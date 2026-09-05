# Profile editor — what is not built yet

Tracked here so it is not lost between sessions. Step numbers refer to the four-step
configuration rework.

## Done since this file was written

- Per-lane placement, rotation, mirroring, crop, scale and pan are edited in the profile
  editor and drawn by `FourLaneContainer`. They used to be stored and read by nobody.
- Recording parameters (frame rate, bitrate, codec, segment length, which cameras record)
  come from the profile, per camera.
- The editor lives in Settings → Recording and is built like the rest of Settings.
- Photos go through the camera's JPEG channel by default, so the photo resolution applies.

## Step 4 — the cabin cameras still use the old display path

The composite camera's lanes are drawn from the profile. The two cabin cameras are separate
`TextureView`s, and their rotation, mirroring, crop and correction still come from
`CustomLayoutManager` and `PreviewCorrection`, reading the old per-camera settings.

So a cabin camera's lane in the profile carries values that nothing reads — the same defect
that was just fixed for the composite lanes, one layer down. Merging them means the main
screen layout has to be driven by the profile rather than by a fixed XML per camera count.

Until that happens:

- editing a cabin lane's rotation or mirroring in the editor has no effect
- panes cannot be moved or resized (only the composite lanes can)
- the three camera-wiring paths cannot merge into one

## Custom camera mapping

`Profile.PRESET_CUSTOM` still delegates to the old camera-mapping data
(`CustomCameraConfigFragment`). Camera ids and names are not part of a profile yet.
