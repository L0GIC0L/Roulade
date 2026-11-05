#!/usr/bin/env python3
"""Builds the synthetic clip used by the instrumented test.

A soft dark bar on a textured background moves up and down by 0.3 px at 3 Hz,
which is too small to see. Needs numpy and ffmpeg.

    python3 tools/make_test_clip.py app/src/androidTest/assets/vibrating_bar.mp4
"""
import subprocess
import sys

import numpy as np

W, H, FPS, SECONDS = 640, 360, 30, 4
BAR_Y, BAR_SIGMA, BAR_DEPTH = 180.0, 6.0, 120.0
AMPLITUDE_PX, MOTION_HZ = 0.3, 3.0


def main(out_path: str) -> None:
    rng = np.random.default_rng(7)
    texture = rng.normal(0.0, 8.0, (H, W))
    ys = np.arange(H, dtype=np.float64)[:, None]
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error",
         "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
         "-c:v", "libx264", "-preset", "slow", "-crf", "12", "-pix_fmt", "yuv420p",
         "-movflags", "+faststart", out_path],
        stdin=subprocess.PIPE,
    )
    for n in range(FPS * SECONDS):
        shift = AMPLITUDE_PX * np.sin(2 * np.pi * MOTION_HZ * n / FPS)
        bar = BAR_DEPTH * np.exp(-((ys - BAR_Y - shift) / BAR_SIGMA) ** 2)
        frame = np.clip(180.0 + texture - bar, 0, 255).astype(np.uint8)
        ffmpeg.stdin.write(frame.tobytes())
    ffmpeg.stdin.close()
    sys.exit(ffmpeg.wait())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "vibrating_bar.mp4")
