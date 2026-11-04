package io.github.l0gic0l.roulade.analysis

import io.github.l0gic0l.roulade.stab.StabilizerSettings

/** What the output video shows. */
enum class ViewMode(val label: String) {
    AMPLIFIED("Amplified"),
    SIDE_BY_SIDE("Side by side"),
    MOTION_ONLY("Motion only"),
    HEATMAP("Heatmap"),
}

/** Encoder bit budget per pixel per frame. */
enum class OutputQuality(val label: String, val bitsPerPixel: Float) {
    STANDARD("Standard", 0.12f),
    HIGH("High", 0.2f),
    MAX("Max", 0.35f),
}

/** Every user-adjustable processing parameter. Defaults suit a tripod shot of a structure. */
data class AnalysisSettings(
    // Frequency band
    val lowHz: Double = 1.0,
    val highHz: Double = 4.0,
    val filterOrder: Int = 1,
    /** Real capture rate; null = use the clip's own rate. */
    val captureFps: Double? = null,

    // Amplification
    val alpha: Float = 20f,
    /** Largest phase shift in radians; 0 = unlimited. */
    val phaseLimit: Float = 0f,
    val skipFineLevels: Int = 0,
    val denoiseSigma: Float = 2f,

    // Stabilisation (needs a sensor log recorded in Roulade)
    val stabilize: Boolean = true,
    val stabilizer: StabilizerSettings = StabilizerSettings(),
    /** Edge crop that hides borders exposed by stabilisation, percent per side. */
    val cropPercent: Float = 4f,

    // Clip and timing
    val trimStartS: Float = 0f,
    /** null = to the end. */
    val trimEndS: Float? = null,
    /** Output playback speed; 0.25 = four times slower than real time. */
    val playbackSpeed: Float = 1f,

    // Output
    val shortSide: Int = 720,
    val viewMode: ViewMode = ViewMode.AMPLIFIED,
    val heatmapOpacity: Float = 0.6f,
    val grayscale: Boolean = false,
    val quality: OutputQuality = OutputQuality.HIGH,

    // Frequency finder
    val rejectCameraShake: Boolean = true,
) {
    /** Problem with the settings for a clip at [fps], or null when they are usable. */
    fun problem(fps: Double, durationS: Float): String? {
        val end = trimEndS ?: durationS
        return when {
            fps <= 0 -> "Enter the capture frame rate."
            lowHz <= 0 -> "The low cutoff must be above 0 Hz."
            highHz <= lowHz -> "The high cutoff must be above the low cutoff."
            highHz >= fps / 2 -> "The high cutoff must be below ${fmt(fps / 2)} Hz (half the frame rate)."
            end - trimStartS < 1f -> "Keep at least 1 second of the clip."
            else -> null
        }
    }

    companion object {
        fun fmt(v: Double): String =
            if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

/** Starting points for common structural-analysis subjects. */
enum class BandPreset(val label: String, val lowHz: Double, val highHz: Double, val hint: String) {
    SWAY("Building sway", 0.1, 1.0, "Tall buildings, towers, masts"),
    CABLES("Cables & stays", 0.5, 5.0, "Stay cables, guy wires, lines"),
    FOOTFALL("Floors & footbridges", 1.5, 10.0, "Walking excitation, floor modes"),
    PIPING("Piping & ducts", 5.0, 30.0, "Needs 60+ fps"),
    MACHINES("Machinery", 10.0, 60.0, "Needs slow motion (120–240 fps)"),
}
