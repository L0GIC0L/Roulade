package io.github.l0gic0l.roulade.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/** What the app needs to know about a source video before processing it. */
data class VideoInfo(
    /** Encoded (unrotated) frame size. */
    val width: Int,
    val height: Int,
    /** Clockwise rotation the player applies, in degrees. */
    val rotation: Int,
    val durationMs: Long,
    /** Frames per second of the file's own timeline. */
    val frameRate: Float,
    /** Real capture rate for slow-motion clips, when the file records it. */
    val captureFrameRate: Float?,
) {
    /** Width / height as the video is shown on screen. */
    val displayAspect: Float
        get() = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width

    companion object {
        fun probe(context: Context, uri: Uri): Pair<VideoInfo, Bitmap?> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull()
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    throw IllegalArgumentException("That file has no video track.")
                }
                val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0
                val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0
                val rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
                val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frameCount = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                val fps = if (frameCount != null && durationMs > 0) frameCount * 1000f / durationMs else 30f
                val capture = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.takeIf { it > 0f }
                val thumb = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960)
                return VideoInfo(w, h, rotation, durationMs, fps, capture) to thumb
            } finally {
                r.release()
            }
        }
    }
}
