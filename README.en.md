# Zeekr Shortcut (Car Version)

A surround-view dashcam application for Zeekr head units.

The app is named 极氪即刻（车机版） on the device.

[简体中文](README.md) · [Attribution](NOTICE.md) · [Changelog](CHANGELOG.md)

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by or endorsed by Zeekr,
> and not certified for any automotive functional-safety purpose. It is **not** a replacement
> for a factory dashcam, reversing camera, blind-spot monitor or any legally required safety
> device. Do not interact with it while driving. Use at your own risk.

## What it does

In the Zeekr App Lab environment a third-party application cannot open four separate
surround-view cameras. It receives **one already-composited video stream** with four
1280x1280 square views packed into a single frame. Previewed directly, that is a tall
thin strip with the wrong proportions.

This app does two things:

1. **Splits the composite stream correctly** into a 2x2 grid, preserving each view's
   original square aspect ratio.
2. **Attaches a mature dashcam feature set** — multi-stream capture, H.264/H.265 encoding,
   bitrate and frame-rate control, USB/external storage, segmented recording, automatic
   cleanup, playback, floating window and remote viewing.

Neither part was written from scratch. Both build on existing projects.

## Attribution

### EVCam — the code base (GPL-3.0)

<https://github.com/suyunkai/EVCam> by **suyunkai**, licensed GPL-3.0.

This repository is built **directly on EVCam's source**. The first commit is EVCam's
complete working tree at `0876b97`, so every subsequent change is visible as a diff.
All camera, encoding, storage, playback, keep-alive and remote-viewing capability comes
from EVCam and remains the copyright of its authors.

Per GPL-3.0, **this project is likewise released under GPL-3.0 with full source available**.

### AVM Recorder (openavm-recorder) — published technical facts (All Rights Reserved)

<https://github.com/Dantenothing/openavm-recorder> by **Dantenothing**.
`Copyright © 2026 Dantenothing. All rights reserved.` — **not an open-source licence.**

The key fact that Zeekr App Lab exposes a single composite stream, together with its exact
dimensions and layout, was first publicly documented by that project:

- vertical `1280x5140` — four 1280x1280 square views stacked vertically,
  `5140 = 4x1280 + 20`, the surplus being five 4px separator bands;
- horizontal `5120x1280` — four 1280x1280 views side by side;
- roughly 28 Mbps recommended for the full composite.

This project's aspect-ratio handling and 2x2 split are based on those published facts.
Without that documentation this app could not target Zeekr head units. **Sincere thanks.**

> [!IMPORTANT]
> **No source code from openavm-recorder has been copied.** That project reserves all
> rights and grants no permission to copy, modify or redistribute its code or APK.
> Only the *interface facts* (resolutions, layout, separator bands, ratios, bitrate) were
> used — factual descriptions of a hardware output interface, not protected expression.
> The geometry and GL renderer here are independent implementations.
>
> This project is **not affiliated with or endorsed by** openavm-recorder or its author,
> and does not use the AVM Recorder name or logo.

Full detail in [NOTICE.md](NOTICE.md) and in the app's built-in "关于与致谢" screen.

## How the composite stream is handled

```
Camera2 (single composite stream, e.g. 1280x5140)
        |  writes into
        v
producer SurfaceTexture (OES external texture)
        |  GL thread splits into 4 sampling windows
        v
2x2 grid -- rendered to the TextureView's display surface
```

- **No upstream camera-pipeline changes.** `CompositeTextureView` extends EVCam's
  `AutoFitTextureView` and overrides `getSurfaceTexture()` to hand back its own OES
  texture, so `SingleCamera` is untouched.
- **Cameras located by capability**, not by hard-coded index.
- **Never invents a resolution** — only sizes the HAL actually declares.
- **Aspect ratio first** — default FIT mode letterboxes rather than stretches.
- **Safe fallback** to an equal four-way split when the band layout does not match.

Recording stores the **raw composite stream**; playback re-applies the same geometry.

## Install

Download the latest `ZeekrShortcut-*.apk` from [Releases](../../releases), or grab the
build artifact from [Actions](../../actions). To build yourself (JDK 17+, Android SDK):

```bash
./gradlew assembleRelease
```

## Known limitations

- Designed against the Zeekr 7X composite format only; other layouts fall back to an
  equal split and may be offset.
- **Not yet verified on a real vehicle.** Automated tests cover the pure logic only.
  Test on a stationary vehicle first.
- The composite stream is ~6.55 MP; encoding load is high. Default 20 fps.
- Some EVCam features target Geely Galaxy vehicles (VHAL signals, blind-spot, turn-signal
  integration) and will likely not work on Zeekr.

## Licence

GNU General Public License v3.0, inherited from EVCam. See [LICENSE](LICENSE) and
[NOTICE.md](NOTICE.md).
