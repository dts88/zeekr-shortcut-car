# Australian 7X: Comfort acceleration

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.

## 1. Result and requested default

**Verified on this car:** one explicit ECARX Comfort command returned `true`, and the
owner confirmed that the factory Acceleration setting changed to **Comfort**. This verifies
attended manual control. Available-state API readback and startup restoration remain unverified.

**Owner observation:** Acceleration defaults to **Standard** when the car starts. The desired
behaviour is Comfort once at the beginning of each drive, preserving later manual changes.
The owner was instructed to start this command test from factory Standard; the diagnostic
did not read or verify the pre-command mode.

## 2. ECARX access and exact command

The diagnostic used `Car.create(context)` followed by `getICarFunction()` through reflection.
The command matches Zeekr Shortcut's existing driving-mode path in
[`Telemetry.setDriveMode`](../app/src/main/java/com/kooo/evcam/telemetry/Telemetry.java) and
[`EcarxSource.write`](../app/src/main/java/com/kooo/evcam/telemetry/EcarxSource.java).

| Purpose | Runtime mapping / call |
|---|---|
| Setting property | `IDriveMode.DM_FUNC_DRIVE_MODE_SELECT = 0x22010100` |
| Tested selection | `IDriveMode.DRIVE_MODE_SELECTION_ECO = 0x22010101` |
| Factory name | **Comfort**, as mapped by [`Signal.DRIVE_MODE_COMFORT`](../app/src/main/java/com/kooo/evcam/telemetry/Signal.java) |
| Tested command | `setFunctionValue(0x22010100, 0x22010101)` |

`IDriveMode` is `com.ecarx.xui.adaptapi.car.vehicle.IDriveMode`. The exact two-argument
boolean setter uses the default scope. Runtime constants were checked before dispatch.
The ECARX name ECO must not be confused with the factory label Comfort; the SDK's
COMFORT selection is a different value, labelled Standard by upstream.

**Implementation inspection:** the installed adapter translates the tested selection to
raw value `1` for `DRIVE_ZEEKR_MODE_PROPULSION_SYS` (`0x22400F21`). Its ordinary ECARX
mode getter can write a default when the underlying value is zero, so the diagnostic did
not call that getter before or after this command.

## 3. Command and factory result

Times are Sydney time (UTC+11), 3 November 2026, converted from the report's epoch fields.
They identify the attempt and separate observation starts, not the exact setter-dispatch time.

| Event | Time | Result |
|---|---|---|
| Command record / attempt start | 14:14:06.907 | Runtime constants checked; property and gear/speed sensor support `active`. |
| Explicit command result | Not separately timestamped | One app setter call returned `true`; no app retry. Whole attempt elapsed 1080 ms. |
| Separate observation start | 14:14:08.345 | Alias payload `1`; status before and after both `1` (`UNAVAILABLE`). |

The guards recorded Park (`0x00200230`) and finite speed `0.0`; guard collection took
**14 ms**. The test required a two-second maximum guard age and a ten-second call-start
deadline. Cancellation stopped future calls; a dispatched command could not be recalled.
The later observation started after the command worker released its shared gate.

After uploading the report, the owner confirmed: "done, car changed to comfort".
The report's `factoryUiConfirmed=false` records the diagnostic's own observation limit;
the factory confirmation was supplied separately by the owner.

## 4. Readback and integration limits

The separate read used `setting_zeekr`, alias `ID_ZONED_DRIVE_MODE_SELECT` (`0x00004005`),
at its advertised area `0x80000000`. The typed getter returned integer payload `1`, consistent
with the inspected raw translation, but both surrounding status samples were **UNAVAILABLE**.
The status/value/status calls are non-atomic, and the typed getter does not expose the
payload's own status or timestamp. This is uncertain supporting evidence, **not valid
available-state readback** or an independently verified Standard/Comfort read mapping.
Earlier comparison batches did not capture acceleration values either.

The standard `CarPropertyManager` route did not advertise raw configuration `0x22400F21`,
so no raw property read was attempted. Permission filtering versus unsupported access
remains indeterminate.

No drive-session trigger, settings-initialization timing, sleep/wake, head-unit restart or
automatic preservation of later manual changes was tested. The owner-reported Standard
startup default and desired Comfort restoration require separate automation work. This
finding does not establish unattended restoration or a reliable available-state getter.

## 5. Evidence retained for follow-up

The contributor retains the report and diagnostic source. Full device logs, vehicle
binaries and decompiled source are not included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| AM4 accepted Comfort command and separate observation | `0.26-comfort-command-test` | `d7c312ea426fdcf2e2ed26ed2dcee320da2a1e7068b240f6a77a86345cebd0ec` |
