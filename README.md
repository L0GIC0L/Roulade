# Roulade

A motion amplification Android app. It makes small movements visible, such as a
vibrating pump, a swaying beam or someone breathing, by enlarging only the motion
inside a frequency band you choose.

## Using it

1. **Record video** (opens your camera app) or **Choose video** from the gallery.
2. Set the parameters:
   - **Amplification:** roughly how much in-band motion is enlarged. 10–30 is a good start.
     Higher values also enlarge noise and can smear edges.
   - **Frequency band (Hz):** only motion between *Low* and *High* is amplified. *High*
     must be below half the frame rate (the Nyquist limit).
   - **Capture frame rate:** detected automatically. For slow-motion clips stored at a
     different playback rate, enter the real capture rate (e.g. 240) so the band is in real Hz.
   - **Output resolution:** 480p, 720p or 1080p. Higher is sharper but slower.
3. Tap **Amplify motion** and wait. The screen stays on while it works.
4. Toggle **Amplified / Original** to compare, then **Save to gallery** (Movies/Roulade) or **Share**.

### Getting good results

- Keep the camera still: use a tripod or brace it. Camera shake is motion too.
- Use bright, steady light. Sensor noise in dim scenes gets amplified.
- To see motion at *f* Hz you need more than *2f* frames per second. A machine running
  at 1800 rpm (30 Hz) needs 60 fps at minimum; slow-motion (120/240 fps) is better.
- Short clips (5–20 s) process fastest.

## How it works

Roulade uses phase-based motion magnification with a **Riesz pyramid** (Wadhwa,
Rubinstein, Durand & Freeman, *Riesz Pyramids for Fast Phase-Based Video Magnification*,
ICCP 2014). Of the published methods it is the one designed to be fast, and it handles
motion better than the older linear (intensity-based) Eulerian method.

For each frame:

1. The luminance is split into a Laplacian pyramid (one band-pass image per scale).
2. At each pixel of each band, an approximate Riesz transform (two 3-tap filters) gives
   the local *phase* and orientation. Phase changes when an edge moves.
3. The change in phase since the previous frame is accumulated and passed through a
   first-order Butterworth band-pass filter in time. That keeps only motion in your band.
4. The filtered phase is smoothed spatially (weighted by local amplitude to suppress
   noise), multiplied by the amplification factor and applied back as a phase shift.
5. The pyramid is collapsed back into a frame.

The filter is causal, so frames stream through one at a time with constant memory.
Video is decoded and encoded with the platform `MediaCodec` (hardware where available).

### Limitations

- Processing runs on the CPU after recording, not live.
- Only luminance is amplified; colour is carried over from the original. With very large
  amplification you may see slight colour fringing at moving edges.
- Audio is dropped.
- The amplification factor is nominal. The fast Riesz approximation reads phase slightly
  low at the finest scale, so real gain is about 75–80 % of the setting.
- HDR / 10-bit video isn't supported; record in standard (SDR) mode.

## Building

Requires JDK 17+ and the Android SDK (API 36). Open the project in Android Studio, or:

```sh
./gradlew assembleDebug            # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # DSP unit tests (JVM, no device needed)
./gradlew connectedDebugAndroidTest  # end-to-end test on a connected phone or emulator
```

Every push to `main` and every pull request builds a debug APK in GitHub Actions; download it
from the run's *Artifacts* section.

Minimum Android version: 10 (API 29).

## Project layout

```
app/src/main/java/io/github/l0gic0l/roulade/
  dsp/     Laplacian pyramid, Butterworth band-pass, Riesz magnifier, resampling (pure Kotlin)
  video/   MediaCodec decode → magnify → encode pipeline, video probing
  ui/      Jetpack Compose screens and the ViewModel
app/src/test/        JVM unit tests for the DSP code
app/src/androidTest/ End-to-end test using a synthetic clip (tools/make_test_clip.py)
```
