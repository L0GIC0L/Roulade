package io.github.l0gic0l.roulade.ui

import android.content.Context
import androidx.core.content.edit
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import io.github.l0gic0l.roulade.analysis.OutputQuality
import io.github.l0gic0l.roulade.analysis.ViewMode
import io.github.l0gic0l.roulade.capture.CaptureConfig
import io.github.l0gic0l.roulade.stab.StabilizerSettings
import org.json.JSONObject

/** Remembers the last-used settings between launches. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun loadAnalysis(): AnalysisSettings = runCatching {
        val j = JSONObject(prefs.getString(KEY_ANALYSIS, null) ?: return AnalysisSettings())
        val d = AnalysisSettings()
        val sj = j.optJSONObject("stabilizer") ?: JSONObject()
        val sd = StabilizerSettings()
        AnalysisSettings(
            lowHz = j.optDouble("lowHz", d.lowHz),
            highHz = j.optDouble("highHz", d.highHz),
            filterOrder = j.optInt("filterOrder", d.filterOrder).coerceIn(1, 3),
            captureFps = null, // always re-detected per clip
            alpha = j.optDouble("alpha", d.alpha.toDouble()).toFloat(),
            phaseLimit = j.optDouble("phaseLimit", d.phaseLimit.toDouble()).toFloat(),
            skipFineLevels = j.optInt("skipFineLevels", d.skipFineLevels),
            denoiseSigma = j.optDouble("denoiseSigma", d.denoiseSigma.toDouble()).toFloat(),
            stabilize = j.optBoolean("stabilize", d.stabilize),
            stabilizer = StabilizerSettings(
                lockView = sj.optBoolean("lockView", sd.lockView),
                smoothSeconds = sj.optDouble("smoothSeconds", sd.smoothSeconds.toDouble()).toFloat(),
                rotation = sj.optBoolean("rotation", sd.rotation),
                roll = sj.optBoolean("roll", sd.roll),
                gravityFusion = sj.optBoolean("gravityFusion", sd.gravityFusion),
                translation = sj.optBoolean("translation", sd.translation),
                subjectDistanceM = sj.optDouble("subjectDistanceM", sd.subjectDistanceM.toDouble()).toFloat(),
                autoSync = sj.optBoolean("autoSync", sd.autoSync),
                offsetMs = sj.optDouble("offsetMs", sd.offsetMs.toDouble()).toFloat(),
            ),
            cropPercent = j.optDouble("cropPercent", d.cropPercent.toDouble()).toFloat(),
            playbackSpeed = j.optDouble("playbackSpeed", d.playbackSpeed.toDouble()).toFloat(),
            shortSide = j.optInt("shortSide", d.shortSide),
            viewMode = runCatching { ViewMode.valueOf(j.optString("viewMode")) }.getOrDefault(d.viewMode),
            heatmapOpacity = j.optDouble("heatmapOpacity", d.heatmapOpacity.toDouble()).toFloat(),
            grayscale = j.optBoolean("grayscale", d.grayscale),
            quality = runCatching { OutputQuality.valueOf(j.optString("quality")) }.getOrDefault(d.quality),
            rejectCameraShake = j.optBoolean("rejectCameraShake", d.rejectCameraShake),
        )
    }.getOrDefault(AnalysisSettings())

    fun saveAnalysis(s: AnalysisSettings) {
        val st = s.stabilizer
        val j = JSONObject()
            .put("lowHz", s.lowHz).put("highHz", s.highHz).put("filterOrder", s.filterOrder)
            .put("alpha", s.alpha.toDouble()).put("phaseLimit", s.phaseLimit.toDouble())
            .put("skipFineLevels", s.skipFineLevels).put("denoiseSigma", s.denoiseSigma.toDouble())
            .put("stabilize", s.stabilize)
            .put(
                "stabilizer",
                JSONObject()
                    .put("lockView", st.lockView).put("smoothSeconds", st.smoothSeconds.toDouble())
                    .put("rotation", st.rotation).put("roll", st.roll).put("gravityFusion", st.gravityFusion)
                    .put("translation", st.translation).put("subjectDistanceM", st.subjectDistanceM.toDouble())
                    .put("autoSync", st.autoSync).put("offsetMs", st.offsetMs.toDouble()),
            )
            .put("cropPercent", s.cropPercent.toDouble()).put("playbackSpeed", s.playbackSpeed.toDouble())
            .put("shortSide", s.shortSide).put("viewMode", s.viewMode.name)
            .put("heatmapOpacity", s.heatmapOpacity.toDouble()).put("grayscale", s.grayscale)
            .put("quality", s.quality.name).put("rejectCameraShake", s.rejectCameraShake)
        prefs.edit { putString(KEY_ANALYSIS, j.toString()) }
    }

    fun loadCapture(): CaptureConfig = runCatching {
        val j = JSONObject(prefs.getString(KEY_CAPTURE, null) ?: return CaptureConfig())
        val d = CaptureConfig()
        CaptureConfig(
            fps = j.optInt("fps", d.fps),
            quality = j.optString("quality", d.quality),
            lockExposure = j.optBoolean("lockExposure", d.lockExposure),
            disablePhoneStabilization = j.optBoolean("disablePhoneStabilization", d.disablePhoneStabilization),
            fastestSensors = j.optBoolean("fastestSensors", d.fastestSensors),
        )
    }.getOrDefault(CaptureConfig())

    fun saveCapture(c: CaptureConfig) {
        val j = JSONObject()
            .put("fps", c.fps).put("quality", c.quality).put("lockExposure", c.lockExposure)
            .put("disablePhoneStabilization", c.disablePhoneStabilization).put("fastestSensors", c.fastestSensors)
        prefs.edit { putString(KEY_CAPTURE, j.toString()) }
    }

    private companion object {
        const val KEY_ANALYSIS = "analysis"
        const val KEY_CAPTURE = "capture"
    }
}
