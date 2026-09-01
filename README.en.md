# Zeekr Shortcut (Car Version)

A surround-view dashcam for Zeekr 7X head units. On the device the app is named
极氪即刻（车机版）.

[简体中文](README.md) · [Attribution](NOTICE.md) · [Platform notes](docs/zeekr-platform-notes.md) · [Changelog](CHANGELOG.md)

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by or endorsed by Zeekr,
> and not certified for any automotive functional-safety purpose. It is **not** a replacement
> for a factory dashcam, reversing camera, blind-spot monitor or any legally required safety
> device. Do not interact with it while driving. Use at your own risk — see "Safety notice" below.

---

## What it does

Zeekr App Lab exposes only **one already-composited video stream** to third-party apps — four
1280×1280 square views packed into a single frame. Previewed directly, that is a tall,
distorted strip.

This app splits that stream correctly into a 2×2 grid and attaches a complete dashcam feature
set on top. Neither part was written from scratch; both build on existing projects — see
"Attribution" below.

---

## Features

**Display** — the composite stream is split by real geometry into a 2×2 grid without distorting
the square aspect ratio. Tap to toggle grid / single view; tap again in single view to cycle to
the next lane.

**Recording** — H.264 / H.265; stored either as a 2×2 grid or as the raw strip; adjustable frame
rate (25 fps native, down to 10 fps), resolution and bitrate (low / medium / high — all three
actually take effect); automatic 1 / 3 / 5 / 10-minute segmentation with the oldest files
cleaned up once the storage cap is reached. Optional timestamp and recording-spec overlays; the
app name and version are always stamped in the top-left corner.

**Storage** — records to external USB storage only by default. Writing to internal storage
requires unlocking Developer Options first, to avoid wearing out the head unit's flash memory
with continuous writes. Storage locations are listed as actually detected (volume name + free
space).

**Super rear-view mirror** — a dockable floating window that enlarges any one lane: pinch to
zoom, swipe left/right to rotate through the lanes clockwise / counter-clockwise, drag vertically
in the middle third to adjust framing. Fisheye correction and field of view are adjustable. The
window resizes to any width and height while **the picture's aspect ratio never changes**. Push
it half off-screen or flick it at an edge to dock; tap it or pull it back to slide it in again.
The rear lane is mirrored horizontally, like a real mirror — preview, recording and playback are
unaffected.

**Playback** — continuous timeline playback that carries across segment boundaries, at
0.5× / 1× / 1.5× / 2× speed; tap any cell to enlarge it to a single lane. Photos are grouped by
date.

**Floating windows** — a preview floating window and a floating record button, with adjustable
size and opacity.

**Keep-alive** — launches on boot, runs as a foreground service, records with the screen off and
keeps going after the car is locked.

**Settings** — a two-pane screen (categories on the left, content on the right): Recording /
Storage / Super mirror / Floating windows / System / Advanced / About.

---

## How the composite stream is split

```
Camera2 (the head unit's single composite stream, e.g. 1280x5140)
        |  a plain TextureView is the sole camera consumer, entirely unmodified
        v
the parent container redraws that same child view 4 times, each cropped to
one cell and mapped to its source rectangle
        v
2x2 grid
```

- **The camera pipeline is untouched.** Substituting an OpenGL-owned producer for the camera's —
  the more elegant-looking approach — crashes on a real vehicle; this project was torn down and
  rewritten around that finding once already.
- **Geometry is computed from the composite stream's real size, not the buffer size** — the HAL
  sometimes declares only a flattened hint size while the content is still the full composite.
- **Never invents a resolution** — only sizes the HAL actually declares; if none match, it says
  so explicitly.
- **Safe fallback** to an equal four-way split when the separator bands don't line up.

Details in [`docs/zeekr-platform-notes.md`](docs/zeekr-platform-notes.md).

---

## Install

Download `ZeekrShortcut-*.apk` from [Releases](../../releases) and sideload it via App Lab.

Build it yourself (JDK 17+, Android SDK, compileSdk 36):

```bash
git clone https://github.com/dts88/zeekr-shortcut-car.git
cd zeekr-shortcut-car && ./gradlew assembleRelease
```

Output lands in `app/build/outputs/apk/release/`. The repo includes a public AOSP test-signing
key (password `android`); for a real release supply your own via `ZEEKR_KEYSTORE`,
`ZEEKR_KEYSTORE_PASSWORD`, `ZEEKR_KEY_ALIAS`, `ZEEKR_KEY_PASSWORD`.

After installing, go to **Settings → Recording → Video stream configuration**, choose
"Zeekr 7X (surround composite stream)" and restart the app as prompted. If it reports that no
composite stream was detected, this head unit's or firmware's Camera2 doesn't declare a
composite stream size and may not be supported.

---

## Known limitations

- **Designed only against the Zeekr 7X composite format.** Other models or firmware versions with
  a different layout fall back to an equal split and may be offset.
- **Factory functions take priority** — the factory 360° surround view, reversing camera or
  parking camera may reclaim the camera at any time, and this app should yield.
- **Heavy write volume** — roughly 200 MB/min of continuous writes, which is why USB storage is
  the default: it spares the head unit's flash memory and is easy to pull out and read.
- **Real-vehicle verification is ongoing** — automated tests cover pure logic only (geometry
  split, camera selection, gesture model); capture and the recording pipeline need verification
  in the car. Test on a stationary vehicle first.

---

## Attribution

This app's capability comes from two projects. Full sources, licensing and boundaries in
[NOTICE.md](NOTICE.md).

- **[EVCam](https://github.com/suyunkai/EVCam)** by **suyunkai**, GPL-3.0 — **the code base**.
  This repository's first commit is EVCam's complete working tree; every change since is visible
  as a diff. Camera, encoding, storage, playback and keep-alive capability all come from EVCam
  and remain the copyright of its authors. Per GPL-3.0, this project is likewise released under
  GPL-3.0 with full source available.
- **[openavm-recorder](https://github.com/Dantenothing/openavm-recorder)** by **Dantenothing**,
  All Rights Reserved (not open source) — **published the technical facts about the Zeekr
  composite stream**. The key fact that Zeekr App Lab exposes a single composite stream, together
  with its dimensions and layout, was first documented by that project. **No source code from
  openavm-recorder has been copied** — only its published interface facts were used; the split
  and the rendering container here are independent implementations. Not affiliated with or
  endorsed by that project or its author.

---

## Safety notice

- Experimental, unofficial software, not certified for any vehicle functional-safety purpose;
- not a substitute for a factory dashcam, reversing camera, blind-spot monitor, or any legally
  required safety device;
- **do not operate this app while driving**, and do not use its display to judge distance,
  obstacles, or to change lanes / reverse;
- the feed has latency and may stutter, tear, distort or drop; contending with the head unit for
  resources may affect factory functions — stop using it and uninstall immediately if anything
  behaves oddly;
- recordings may contain faces, license plates, addresses, travel patterns and other personal
  data — follow local law on privacy, surveillance, recording and distribution, and don't publish
  identifiable footage without consent;
- provided AS IS, without warranty of any kind; the developer accepts no liability for any
  injury, property loss, or traffic incident arising from using or installing this software.

---

## Licence

**GNU General Public License v3.0**, inherited from EVCam. You may freely use, modify and
distribute this project, but any distribution must likewise be under GPL-3.0 with corresponding
source available, preserve copyright and licence notices, and add no further restrictions.
Full terms in [LICENSE](LICENSE); third-party component licensing in [NOTICE.md](NOTICE.md).
