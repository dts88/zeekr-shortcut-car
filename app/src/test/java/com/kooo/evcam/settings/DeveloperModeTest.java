package com.kooo.evcam.settings;

import static org.junit.Assert.assertFalse;
import org.junit.Test;

/** Fresh-process default; persistence and the real System switch are covered on Android. */
public class DeveloperModeTest {
    @Test public void startsLocked() { assertFalse(DeveloperMode.isUnlocked()); }
}
