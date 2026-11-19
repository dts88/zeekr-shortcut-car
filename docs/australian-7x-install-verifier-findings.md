# Australian 7X: APK install verifier (sideloading blocked after App Lab 1.0.0.26)

Research contributed by Calvin Bui, from a factory system image read off one
Australian-market ZEEKR 7X on **App Lab 1.0.0.25**, 19 November 2026. Prompted by
owners reporting that sideloading (for example the Brave browser) fails with an
installation error after updating App Lab to **1.0.0.26**.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and
**inference / unverified behaviour**. The APK analysed here is a factory system
app, not a Zeekr Shortcut release.

## Summary

The install block is **not** inside App Lab and is **not** a package-name block.
A system app, `com.ecarx.xsfinstallverifier`
(`/vendor/app/XSFInstallVerifier/XSFInstallVerifier.apk`, ~18 MB), registers as
Android's package verification agent and intercepts **every** APK install on the
head unit, whichever app triggers it. It allows an install only when the app's
**signature** is already trusted locally or is approved by an ECARX app-market
server. Everything else is rejected. This is effectively a signature allowlist,
not a blocklist of specific apps. It explains why official apps (YouTube, Waze,
Netflix) still install while a range of community and personal APKs all fail, and
why nothing about the official apps needs re-signing.

App Lab 1.0.0.26 most likely did not add this check. The verifier has shipped in
the image; what changed is almost certainly that enforcement was switched on (see
*Enforcement switch* below).

## 1. Where the block lives

**Implementation inspection.** `AndroidManifest.xml` of
`com.ecarx.xsfinstallverifier`:

- `<uses-permission android:name="android.permission.PACKAGE_VERIFICATION_AGENT"/>`
- receiver `com.ecarx.verifier.main.VerifyReceiver` for
  `android.intent.action.PACKAGE_NEEDS_VERIFICATION`

This is the standard Android mechanism by which the platform asks a privileged
app to approve or reject each pending install before it completes. Because it is a
system verifier, it applies to all installers on the device, not only App Lab.

`VerifyReceiver` forwards the pending install's package name and APK path to
`VerifyService` with `VERIFY_CMD = 10` (CMD_VERIFY).

## 2. The decision in VerifyService

**Implementation inspection** (`com.ecarx.verifier.main.VerifyService.onHandleIntent`):

1. **Environment bypass.** If the TSP environment is testing/development
   (`envType.isTestingEnv() || envType.isDevelopment()`), every install is
   allowed. False on a normal production car.
2. **Region gate.** For certain vehicle types (`CX1E-EU`, `EF1E-M-R`, any
   `CX1E-*` overseas, `CC1E-EU`) it allows installs **only** from installer
   package `com.faa.zeekr.appstore` and rejects everything else outright.
3. **Local allowlist (`cert_db`).** Otherwise it computes the APK hash (MD5, or
   SHA-256 behind a flag) and queries a local Room/SQLite database `cert_db`,
   table `sign`:
   ```
   SELECT * FROM sign WHERE pgkName = ?
   ```
   Columns: `pgkName`, `apkMd5`, `certContent`, `signType`, `signText`. A matching
   trusted signature record that validates → allow ("Legal App ALLOW").
4. **Online fallback.** If the package is not in `cert_db`, it validates the
   signature against an ECARX server, sending APK hash, version code, package
   name, VIN and vehicle type:
   - `POST /app-market/openserver/clientApi/v2/app/signature`
   - `POST /app-market/openserver/clientApi/v1/app/signature`

   The server decides. Any error or non-approval → reject (v2 retries 3× with a
   3s backoff, then rejects).
5. **Rejection UI.** On reject it shows a dialog
   (`verify_install_forbid_title` / `verify_install_forbid_tip`). This is the
   "installation error" owners see.

## 3. Why official apps work without re-signing

**Implementation inspection.** The gate checks each app's **own developer
signature** against trusted records (local `cert_db` or the online server). ECARX
does not re-sign YouTube, Waze or Netflix; it records that their genuine
signatures are approved, so those apps install unmodified and their sign-in
flows keep working. Because the record pairs `pgkName` with
`certContent`/`signText`, a package name alone is insufficient: a decoy APK
reusing an approved package name but signed with a different key would still fail.

## 4. Enforcement switch

**Implementation inspection.** CMD_INIT touches several toggles:

- `Settings.Global` key `verifier_default_response` — the default answer when the
  verifier does not respond. Set to `-1` (allow) on some `user`-build paths, but
  CMD_INIT writes `1` (reject) when `persist.ecarx.appstore.verify` is true.
- System property `persist.ecarx.appstore.verify` ("isForceVerify").
- User restriction `ensure_verify_apps`.

**Inference, not verified.** The most likely .25 → .26 change is force-verify
being enabled (or an updated verifier that enforces), turning a previously lax
check into a hard gate. The exact trigger is not directly observed from a .26
image.

## 5. What this means for sideloading

- There is no package-name block to route around. Renaming an APK or changing its
  package does not help; the gate is the signature plus `cert_db`/server approval.
- A third-party APK can pass only if its signature is in `cert_db` or approved by
  the ECARX server, neither of which is user-controllable without system access.
- On the affected cars, staying on App Lab 1.0.0.25 (where enforcement appears
  off or lax) is the practical answer. Updating to 1.0.0.26 is expected to block
  sideloading, including this project's APK.

## 6. Why the verifier cannot be removed from this device

**On-car observation.** Boot and integrity state read at runtime via `getprop`
on this car:

```
ro.boot.verifiedbootstate = green
ro.boot.veritymode        = enforcing
ro.boot.flash.locked      = 1
ro.secure                 = 1
ro.debuggable             = 1
```

`green` and `flash.locked = 1` mean the bootloader is locked and the boot chain
verified against the manufacturer keys; `enforcing` means dm-verity is active, so
the system and vendor partitions are hash-checked at runtime and any modification
fails the read (on a locked device, typically a boot failure). `ro.secure = 1`
means a secure adb/shell. `ro.debuggable = 1` is the `userdebug` flag: debuggable
but still locked, not unlocked.

**Inference.** Disabling or removing the verifier means altering `/system` or
`/vendor`, which enforcing dm-verity blocks, and flashing a patched image is
refused by the locked bootloader unless signed with the manufacturer keys. There
is no user-reachable bootloader unlock on these units. The `userdebug` build
would permit a root shell over adb in principle, but adb is not exposed on these
units, and even a root shell on a locked, verity-enforcing device cannot
persistently modify the system partitions. Removing the install verifier is
therefore not feasible without defeating verified boot, with real bricking risk
and no known public method for this unit.

Note the verifier sits **above** the App Lab container, at the Android system
level: it fires for any real install on the device, so installing outside the
sandbox would still be checked (and rejected the same way). The only bypass is a
privileged/root install that skips verification, which the points above put out
of reach.

## 7. Evidence and open items

- Source: the factory verifier extracted from a **1.0.0.25** image, read with a
  file manager from the real system partitions (`/vendor/app/...`). Behaviour is
  from this app's decompiled code plus the force-verify flag, not from a .26
  image.
- Which region bucket Australian `EF1E` 7X cars fall into (section 2, step 2)
  needs confirming against the vehicle type at runtime; an `EF1E-M-R` match would
  mean the strict "store-only" path.
- `cert_db` lives in the verifier's private data (`/data/data/...`), not readable
  from inside the App Lab container, so its contents were not dumped.
- Container identity (`com.puneetlj.box` / `com.vlite.sdk`) is already recorded in
  [the platform notes](zeekr-platform-notes.md); this verifier is a separate,
  system-wide component.
</content>
