# Profile editor — what is not built yet

Tracked here so it is not lost between sessions. Step numbers refer to the four-step
configuration rework.

## Step 4 — per-lane display parameters

`LaneLayout` already carries these fields and the migration fills them in, but the editor
has no controls for them, so they can only be changed by editing storage:

| Field | Where it came from | Notes |
|---|---|---|
| `cropTop/Bottom/Left/Right` | `CustomLayoutManager` | Old values were pixels; the model stores fractions, so nothing was migrated. Must be set fresh. |
| `scaleX/scaleY` | `PreviewCorrection` | Migrated only when the correction switch was on. |
| `translateX/translateY` | `PreviewCorrection` | Same. |

Crop removes edges and changes the aspect ratio; scale and translate move the picture
without changing it. Different jobs, both worth keeping.

## Step 4 — pane position and size

`LaneLayout.x/y/width/height` exist as fractions of the container and the four-up grid is
migrated as four equal cells, but nothing reads them yet: `FourLaneContainer` still computes
the 2×2 grid itself, and the main screen layout is still a fixed XML per camera count.

Until this is read from the profile:

- panes cannot be moved or resized
- adding a camera to a profile can only work because the layout is picked by camera count

This is the piece that lets the three camera-wiring paths finally merge into one.

## Photo channel

The photo resolution in a profile only applies when **Developer options → Photo via the
image channel** is on. With it off, photos are grabbed from the preview and follow the
preview resolution. The editor should say so on the photo row.
