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

## Step 4 — the cabin panes still cannot be moved or resized

A cabin camera's rotation, mirroring, crop, zoom and pan now come from its lane in the
profile (`LaneSurfaceMatrix`, applied as the `TextureView`'s base transform). What is
still fixed is the *pane*: each cabin camera occupies a slot in a per-camera-count XML
layout, so a lane's x/y/width/height are read only for the composite camera.

Merging that means the main screen layout has to be driven by the profile rather than by
a fixed XML per camera count.

Until that happens:

- a cabin lane's position and size have no effect (rotation, mirroring and crop do)
- the three camera-wiring paths cannot merge into one

The geometry is also written twice: `FourLaneContainer.drawLane` places a lane inside a
grid cell, `LaneSurfaceMatrix` places a whole frame inside a `TextureView`. They share the
axis mapping (`LaneOrientation`) but not the fit-and-rotate arithmetic. The drag-and-resize
editor is the moment to merge them — doing it earlier means touching the container's
drawing for no visible gain.

## Custom camera mapping

`Profile.PRESET_CUSTOM` still delegates to the old camera-mapping data
(`CustomCameraConfigFragment`). Camera ids and names are not part of a profile yet.
