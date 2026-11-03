# Australian 7X: Hill Descent Control

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.
Related finding: [Low-speed Emergency Braking](australian-7x-low-speed-braking-findings.md).

## 1. Result and availability

**Verified on this car:** the factory Hill Descent Control (HDC) switch was mapped by
OFF → ON → OFF changes. An explicit API command then changed it from OFF to ON, with
matching readback and the owner's factory-screen confirmation.

**Owner observation:** HDC was unavailable while the car was in ACC. It became available
after selecting Drive and returning to Park. The successful command itself was sent in
Park at zero speed. The reports do not capture the intervening Drive selection or establish
that physical movement is required; that sequence is supplied by the owner.

The first command attempt was blocked by the diagnostic's `notactive` support guard,
without sending a write. A later explicit attempt found `active` support and succeeded.
This was not an automatic retry or a bypass of the car's availability status.

## 2. ECARX access and exact mapping

The diagnostic used `Car.create(context)` → `getICarFunction()` through reflection.
See the [connection code](australian-7x-recirculation-findings.md#2-ecarx-access-and-exact-mapping).

| Purpose | Runtime mapping / call |
|---|---|
| Setting property | `IADAS.SETTING_FUNC_HDC_SWITCH = 0x20060500` |
| Factory OFF / ON | `0` / `1`, also checked against `ICarFunction.COMMON_VALUE_OFF/ON` |
| Read | `getFunctionValue(0x20060500)` |
| Tested command | `setFunctionValue(0x20060500, 1)` |

`IADAS` is `com.ecarx.xui.adaptapi.car.vehicle.IADAS`. The getter and two-argument boolean
setter use the default scope. Runtime constants were checked; empty supported-value/zone
lists were not treated as permission to invent a zone.

**Implementation inspection:** `CarFuncImplADAS` maps the switch to backend `0x22400F96`,
with integer values passed through. This switch is separate from active downhill-braking
behaviour and from the backend HDC-status dependency described below.

## 3. Factory comparison and command result

Times are Sydney time (UTC+11), 3 November 2026.

| Sample / event | Time | Value / result | Support |
|---|---|---|---|
| HD1: factory OFF | 12:53:54.001 | `0` | `notactive` |
| HD2: factory ON | 12:55:10.950 | `1` | `active` |
| HD3: factory OFF again | 12:55:23.165 | `0` | `active` |
| First explicit attempt | 13:08:27.582 | Blocked; no setter call. | `notactive` |
| Later explicit ON command | 13:09:01.711 | Setter returned `true`. | `active` |
| First readback | 13:09:01.715 | Still `0`. | |
| Second readback | 13:09:01.968 | `1`; `READBACK_MATCHED`. | |
| HD4: later snapshot | 13:09:08.684 | Still `1`. | `active` |

Each factory transition changed only HDC among the 74 captured numeric readings. All
three comparison snapshots and the successful command's guards recorded Park and zero speed.
There was **one HDC setter call** across the two explicit attempts, with no write retry.
The owner subsequently confirmed that the factory switch changed to ON. The diagnostic
cannot inspect that screen, so its `factoryUiConfirmed=false` log field remains separate
from the owner's later confirmation. Physical downhill-braking behaviour was not tested.

## 4. Availability rule and integration limit

**Implementation inspection:** the installed adapter's main, 23n5 and 23n7 HDC rules agree:

- Available when `CARCFG_53 == 2`.
- Active when car mode is `0`, `3` or `5`, usage mode is `0x0D`, and
  `ZEEKR_FUNC_SWT_STS_FOR_HILL_DWN_CTRL` (`0x2240F1CF`) is `1`.
- The client maps both backend `notactive` and error statuses to `FunctionStatus.notactive`.

Thus `notactive` is not the OFF value. A known OFF reading alone does not justify a write.
The setter checks a separate restriction map, so the diagnostic retained its own fresh
`active` guard. The owner-reported ACC/Drive/Park behaviour is consistent with contextual
availability; these reports do not identify which dependency changed.

The test also required exact runtime constants, the known OFF starting value, Park, zero
speed, a two-second maximum guard-collection time and a ten-second call-start deadline.
It used bounded readbacks and cancelled future calls on exit or screen-off. Startup
restoration would need to wait for eligible context, with bounded handling and preservation
of later manual changes. No session trigger, automatic restoration, sleep/wake or
head-unit-restart behaviour was tested here.

## 5. Evidence retained for follow-up

The contributor retains the cumulative reports and diagnostic source. Full device logs,
vehicle binaries and decompiled source are not included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| Six factory comparisons, including HD1/HD2/HD3 | v0.22 | `8f6dc39f78488f4087b153de58c485bf1f37bf043ea3c50a9f6b0d2e9d2972ca` |
| Blocked attempt, accepted ON command and HD4 snapshot | v0.23 | `7e3a7086a6c583dce22900796084f2a8a70814b5e16c6a2c08d238505f5ee1d6` |
