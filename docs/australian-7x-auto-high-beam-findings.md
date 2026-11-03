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
vehicle-service route connected, but normal reads of both AHB candidates raised permission
`SecurityException`s while the diagnostic recorded stationary Drive. Inspection also found
an alias collision in the custom lighting route. The factory settings code has now been
received and inspected: its intelligent-high-beam switch uses an existing ECARX function,
but this has not established a usable control for the owner's stalk-selected AHB mode.

These results apply to this diagnostic in this car's App Lab. They do not establish that
every OEM or privileged integration route is unavailable.

## 2. Factory selection versus lamp output

**On-car observation:** seven owner-labelled states were compared after using the physical
high-beam stalk: neither high-beam mode selected, auto, manual, manual in Park, neither,
neither in Drive, auto. The owner clarified that from the untouched Drive state the first
stalk action selected manual high-beam and the second selected auto.

`BCM_FUNC_LIGHT_MAIN_BEAM = 0x21050200` read `0, 0, 1, 1, 0, 0, 0`.
It distinguished lamp output, not AHB selection. Across the 64 captured numeric readings,
the auto samples did not differ from the untouched Drive samples. Both comparisons
(HB01/HB02 and HB06/HB07) explicitly recorded Drive and zero speed, so Park does not
explain those unchanged readings. This concerns the queried signals, not a full vehicle-bus capture.

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
This later API check did not record gear, so it must not be described as a verified Drive
test. It collected access/configuration metadata, not an AHB value read or write.
The earlier deprecated factory had returned null, and the app-visible automotive feature
was false; changing connection method resolved that part of the experiment.

| Raw property | Meaning from the installed backend | Read permission reported | Configuration returned |
|---|---|---|---|
| `0x21408E18` | AHB-switch candidate | `android.car.permission.CAR_VENDOR_EXTENSION` | `null` |
| `0x11400E11` | Standard high-beam switch | `android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS` | `null` |
| `0x11400E01` | Standard high-beam lamp state | `android.car.permission.CAR_EXTERIOR_LIGHTS` | `null` |

All three permissions were ungranted in the local app context and reported protection level
`0x12` (signature/privileged). In v0.19, the diagnostic stopped before value reads because the
exact configurations were unavailable: **that check did not attempt a getter or observe a
getter `SecurityException`**.

**Implementation inspection:** the installed `CarService` builds its permission map from
runtime HAL properties and filters generic configurations by read permission. Combined with
the non-null permission metadata, this suggested the candidates exist but configuration
access is filtered. Those metadata results alone did not establish actual getter denial;
App Lab's local permission result does not establish the backend caller identity, and
transport errors can also produce null.

**On-car observation, diagnostic v0.32:** the normal
`CarPropertyManager.getProperty(int, int)` getter was then attempted once for each of the
two selection candidates, even though their configurations remained `null`. Both IDs encode
GLOBAL and INT32 in the installed API, providing the source basis for exact area `0`.

| Attempted property | Actual getter result |
|---|---|
| `0x21408E18`, area `0` | `SecurityException: requires android.car.permission.CAR_VENDOR_EXTENSION` |
| `0x11400E11`, area `0` | `SecurityException: requires android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS` |

The known ECARX gear and speed sensors were active. Before and after the getters, they
reported Drive (`0x00200220`) and finite speed `0.0`. The check took 1,163 ms and disconnected
normally. It returned no property values and made no lighting writes, retries, subscriptions,
permission requests or custom-lighting alias calls.

**Result:** the ordinary Android property route is blocked by permission checks for this app
in this car's App Lab, including in stationary Drive. Being in Drive did not make
these normal reads accessible. This does not establish that every AHB route is unavailable,
nor does it establish the candidates' AHB values or a working setter.

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
Drive may affect a valid control's current availability, but the inspected missing translation
and alias collision have no gear-dependent branch. Changing gear does not repair those
specific mappings. The public configuration filter also uses cached metadata and Android
permission checks rather than a gear condition.

The same v0.32 run attempted a targeted code-and-manifest export of factory settings package
`com.geely.pma.settings`, version `cs1e_eu-20260404` (code `20260404`), whose known service is
`com.zeekr.carcontrol.CBCService` with alias `carsettings`. It returned **metadata only**:
the APK exceeded the exporter's 4,096-entry inventory limit. Its reported size was
315,450,453 bytes; no partial code payload was included. A later v0.35 export succeeded,
as described below.

## 6. Factory settings implementation

**On-car observation, diagnostic v0.35:** all four parts of the targeted factory settings
export arrived and passed their checksums. The decoded payload contained 14 DEX files and
the manifest, totaling 67,142,152 selected bytes. All 15 selected file hashes matched.
This was a file export only, with no vehicle API calls or service binding. No further
stalk comparison was needed to inspect this code.

**Implementation inspection:** the factory HMI 5.0 intelligent-high-beam switch emits
`IntelligentFarOffLight(boolean)`. Its repository uses `lampActiveHighBeamControl`,
function `0x2B010100`, zone `0`, with `1` for enabled and `0` for disabled. The call passes
through `FunctionSignalProcessor` and `FunctionProxy` to the ordinary three-argument
ECARX `ICarFunction.setFunctionValue(function, zone, value)`. These values come from the
factory event handler, not a guessed numeric sweep.

