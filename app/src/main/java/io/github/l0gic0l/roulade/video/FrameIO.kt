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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Output size for a source frame, rounded to multiples of 16 for encoder compatibility. */
fun outputSize(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
    val scale = min(1.0, shortSide.toDouble() / min(width, height))
    fun round16(v: Double) = max(16, (v / 16).roundToInt() * 16)
    return round16(width * scale) to round16(height * scale)
}

/** Decodes the video track of a file with [MediaCodec], handing out frames as YUV [Image]s. */
class FrameReader(context: Context, uri: Uri) : Closeable {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalArgumentException("That file has no video track.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    /**
     * Decodes from the key frame at or before [startUs] until a frame passes [endUs]. Frames before
     * [startUs] are delivered too (useful to settle filters); [onFrame] returns false to stop early.
     */
    suspend fun read(startUs: Long, endUs: Long, onFrame: (image: Image, ptsUs: Long) -> Boolean) {
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var inputDone = false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(idx)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (out >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                var keepGoing = true
                if (info.size > 0 && pts <= endUs) {
                    decoder.getOutputImage(out)?.use { image ->
                        if (image.format != ImageFormat.YUV_420_888) {
                            throw UnsupportedOperationException(
                                "This video's pixel format isn't supported (HDR / 10-bit?). Record it in standard (SDR) mode."
                            )
                        }
                        keepGoing = onFrame(image, pts)
                    }
                }
                decoder.releaseOutputBuffer(out, false)
                if (eos || !keepGoing || pts > endUs) return
            }
        }
    }

    override fun close() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

/** Reads the visible area of decoded frames and scales it (area averaging) to a working size. */
class FrameScaler(val srcWidth: Int, val srcHeight: Int, val width: Int, val height: Int, private val withChroma: Boolean) {
    val y = FloatArray(width * height)
    val u = FloatArray(if (withChroma) (width / 2) * (height / 2) else 0)
    val v = FloatArray(u.size)

    private val chromaSrcW = (srcWidth + 1) / 2
    private val chromaSrcH = (srcHeight + 1) / 2
    private val srcY = FloatArray(srcWidth * srcHeight)
    private val srcU = FloatArray(if (withChroma) chromaSrcW * chromaSrcH else 0)
    private val srcV = FloatArray(srcU.size)
    private val lumaResampler = AreaResampler(srcWidth, srcHeight, width, height)
    private val chromaResampler = if (withChroma) AreaResampler(chromaSrcW, chromaSrcH, width / 2, height / 2) else null
    private var row = ByteArray(0)

    fun load(image: Image) {
        val crop = image.cropRect
        if (crop.width() != srcWidth || crop.height() != srcHeight) {
            throw UnsupportedOperationException("The video changes resolution part-way through.")
        }
        readPlane(image.planes[0], crop.left, crop.top, srcWidth, srcHeight, srcY)
        lumaResampler.resample(srcY, y)
        val cr = chromaResampler ?: return
        readPlane(image.planes[1], crop.left / 2, crop.top / 2, chromaSrcW, chromaSrcH, srcU)
        readPlane(image.planes[2], crop.left / 2, crop.top / 2, chromaSrcW, chromaSrcH, srcV)
        cr.resample(srcU, u)
        cr.resample(srcV, v)
    }

    private fun readPlane(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int, dst: FloatArray) {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val len = (w - 1) * ps + 1
        if (row.size < len) row = ByteArray(len)
        val r = row
        for (yy in 0 until h) {
            buf.position((top + yy) * rs + left * ps)
            buf.get(r, 0, len)
            val o = yy * w
            for (x in 0 until w) dst[o + x] = (r[x * ps].toInt() and 0xFF).toFloat()
        }
    }
}

/** Encodes YUV 4:2:0 float planes to H.264 in an MP4. */
class FrameWriter(
    output: File,
    val width: Int,
    val height: Int,
    frameRate: Float,
    bitsPerPixel: Float,
    rotationDeg: Int,
) : Closeable {
    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samples = 0
    private var lastPts = -1L
    private var row = ByteArray(0)

    init {
        val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            ?: throw IllegalStateException("No H.264 video encoder found.")
        if (!caps.isSizeSupported(width, height)) {
            encoder.release()
            throw UnsupportedOperationException(
                "This phone can't encode ${width}x$height video. Pick a lower output resolution or another view."
            )
        }
        val fps = frameRate.roundToInt().coerceIn(1, 240)
        val bitrate = caps.bitrateRange.clamp((width.toLong() * height * fps * bitsPerPixel).toInt().coerceAtLeast(500_000))
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotationDeg)
    }

    /** [y] is `width x height`; [u] and [v] are half size in both directions. */
    fun write(y: FloatArray, u: FloatArray, v: FloatArray, ptsUs: Long) {
        var pts = ptsUs
        if (pts <= lastPts) pts = lastPts + 1
        lastPts = pts
        val idx = dequeueInput()
        val image = encoder.getInputImage(idx) ?: throw IllegalStateException("Encoder gave no input image.")
        val cl = image.cropRect.left
        val ct = image.cropRect.top
        writePlane(image.planes[0], cl, ct, width, height, y)
        writePlane(image.planes[1], cl / 2, ct / 2, width / 2, height / 2, u)
        writePlane(image.planes[2], cl / 2, ct / 2, width / 2, height / 2, v)
        encoder.queueInputBuffer(idx, 0, width * height * 3 / 2, pts, 0)
        drain(false)
    }

    fun finish() {
        val idx = dequeueInput()
        encoder.queueInputBuffer(idx, 0, 0, max(lastPts, 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        if (samples == 0) throw IllegalStateException("No frames were written.")
        muxer.stop()
        muxerStarted = false
    }

    private fun dequeueInput(): Int {
        while (true) {
            val idx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) return idx
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > 500) throw IllegalStateException("Encoder stopped responding.")
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        samples++
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun writePlane(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int, src: FloatArray) {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        if (row.size < w) row = ByteArray(w)
        val r = row
        for (yy in 0 until h) {
            val base = (top + yy) * rs + left * ps
            val o = yy * w
            if (ps == 1) {
                for (x in 0 until w) r[x] = toByte(src[o + x])
                buf.position(base)
                buf.put(r, 0, w)
            } else {
                for (x in 0 until w) buf.put(base + x * ps, toByte(src[o + x]))
            }
        }
    }

    private fun toByte(v: Float): Byte {
        val i = (v + 0.5f).toInt()
        return (if (i < 0) 0 else if (i > 255) 255 else i).toByte()
    }

    override fun close() {
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { if (muxerStarted && samples > 0) muxer.stop() }
        runCatching { muxer.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
