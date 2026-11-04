package io.github.l0gic0l.roulade.video

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import io.github.l0gic0l.roulade.dsp.AreaResampler
import io.github.l0gic0l.roulade.dsp.RieszMagnifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** User-chosen processing parameters. */
data class AmplifySettings(
    /** Phase gain; in-band motion grows by roughly `1 + alpha`. */
    val alpha: Float,
    val lowHz: Double,
    val highHz: Double,
    /** Real frames per second at capture (differs from the file rate for slow-motion clips). */
    val captureFps: Double,
    /** Output short side in pixels; the source is never upscaled. */
    val shortSide: Int,
)

/** Output size for a source frame, rounded to multiples of 16 for encoder compatibility. */
fun outputSize(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
    val scale = min(1.0, shortSide.toDouble() / min(width, height))
    fun round16(v: Double) = max(16, (v / 16).roundToInt() * 16)
    return round16(width * scale) to round16(height * scale)
}

/**
 * Decodes a video with [MediaCodec], motion-magnifies its luminance with [RieszMagnifier],
 * and encodes the result as H.264 into an MP4. Colour (chroma) is carried over unchanged and
 * audio is dropped.
 */
class VideoAmplifier(private val context: Context) {

    suspend fun amplify(
        input: Uri,
        info: VideoInfo,
        settings: AmplifySettings,
        output: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val job = Transcode(input, info, settings, output, onProgress)
        var ok = false
        try {
            job.run()
            ok = true
        } finally {
            job.release()
            if (!ok) output.delete()
        }
    }

    private inner class Transcode(
        private val input: Uri,
        private val info: VideoInfo,
        private val settings: AmplifySettings,
        private val output: File,
        private val onProgress: (Float) -> Unit,
    ) {
        private val extractor = MediaExtractor()
        private var decoder: MediaCodec? = null
        private var encoder: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var muxerTrack = -1
        private var muxerStarted = false
        private var samplesWritten = 0

        // Created on the first decoded frame, once the visible frame size is known.
        private var srcW = 0
        private var srcH = 0
        private var dstW = 0
        private var dstH = 0
        private lateinit var srcY: FloatArray
        private lateinit var srcU: FloatArray
        private lateinit var srcV: FloatArray
        private lateinit var dstY: FloatArray
        private lateinit var dstU: FloatArray
        private lateinit var dstV: FloatArray
        private lateinit var lumaResampler: AreaResampler
        private lateinit var chromaResampler: AreaResampler
        private lateinit var magnifier: RieszMagnifier
        private var rowBuf = ByteArray(0)

        private var firstPts = -1L
        private var lastOutPts = -1L
        private val bufferInfo = MediaCodec.BufferInfo()

        suspend fun run() {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("That file has no video track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val size = extractor.readSampleData(dec.getInputBuffer(idx)!!, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (out >= 0) {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.size > 0) {
                        dec.getOutputImage(out)?.use { processFrame(it, pts) }
                    }
                    dec.releaseOutputBuffer(out, false)
                    if (eos) outputDone = true
                }
            }