This is the same function already present in the diagnostic reports. It reported active
support, empty supported-value/zone lists and an unknown integer value of `255`.
Active support alone is insufficient: the inspected adapter's status method can return
active for an unlisted function. Its dispatcher separately checks for a registered handler
before using the zone; without one, it returns `255` for a get and does not dispatch a set.
The inspected lamp handler table has no registration for `0x2B010100`.
Changing the setter overload or passing zone `0` does not bypass that registration check.
The client setter can return `true` after a void Binder call completes, so that boolean
alone would not prove that the car accepted a setting change.

The factory switch's displayed state also needs care. Its observer preserves the previous
displayed value when it receives `255`. The HMI 5.0 screen is selected using the runtime
`persist.sys.hmi.version` property; this value was not captured. The presence of this UI
branch in the shared factory package therefore does not establish that this car uses it,
or that its switch represents the owner's physical-stalk AHB selection. No explicit gear
gate was found in this particular switch/event chain.

The inspected `CBCService` forwards to a scheme dispatcher for window, door, climate,
seat, drive and sound actions. It does not expose an AHB action in that dispatcher.
A separate settings remote service has a generic `signal/get` route, but its integer
getter forwards to the same ECARX `getFunctionValue(function, zone)`. It does not supply
an independent vehicle backend or a factory-only cached AHB state. That wrapper returns
`253` if its manager is null and `0` if the getter throws, without preserving availability
or the exception in the value response. A returned `0` would therefore not prove OFF.

The factory manifest declares system shared identity and vendor-car permission, so the
factory app's own access cannot be assumed for an App Lab app. No remote service call was
made. Whether an App Lab caller can use that separate transport, or whether its caller
context would change the result, remains untested; no AHB benefit has been established.

**Result:** the export supplies concrete factory implementation evidence, but no verified
AHB read/write control. The existing Drive/stalk reports remain valid and need no repeat.
The source-derived `0/1` values alone do not justify a write through an unverified path.

## 7. Evidence retained for follow-up

The contributor retains the original reports and diagnostic source. The hashes below identify
the relevant reports. Full device logs and exported vehicle software are not
included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| Lighting managers/configurations | v0.19 | `c3ed5aaf282a21144ccbbb91fb0383b314dd1c4ae95423e258c6d52dc999c394` |
| Car-provider code export, received and hash-verified | v0.30 | `34bbc48487962a3dba97a3b11f03f0a0f45cfce96c9fea62f5a55115fd180956` |
| Normal direct getters in stationary Drive | v0.32 | `a1789a212be52720f640a7eaab0916c5839b2c6c858a51e46bc2ce9e3babf7cf` |
| Factory settings export metadata only, no code payload | v0.32 | `db784596aa3c96162d8ba9b0801b6f63f6aed909903cc53c260abcac91c1ef5a` |
| Factory settings code, part 1 of 4 | v0.35 | `9080db44027e5c23aaa3a6508c76a02bcd7fbe75dc875dfef2efeac090a29abb` |
| Factory settings code, part 2 of 4 | v0.35 | `b2ad29025d541ba41aa68722587c93314b15750029665826f8c408ec77035bd2` |
| Factory settings code, part 3 of 4 | v0.35 | `3c095f185d1e76fbc4c37480e857d52ca31f8d73604e2cbcb43b91ac608f2951` |
| Factory settings code, part 4 of 4 | v0.35 | `7a5e87e75360eed2698bd8f366e96f3cf91c43748409c5884f56834f9d958948` |

Both v0.32 reports share run ID `bc934eb8-f409-4d45-87cd-1acbe9e7e18b`.
The direct check started on 3 November 2026 at 05:31:36.850 UTC (16:31:36.850 AEDT);
the factory export report was created at 05:31:38.032 UTC (16:31:38.032 AEDT).

The v0.35 parts share run ID `3339c327-56c4-4da1-9447-dbcd3e0aba60`.
The verified code bundle SHA-256 is
`14728876488444a34024ed517289bcb8d42f9735389352ee0ca457e3f7580e23`.
Factory inspection used package `com.geely.pma.settings`, version `cs1e_eu-20260404`
(code `20260404`). It included the lighting UI/domain/repository, HMI signal processors,
`FunctionProxy`, `CBCService` and its dispatchers. Critical mappings were checked against
DEX instructions where decompiled methods were incomplete. This did not involve executing
the exported factory code.

Backend inspection used the car's exported `CarService` version 12 (version code 32).
Relevant implementations were `CarPropertyService`, `PropertyHalService`, `PropertyHalServiceBase`,
`HalServiceBase`, and `LightHalZeekrService`. Their method behaviour is summarized above;
vendor binaries and decompiled source are not part of this PR.

The ECARX enum/setter check also used the car's exported adapter implementation:
`CarFuncImplLamp`'s value map and setter, `BidirectionalSparseIntArray`, and
`VehiclePropertyMWIds`. Its exterior-light function maps to raw `0x22400F59`; that is separate
from the raw AHB-switch candidate `0x21408E18` above.
