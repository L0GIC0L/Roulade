# Roulade

A motion amplification Android app for structural analysis. It makes small movements
visible, such as a swaying mast, a bouncing footbridge or a vibrating pipe run, by enlarging
only the motion inside a frequency band you choose. Material You design (wallpaper colours
on Android 12+).

## Getting the APK

Every push and pull request is built by GitHub Actions (**Actions → Android build → run →
Artifacts → `roulade-apk`**). Pushing a tag such as `v0.2.0` also publishes the APK as a
GitHub Release. The APK installs on Android 10 or newer.

**Signing.** Without secrets, CI signs with a throwaway debug key. That installs fine, but
a later build can't update it (uninstall first). For a stable signature, add these
repository secrets:

| Secret | Value |
| --- | --- |
| `ROULADE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ROULADE_KEYSTORE_PASSWORD` | keystore password |
| `ROULADE_KEY_ALIAS` | key alias |
| `ROULADE_KEY_PASSWORD` | key password |

Create a keystore with `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias roulade`.

## Workflow

1. **Record with sensors** (recommended). The recording screen logs the gyroscope,
   accelerometer, gravity and linear acceleration alongside the video. It also shows a
   gravity-based spirit level (roll and tilt) and a live shake meter. Options: 30/60 fps,
   720p/1080p/4K, exposure and white-balance lock, phone OIS/EIS off, maximum sensor rate,
   zoom and tap-to-focus. Recordings stay in the app and can be shared as video plus sensor CSV.
   **Or open a video** from the gallery, for example a 120/240 fps slow-motion clip.
2. **Analyse.** Choose the frequency band, or let the frequency finder suggest one. Adjust
   amplification, stabilisation, timing and output, then tap **Amplify**.
3. **Review.** Toggle Result/Original, read the run summary, then save to Movies/Roulade or share.

## Settings

| Group | Setting | What it does |
| --- | --- | --- |
| Frequency band | Presets | Building sway, cables & stays, floors & footbridges, piping & ducts, machinery |
| | Low / High (Hz) | Only motion in this band is amplified; High must be below half the frame rate |
| | Capture frame rate | Detected automatically; enter the real rate for slow-motion files |
| | Filter sharpness | Butterworth order 1–3. Higher orders isolate one mode from its neighbours |
| | Machine-speed helper | RPM × order (1×, 2×, 3×) ± width → band |
| Frequency finder | Scan | Tracks a grid of regions and plots their motion spectrum with peaks; tap a peak or the chart to centre the band on it |
| | Ignore camera shake | Removes motion common to the whole picture before the spectrum |
| Amplification | Factor | Roughly how much in-band motion grows (1–150×) |
| | Ghosting limiter | Caps the phase shift so big factors don't smear edges |
| | Fine detail | Skip the finest 0–2 scales to cut noise and shimmer |
| | Noise reduction | Amplitude-weighted smoothing of the measured motion |
| Stabilisation | Lock view / Smooth | Tripod (remove all camera motion) or handheld (remove shake, keep slow pans) |
| | Pitch & yaw, Roll | Gyroscope rotation correction |
| | Gravity drift correction | Complementary filter with the gravity sensor to cancel gyro drift |
| | Translation | Double-integrated accelerometer with subject distance (close subjects only) |
| | Auto-sync, Offset | Aligns sensor time with the video's own motion; manual offset as fallback |
| | Edge crop | Hides the borders the correction exposes |
| Clip & timing | Trim | Process part of the clip; earlier frames still settle the filter |
| | Playback speed | 1×, 1/2, 1/4, 1/8, so fast vibration is easy to follow |
| Output | View | Amplified, Side by side, Motion only (grey except where things move), Heatmap (where in-band motion is strongest) |
| | Black & white, Resolution, Quality | 480p/720p/1080p; bit rate |

Settings are remembered between launches.

## How it works

**Magnification.** Phase-based motion magnification with a Riesz pyramid (Wadhwa,
Rubinstein, Durand & Freeman, ICCP 2014). Each frame's luminance is split into a Laplacian
pyramid. Local phase comes from an approximate Riesz transform, and its frame-to-frame
change is band-pass filtered in time (cascaded Butterworth sections). The result is smoothed
by local amplitude, multiplied by the factor and applied back as a phase shift. The filter
is causal, so frames stream through with constant memory.

**Stabilisation.** Camera rotation causes most image shake. About 0.001 rad is ~3 px at a
phone's focal length, while 0.05 mm of camera translation at 5 m is ~0.03 px. So:
- The gyroscope is integrated into an orientation track. Gravity corrects drift in pitch
  and roll, and a straight-line (lock) or moving-average (smooth) reference is removed.
- Rotation becomes image shift via the focal length from Camera2 metadata (lens focal length,
  sensor size, zoom) and the frame's orientation.
- The accelerometer is double-integrated with high-pass filtering. It is only used if you
  enable translation and give a subject distance.
- Auto-sync measures the global frame-to-frame motion of the video (Lucas–Kanade) and finds
  the sensor time offset, axis orientation and per-axis gain that best explain it. This is
  the approach gyro-stabilisation tools use. With almost no shake (a good tripod) it falls back
  to the manual offset, and there is then little to correct anyway.
- Frames are warped (bicubic) by the predicted motion before magnification, so camera shake
  is not amplified.

**Frequency finder.** Downscaled frames are tracked against the first frame on a grid of
blocks. Common-mode (camera) motion is removed, and Hann-windowed spectra are summed. Peaks
are found by local maxima and refined by parabolic interpolation.

### Limitations

- Processing happens after recording, on the CPU.
- Sensor stabilisation needs clips recorded in Roulade. Rolling shutter is not modelled.
- Only luminance is amplified; colour is carried over. Audio is dropped. HDR/10-bit
  video is not supported.
- The amplification factor is nominal: real gain is around 75–80% of it at the finest
  scale, and less at very high factors where phase wraps.
- Results are a visual aid, not calibrated displacement measurements.

## Building

Requires JDK 17+ and the Android SDK (API 36). Open in Android Studio, or:

```sh
./gradlew testDebugUnitTest          # DSP and stabiliser unit tests (JVM)
./gradlew assembleRelease            # minified APK at app/build/outputs/apk/release/
./gradlew connectedDebugAndroidTest  # end-to-end test on a phone or emulator
```

## Project layout

```
app/src/main/java/io/github/l0gic0l/roulade/
  dsp/       Laplacian pyramid, Butterworth band-pass, Riesz magnifier, warp, motion estimation, FFT
  stab/      Sensor log (CSV) and the sensor-based stabiliser
  video/     MediaCodec frame reader/writer, amplification pipeline, frequency scanner
  capture/   CameraX recording with sensor logging
  analysis/  Settings model and presets
  ui/        Compose screens (home, capture, analysis, processing, result), theme, components
app/src/test/        JVM unit tests
app/src/androidTest/ End-to-end test on a synthetic clip (tools/make_test_clip.py)
```
