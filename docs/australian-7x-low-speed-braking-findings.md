# Australian 7X: Low-speed Emergency Braking

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.
Related finding: [Hill Descent Control](australian-7x-hill-descent-findings.md).

## 1. Result and scope

**Verified on this car:** the factory Low-speed Emergency Braking switch was mapped by
ON → OFF → ON changes. A subsequent explicit API command changed it from ON to OFF,
with matching readback and the owner's factory-screen confirmation.

The owner reported that the setting keeps re-enabling itself. The precise reset trigger,
automatic restoration and startup/session behaviour were not tested here. This result
verifies the setting command; physical emergency-braking performance was not tested.

## 2. ECARX access and exact mapping

The diagnostic used `Car.create(context)` → `getICarFunction()` through reflection,
without bundling ECARX classes. See the [connection code](australian-7x-recirculation-findings.md#2-ecarx-access-and-exact-mapping).

| Purpose | Runtime mapping / call |
|---|---|
| Setting property | `IADAS.SETTING_FUNC_PEB_MODE = 0x20060200` |
| ON | `IVehicle.PEB_MODE_PEB = 0x20060201` |
| OFF | `IVehicle.PEB_MODE_OFF = 0` |
| Read | `getFunctionValue(0x20060200)` |
| Tested command | `setFunctionValue(0x20060200, 0)` |

`IADAS` and `IVehicle` are in `com.ecarx.xui.adaptapi.car.vehicle`.
These are the default getter and two-argument boolean setter; no explicit zone is supplied.
The runtime constants were checked before dispatch. Supported values and zones did not
provide a usable enumeration; no additional scope is inferred.

**Implementation inspection:** `CarFuncImplADAS` maps this to parking emergency braking,
backend `0x2240E009`. Its getter maps raw `0` to OFF and raw `1..7` to the PEB enum;
the tested OFF command maps back to raw `0` at the SDK's default `Integer.MIN_VALUE` scope.
Ordinary AEB is a separate property, `SETTING_FUNC_AUTONOMOUS_EMERGENCY_BRAKING = 0x20070E00`.
No ordinary-AEB command was sent.

## 3. Factory comparison and command result

Times are Sydney time (UTC+11), 3 November 2026.

| Sample / event | Time | Result |
|---|---|---|
| LB1: factory ON | 12:52:45.801 | `0x20060201` |
| LB2: factory OFF | 12:53:03.297 | `0` |
| LB3: factory ON again | 12:53:19.301 | `0x20060201` |
| Explicit OFF command accepted | 13:07:59.216 | Setter returned `true`. |
| First readback | 13:07:59.221 | Still ON. |
| Second readback | 13:07:59.473 | OFF; `READBACK_MATCHED`. |
| LB4: later snapshot | 13:08:13.773 | Still OFF. |

All 74 numeric readings were present in each factory-comparison snapshot. Each transition
changed only the PEB reading; all snapshots recorded Park and zero speed. PEB support was
`active` throughout. The command test also checked active support, Park and zero speed.
There was **one setter call**, with delayed readback and no write retry.

The owner subsequently confirmed that the factory switch changed to OFF after the command.
The diagnostic itself cannot inspect that screen, so its log retains `factoryUiConfirmed=false`;
the owner's confirmation is separate evidence. The later HD4 snapshot also retained PEB OFF.

## 4. Test constraints and remaining work

The attended test validated runtime constants and the known ON starting value. Its dispatch
conditions had to be collected within two seconds, with a ten-second overall deadline for
starting calls. It sent at most one command, used bounded readbacks, and did not retry an
uncertain acknowledgement. Cancel, leaving the app or screen-off stopped subsequent calls;
a command already dispatched could not be recalled.

No automatic trigger, wake lock or new vehicle subscription was added. This does not
establish persistence after a new drive, sleep/wake, head-unit restart, a restoration policy,
or compatibility with other firmware/markets.

### Driveway use case and geofenced option

**Owner-reported motivation:** I and other owners experience recurring unwanted LSEB
interventions in our own driveways. An optional location rule could disable LSEB within a
user-defined geofence, such as home, while retaining normal LSEB behaviour outside that area.
This is a proposal for a future feature.

The owner reports that the Zeekr API can identify when the car is at home; access to an
equivalent signal from an App Lab app remains unverified. The existing
[`LocationSource`](../app/src/main/java/com/kooo/evcam/telemetry/LocationSource.java) uses Android
GPS/network location, but its availability inside App Lab is also untested. If it works,
a geofence could use user-configured home coordinates without a native home-presence flag.

Location access, geofence entry/exit handling and the API command to re-enable LSEB on exit
have not been tested. The existing on-car result verifies the explicit OFF command only.

## 5. Evidence retained for follow-up

The contributor retains the original cumulative reports and diagnostic source. Comparisons
use labelled samples, not whole-file differences. Full logs and vehicle software are not
included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| Six factory comparisons, including LB1/LB2/LB3 | v0.22 | `8f6dc39f78488f4087b153de58c485bf1f37bf043ea3c50a9f6b0d2e9d2972ca` |
| OFF command and LB4 snapshot | v0.23 | `63d72ebc95ca2222d92eeb6031bfde780843c0ad144a34858e644defe89ba294` |
| Later HD4 snapshot retaining PEB OFF | v0.23 | `7e3a7086a6c583dce22900796084f2a8a70814b5e16c6a2c08d238505f5ee1d6` |

## 6. Zeekr Shortcut notes (2026-11-06)

- Remembering this setting between drives ([#138](https://github.com/dts88/zeekr-shortcut-car/issues/138)) will be
  built into Zeekr Shortcut's upcoming geofence feature (project owner, 2026-11-06).
- The home geofence proposal in section 4 is recorded here. Whether Zeekr Shortcut ships it will be decided when
  the geofence feature is built (project owner, 2026-11-06). Zeekr Shortcut hasn't verified location inside App Lab
  either.
- What Zeekr Shortcut has already verified about deep sleep, power-down and restarts on its own 7X is summarised in
  [Comfort acceleration, section 8](australian-7x-acceleration-findings.md#8-zeekr-shortcut-notes-2026-11-06).
