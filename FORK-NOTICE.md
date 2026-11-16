# Fork notice: who wrote what

This fork (`Fabbbrrr/zeekr-shortcut-car`) carries a contribution to Zeekr Shortcut. It is recorded here
so that reuse, forks and credit have something to point at. Licensing terms are in `LICENSING.md`.

## Sentry alerts

- **Design and implementation:** Fab (Fabbbrrr), <https://github.com/Fabbbrrr>
- **First published:** PR #211 against `dts88/zeekr-shortcut-car`, November 2026
- **Files:** `app/src/main/java/com/kooo/evcam/alarm/` and its tests; the clip support in
  `CodecVideoRecorder`, `MultiCameraManager`, `CameraKeeper`; the motion sampling hook in
  `EglSurfaceEncoder`; the Alerts settings wiring in `SettingsPreferenceFragment` and the Alerts keys in
  `AppConfig`; `docs/sentry-alerts-spec.md`; the Alerts sections of the README and CHANGELOG.

### What it does

With Sentry Mode on, the car sends a picture from the four surround cameras to a Telegram chat when it is
disturbed while parked, optionally a short clip around the moment, and answers `pic` and `status` from the
owner in the same chat. The idea is that the car talks to a bot the owner made, so no server of the
developer sits in the path.

### What is original here

The trigger set and its defaults, the armed-value gate that keeps the owner's own return out of the
alerts, the arm delay, quiet hours, the motion sampling read out of the encoder's own GL context, the
clip cut from the already-encoded sample ring, the command loop and its authorisation gate, the secrets
split that keeps the bot token out of backups and logs, and the diagnostic surface (probe row, JSON debug
screen, English feature log lines).

### Reuse

The project asks, in `NOTICE.md`, that facts first published in it are credited where they are reused; the
same courtesy is asked here for the items above. Code reuse follows the GPL-3.0 terms in `LICENSE`, and
keep the copyright lines in the files.