            if (encoder == null) throw IllegalStateException("No frames could be decoded from that video.")
            finishEncoding()
            onProgress(1f)
        }

        private fun processFrame(image: Image, ptsUs: Long) {
            if (image.format != ImageFormat.YUV_420_888) {
                throw UnsupportedOperationException(
                    "This video's pixel format isn't supported (HDR / 10-bit?). Record it in standard (SDR) mode."
                )
            }
            val crop = image.cropRect
            if (encoder == null) setUp(crop.width(), crop.height())
            if (crop.width() != srcW || crop.height() != srcH) {
                throw UnsupportedOperationException("The video changes resolution part-way through.")
            }

            // Read the visible area, scale it to the output size, magnify luminance.
            val cl = crop.left
            val ct = crop.top
            readPlane(image.planes[0], cl, ct, srcW, srcH, srcY)
            readPlane(image.planes[1], cl / 2, ct / 2, (srcW + 1) / 2, (srcH + 1) / 2, srcU)
            readPlane(image.planes[2], cl / 2, ct / 2, (srcW + 1) / 2, (srcH + 1) / 2, srcV)
            lumaResampler.resample(srcY, dstY)
            chromaResampler.resample(srcU, dstU)
            chromaResampler.resample(srcV, dstV)
            magnifier.process(dstY, dstY)

            if (firstPts < 0) firstPts = ptsUs
            var outPts = ptsUs - firstPts
            if (outPts <= lastOutPts) outPts = lastOutPts + 1
            lastOutPts = outPts
            encodeFrame(outPts)

            val durationUs = info.durationMs * 1000
            if (durationUs > 0) onProgress((outPts.toFloat() / durationUs).coerceIn(0f, 0.99f))
        }

        private fun setUp(width: Int, height: Int) {
            srcW = width
            srcH = height
            val (w, h) = outputSize(width, height, settings.shortSide)
            dstW = w
            dstH = h
            srcY = FloatArray(srcW * srcH)
            srcU = FloatArray(((srcW + 1) / 2) * ((srcH + 1) / 2))
            srcV = FloatArray(srcU.size)
            dstY = FloatArray(dstW * dstH)
            dstU = FloatArray((dstW / 2) * (dstH / 2))
            dstV = FloatArray(dstU.size)
            lumaResampler = AreaResampler(srcW, srcH, dstW, dstH)
            chromaResampler = AreaResampler((srcW + 1) / 2, (srcH + 1) / 2, dstW / 2, dstH / 2)
            magnifier = RieszMagnifier(
                dstW, dstH, settings.alpha, settings.lowHz, settings.highHz, settings.captureFps,
            )

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                ?: throw IllegalStateException("No H.264 video encoder found.")
            if (!caps.isSizeSupported(dstW, dstH)) {
                throw UnsupportedOperationException(
                    "This phone can't encode ${dstW}x$dstH video. Pick a lower output resolution."
                )
            }
            val fileFps = info.frameRate.roundToInt().coerceIn(1, 240)
            val bitrate = caps.bitrateRange.clamp((dstW * dstH * fileFps * BITS_PER_PIXEL).roundToInt())
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dstW, dstH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fileFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(info.rotation)
            }
        }

        private fun encodeFrame(ptsUs: Long) {
            val enc = encoder!!
            var idx: Int
            while (true) {
                idx = enc.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) break
                drainEncoder(endOfStream = false)
            }
            val image = enc.getInputImage(idx) ?: throw IllegalStateException("Encoder gave no input image.")
            val cl = image.cropRect.left
            val ct = image.cropRect.top
            writePlane(image.planes[0], cl, ct, dstW, dstH, dstY)
            writePlane(image.planes[1], cl / 2, ct / 2, dstW / 2, dstH / 2, dstU)
            writePlane(image.planes[2], cl / 2, ct / 2, dstW / 2, dstH / 2, dstV)
            enc.queueInputBuffer(idx, 0, dstW * dstH * 3 / 2, ptsUs, 0)
            drainEncoder(endOfStream = false)
        }

        private fun finishEncoding() {
            val enc = encoder!!
            var idx: Int
            while (true) {
                idx = enc.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) break
                drainEncoder(endOfStream = false)
            }
            enc.queueInputBuffer(idx, 0, 0, max(lastOutPts, 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(endOfStream = true)
            muxer!!.stop()
            muxerStarted = false
        }

        /** Moves finished packets from the encoder to the muxer. With [endOfStream], waits for the last one. */
        private fun drainEncoder(endOfStream: Boolean) {
            val enc = encoder!!
            var idleRounds = 0
            while (true) {
                val idx = enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++idleRounds > 500) throw IllegalStateException("Encoder stopped responding.")
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val mux = muxer!!
                        muxerTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                        if (bufferInfo.size > 0 && muxerStarted) {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer!!.writeSampleData(muxerTrack, buf, bufferInfo)
                            samplesWritten++
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        private fun readPlane(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int, dst: FloatArray) {
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val len = (w - 1) * ps + 1
            if (rowBuf.size < len) rowBuf = ByteArray(len)
            val row = rowBuf
            for (y in 0 until h) {
                buf.position((top + y) * rs + left * ps)
                buf.get(row, 0, len)
                val o = y * w
                for (x in 0 until w) dst[o + x] = (row[x * ps].toInt() and 0xFF).toFloat()
            }
        }

        private fun writePlane(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int, src: FloatArray) {
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            if (rowBuf.size < w) rowBuf = ByteArray(w)
            val row = rowBuf
            for (y in 0 until h) {
                val base = (top + y) * rs + left * ps
                val o = y * w
                if (ps == 1) {
                    for (x in 0 until w) row[x] = toByte(src[o + x])
                    buf.position(base)
                    buf.put(row, 0, w)
                } else {
                    for (x in 0 until w) buf.put(base + x * ps, toByte(src[o + x]))
                }
            }
        }

        private fun toByte(v: Float): Byte {
            val i = (v + 0.5f).toInt()
            return (if (i < 0) 0 else if (i > 255) 255 else i).toByte()
        }

        fun release() {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxerStarted && samplesWritten > 0) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val BITS_PER_PIXEL = 0.2f
    }
}
