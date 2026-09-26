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
- Surround lanes are placed on a drag-and-resize stage (0.49.0).
- The editor asks for a recording quality preset first, then per-camera tuning; the three
  cameras are always listed and switched on or off (0.49.0–0.51.0).

## Crop is switched off

`LaneOrientation.CROP_SUPPORTED` is `false`. Setting a crop on a surround lane wrecks the
main screen, and three attempts to fix it by reading the code each broke something else:

| version | what drawLane did | crop | rotation |
|---|---|---|---|
| 0.44.0 | no extra clip | picture re-fits (wrong but drawn) | leaks neighbours into the margin |
| 0.44.1 | `clipRect(sourceRect)` after `concat` | **static** | correct |
| 0.45.0 | same clip + crop keeps the frame still | **static** | correct |
| 0.45.1 | same clip, crop placement reverted | **static** | correct |
| 0.45.2 | `clipRect(visibleRect)` before `concat` | panes overlap, two panes vanish | **broken: every cell shows the whole strip** |
| now | back to 0.45.1's clip, crop forced to 0 | n/a | correct |

What that table says: rotation is only correct with the clip applied *after* the transform,
and crop only misbehaves when that same clip is in play. Nothing in the arithmetic explains
it — the numbers were worked through by hand for the failing case and they land where they
should. The remaining suspect is how the renderer clips a `TextureView`'s layer inside a
transformed canvas, which is not something the source shows.

To pick it up again, the cheap experiments in order:

1. Set a crop and photograph the result. "Static" vs "the neighbouring lane, magnified" are
   different bugs and the difference is visible.
2. Log `sourceRect`, `destinationRect` and the matrix for the cropped lane on the vehicle
   and check them against the numbers derived here.
3. Try `canvas.saveLayer` around the lane draw, or draw the lane through an offscreen
   bitmap once, to see whether the clip is the thing that fails.

Stored crop values are left alone — the drawing paths just treat them as 0 — so flipping
the constant back to `true` restores whatever the user had.

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
stage (0.49.0) was built without merging them, because it only edits surround lanes. The
merge belongs with the step above: once a cabin pane can be placed, both paths place a
picture in a rectangle and should share one implementation.
