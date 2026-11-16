# Licensing of the Alerts feature (fork notice)

This file belongs to **this fork** (`Fabbbrrr/zeekr-shortcut-car`). It does not change the licensing of
Zeekr Shortcut itself, which stays GPL-3.0 as described in `LICENSE` and `NOTICE.md`. It records the
licensing position of the contribution described below, so that the position is on file rather than
depending on a conversation.

## What this covers

The **Sentry alerts** feature, first published as PR #211 against `dts88/zeekr-shortcut-car`:

- the whole `app/src/main/java/com/kooo/evcam/alarm/` package and its tests;
- the clip support in `camera/CodecVideoRecorder.java`, `camera/MultiCameraManager.java`,
  `camera/CameraKeeper.java`;
- the Alerts settings wiring in `settings/SettingsPreferenceFragment.java`, and the Alerts keys and
  accessors in `AppConfig.java`;
- the motion sampling hook in `camera/EglSurfaceEncoder.java`;
- `docs/sentry-alerts-spec.md`, and the Alerts sections of `README.md` and `CHANGELOG.md`.

Those files carry the line `Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr`.

## Copyright

Copyright (C) 2026 Fab (Fabbbrrr). All rights reserved except as granted below.

## The grants

1. **GPL-3.0-only**, as part of Zeekr Shortcut. Anyone may use, study, share and modify this code under
   the terms of `LICENSE`. That grant is what makes the contribution usable by the project and by its
   users, and it is not withdrawn.
2. **A separate commercial licence, on request.** The copyright holder can also license this code under
   other terms. Ask through the GitHub account above, or open an issue on this fork.

## What that means in plain terms

- Using Zeekr Shortcut as GPL-3.0 software, including selling copies of it, is allowed by the GPL and is
  not restricted here.
- The GPL does not allow anyone to take these files and distribute them under a different licence, or
  inside a product that does not meet the GPL's obligations, without the copyright holder's permission.
  That permission is what grant 2 is for.
- Attribution is requested the way the rest of this project does it: keep the copyright line in the files,
  keep the credits in `FORK-NOTICE.md`, and mark modified versions as different from the original.

The GPL's section 7 allows additional terms for attribution and for marking modified versions. If the
maintainer wants the credit in this feature to be a licence condition rather than a request, that section
is the mechanism, and this file is the record of what the contributor asks for.

## Contributions to this feature

By submitting a change to the files listed above, a contributor agrees that the change may be published
under both grants above, so the feature can be offered under either one without having to track down
every author later. Say so in the pull request if that is not acceptable.

## Status

This notice applies to this fork. It says nothing about how the upstream project licenses itself, and it
does not add conditions to anyone else's code.
