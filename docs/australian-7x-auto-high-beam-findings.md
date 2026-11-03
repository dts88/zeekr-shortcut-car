# Australian 7X: auto high-beam

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
31 October-3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate, small diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.
The car results below come from that diagnostic, not a Zeekr Shortcut release.

Related findings: [recirculation](australian-7x-recirculation-findings.md) and
[second-row AC](australian-7x-rear-ac-findings.md).

## 1. Result and scope

**No usable automatic-high-beam (AHB) selection control was verified, and no lighting write
was attempted.** Lamp output was distinguishable; AHB selection was not. The public Android
vehicle-service route connected, but did not provide the required configurations. Inspection
also found an alias collision in the custom lighting route.

These results apply to this diagnostic in this car's App Lab. They do not establish that
every OEM or privileged integration route is unavailable.

## 2. Factory selection versus lamp output

**On-car observation:** seven owner-labelled states were compared after using the physical
high-beam stalk: neither high-beam mode selected, auto, manual, manual in Park, neither,
neither in Drive, auto. The owner clarified that from the untouched Drive state the first
stalk action selected manual high-beam and the second selected auto.

`BCM_FUNC_LIGHT_MAIN_BEAM = 0x21050200` read `0, 0, 1, 1, 0, 0, 0`.
It distinguished lamp output, not AHB selection. Across the 64 captured numeric readings,
the auto samples did not differ from the untouched Drive samples. This is a finding about
the queried API signals, not a full vehicle-bus capture.

Exterior-light mode `0x20040E00` remained `LAMP_EXTERIOR_LIGHT_CONTROL_AUTOMATIC = 0x20040E03`.
The generic SDK declares `LAMP_EXTERIOR_LIGHT_CONTROL_AHBC = 0x20040E04`, but the installed
`CarFuncImplLamp` implementation maps only Off, Position, Low Beam and Auto for this function.
AHBC is absent from that map. The setter translates missing entries to `-1` and skips the
write, so this AHBC request would be ignored by the inspected backend. It does not translate
to raw value `4`. No lighting write was attempted. Other AHB-labelled candidates returned
unknown values.

This differs from the implemented Comfort drive-mode command in Zeekr Shortcut. Its success
does not establish an AHB command, and an SDK enum alone does not establish a setter path.
The public upstream code at `e3e628e` reads high-beam output and flash-to-pass; no verified
AHB-selection command was found there. This does not rule out another OEM or privileged route.

## 3. Android vehicle-service route

**On-car observation, diagnostic v0.19:** the public
`Car.createCar(Context, Handler, long, CarServiceLifecycleListener)` factory with timeout `0`
connected successfully and obtained the `property` and `light_zeekr` managers. The check
completed and disconnected normally. This is separate from the ECARX connection used for the climate controls.
The earlier deprecated factory had returned null, and the app-visible automotive feature
was false; changing connection method resolved that part of the experiment.

| Raw property | Meaning from the installed backend | Read permission reported | Configuration returned |
|---|---|---|---|
| `0x21408E18` | AHB-switch candidate | `android.car.permission.CAR_VENDOR_EXTENSION` | `null` |
| `0x11400E11` | Standard high-beam switch | `android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS` | `null` |
| `0x11400E01` | Standard high-beam lamp state | `android.car.permission.CAR_EXTERIOR_LIGHTS` | `null` |

All three permissions were ungranted in the local app context and reported protection level
`0x12` (signature/privileged). The diagnostic stopped before value reads because the exact
configurations were unavailable: **no getter denial or getter `SecurityException` was observed**.

**Implementation inspection:** the installed `CarService` builds its permission map from
runtime HAL properties and filters generic configurations by read permission. Combined with
the non-null permission metadata, this strongly suggests the candidates exist but configuration
access is filtered. **Inference:** restricted access is the likely obstacle; App Lab's local
permission result does not establish the backend caller identity, and transport errors can
also produce null. It is not proof that every integration route is unavailable.

## 4. Custom lighting alias collision

**Implementation inspection:** `LightHalZeekrService` maps raw AHB candidate `0x21408E18`
to legacy ID **16402 (`0x4012`)**, then maps goodbye-light request `0x21408E2C` to the same ID.
The later forward-map entry wins: outgoing calls to 16402 select **goodbye lighting**.
Passing the raw AHB ID to that manager is also not a route: its lookup rejects an unmapped
manager ID. These are source-derived results; no such write was attempted.

**On-car observation:** the custom manager returned 56 configurations, 54 unique IDs,
including two entries for 16402 with WRITE and READ_WRITE access. The public configuration
objects had already replaced the raw IDs with the shared alias, so neither access flag could
be assigned to AHB. ID16408 was a read-only lamp-output configuration, not an AHB preference.
No distinct usable AHB-selection route was identified in the returned configurations, and
no custom lighting values or writes were attempted.

Further progress needs an accessible control path or an appropriate OEM/App Lab integration
mechanism. Repeating the same stalk sequence would not resolve this access/mapping problem.

## 5. Separate Zeekr SDK route

**On-car observation, diagnostic v0.30:** a bounded PackageManager check identified
`com.zeekr.vehicle.service`, version **1.1.3.11** (code **233**), as the installed provider
for service alias `car`. Its `CarService` matched the exact support-service action,
car category and provider XML alias used by CoreService. The diagnostic exported normally
readable code and the manifest; it did not initialize the SDK, bind a service or call a light control.

**Implementation inspection:** both EE2 and EE3 lighting mappings translate
`Car.Light.ExteriorLight` with SDK value `41242` (`ControlAhbc`) to ECARX property
`0x20040E00` with value `0x20040E04`. The generic lighting path then calls
`ICarFunction.setFunctionValue`. The alternative platform selection changes the Adas/Navi
modules, but lighting still uses the ECARX adapter route. This is another wrapper around
the incomplete AHBC mapping described in section 2, not a newly established raw AHB control.

The provider also maps `Car.Light.ActiveHighbeam` to `0x2B010100`,
`Car.Light.HighbeamActive` to `0x20040200` response-speed values,
`Car.Light.AuxHighbeam` to `0x20040400`, and `MainBeam` to lamp-output `0x21050200`.
These names do not establish the owner's stalk-selected AHB preference, and this inspection
did not reveal a new AHB backend mapping. No additional stalk comparison or guessed write
was performed.

The broker's caller authorization is a separate, still untested question. Exported component
metadata and readable code do not establish live SDK access. These findings close this
specific mapped route; they do not prove that every OEM integration is unavailable.

## 6. Evidence retained for follow-up

The contributor retains the original reports and diagnostic source. This hash identifies the
cumulative lighting-manager report. Full device logs and exported vehicle software are not
included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| Lighting managers/configurations | v0.19 | `c3ed5aaf282a21144ccbbb91fb0383b314dd1c4ae95423e258c6d52dc999c394` |
| Car-provider code export, received and hash-verified | v0.30 | `34bbc48487962a3dba97a3b11f03f0a0f45cfce96c9fea62f5a55115fd180956` |

Backend inspection used the car's exported `CarService` version 12 (version code 32).
Relevant implementations were `CarPropertyService`, `PropertyHalService`, `PropertyHalServiceBase`,
`HalServiceBase`, and `LightHalZeekrService`. Their method behaviour is summarized above;
vendor binaries and decompiled source are not part of this PR.

The ECARX enum/setter check also used the car's exported adapter implementation:
`CarFuncImplLamp`'s value map and setter, `BidirectionalSparseIntArray`, and
`VehiclePropertyMWIds`. Its exterior-light function maps to raw `0x22400F59`; that is separate
from the raw AHB-switch candidate `0x21408E18` above.
