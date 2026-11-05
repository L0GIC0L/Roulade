package io.github.l0gic0l.roulade.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check on a real device or emulator: decode, magnify, encode, then measure.
 * The input clip (see tools/make_test_clip.py) has a dark bar vibrating by 0.3 px at 3 Hz.
 */
@RunWith(AndroidJUnit4::class)
class VideoAmplifierTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun amplifiesSubPixelVibration() = runBlocking {
        val input = File(context.cacheDir, "vibrating_bar.mp4")
        testAssets.open("vibrating_bar.mp4").use { a -> input.outputStream().use { a.copyTo(it) } }
        val (info, _) = VideoInfo.probe(context, Uri.fromFile(input))
        assertEquals(640, info.width)
        assertEquals(360, info.height)
        assertEquals(30f, info.frameRate, 0.5f)

        val output = File(context.cacheDir, "vibrating_bar_amplified.mp4")
        val settings = AnalysisSettings(alpha = 30f, lowHz = 2.0, highHz = 4.5, captureFps = 30.0, shortSide = 360, stabilize = false)
        val clip = Clip(Uri.fromFile(input), info, null, "test")
        VideoAmplifier(context).amplify(clip, settings, output) { _, _ -> }

        val (inFrames, inAmp) = barMotion(input)
        val (outFrames, outAmp) = barMotion(output)
        assertTrue("output has $outFrames of $inFrames frames", outFrames >= inFrames - 2)
        assertTrue("bar moved $inAmp px in, $outAmp px out", outAmp > 5 * inAmp)
    }

    /** Frame count and peak displacement (px) of the bar over the second half of the clip. */
    private fun barMotion(file: File): Pair<Int, Double> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
            val centroids = ArrayList<Double>()
            var i = count / 2
            while (i < count) {
                val n = minOf(15, count - i)
                r.getFramesAtIndex(i, n).forEach { centroids += barCentroid(it); it.recycle() }
                i += n
            }
            return count to (centroids.max() - centroids.min()) / 2
        } finally {
            r.release()
        }
    }

    /** Darkness-weighted mean row of the bar, from rows near the middle of the frame. */
    private fun barCentroid(frame: Bitmap): Double {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        fun rowMean(y: Int): Double {
            var s = 0.0
            for (x in w / 6 until w - w / 6) s += pixels[y * w + x] and 0xFF
            return s / (w - 2 * (w / 6))
        }
        val background = (10 until h / 4).map(::rowMean).average()
        var sum = 0.0
        var weight = 0.0
        for (y in h / 2 - 40 until h / 2 + 40) {
            val d = (background - rowMean(y)).coerceAtLeast(0.0)
            sum += y * d
            weight += d
        }
        return sum / weight
    }
}
