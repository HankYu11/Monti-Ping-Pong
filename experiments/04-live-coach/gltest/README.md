# Testing the capture shaders without a phone

`crop.c` runs the app's capture shaders (`android/app/src/main/assets/shaders`) on Mesa's
software OpenGL ES 3.2 (EGL, surfaceless): RGB frames in, the capture's planes out. The only
change from the app is an ordinary texture instead of the decoder's external one.

    apt-get install libegl-dev libgles-dev
    gcc -O2 crop.c -o crop -lEGL -lGLESv2
    ./crop SHADERS W H x0 y0 w h [601 [yGain yOff uGain uOff vGain vOff]] < frames.rgb > planes.bin

`regpu_same.py CAP CLIP SHADERS OUTDIR` makes a capture (from the desktop tracker) again through
the shaders, labelled as the original, for the engine (`node_cap.mjs`) to compare readings.

Checked (0.7.3): the planes match a NumPy computation of the same crop within rounding (0.6 of
255), rows the right way up; mapped to the decoder's bytes by a straight-line fit of one frame
(ColourFit), within 1 for 99% of pixels (mean 0.24); the engine reads the same captures the same
(M7 44.35 / 31.9 copied, 44.7 / 31.95 from the shaders; M14 51.0 / 48.75 and 51.0 / 48.94;
M6, M12 likewise); the fit picks the matrix the chip used (rms 0.46 vs 0.98, 0.43 vs 1.03).

`TEXH=1088 SIZEH=1080 ./crop ...` emulates a decoder's padded buffer (1088 rows for a 1080 frame)
addressed as 1080 rows, as 0.7.3 did: the fit is off by 10.7 levels (the phone: 7.0); addressed
by the buffer's own height (0.7.4): 0.26.
