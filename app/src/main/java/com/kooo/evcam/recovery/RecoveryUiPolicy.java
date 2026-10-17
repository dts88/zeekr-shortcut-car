package com.kooo.evcam.recovery;

/** Automatic foreground recovery yields to known factory-camera use and reverse. */
final class RecoveryUiPolicy {
    private RecoveryUiPolicy() {}
    static boolean factoryCameraActive(String gear, Boolean surround, Boolean popup, Boolean parkAssist) {
        return "R".equals(gear) || Boolean.TRUE.equals(surround)
                || Boolean.TRUE.equals(popup) || Boolean.TRUE.equals(parkAssist);
    }
}
