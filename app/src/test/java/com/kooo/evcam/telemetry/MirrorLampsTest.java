package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MirrorLamps}：值怎么算亮、一次换一盏、按值相等。 */
public class MirrorLampsTest {

    @Test
    public void anythingButZeroIsOn() {
        assertEquals(Boolean.FALSE, MirrorLamps.on(0));
        assertEquals(Boolean.TRUE, MirrorLamps.on(1));
        assertEquals("开门预警见过 3：算亮", Boolean.TRUE, MirrorLamps.on(3));
        assertNull("读不到", MirrorLamps.on(null));
        assertNull("不是数", MirrorLamps.on("1"));
    }

    @Test
    public void withChangesOneLampAndKeepsTheSameObjectWhenNothingChanges() {
        MirrorLamps a = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, true);
        assertEquals(Boolean.TRUE, a.blindSpotRight);
        assertNull(a.blindSpotLeft);
        assertTrue(a.anyOn());
        assertSame(a, a.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, true));
        MirrorLamps b = a.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, false);
        assertFalse(b.anyOn());
        assertEquals(Boolean.FALSE, b.get(MirrorLamps.Lamp.BLIND_SPOT_RIGHT));
    }

    @Test
    public void equalByValue() {
        MirrorLamps a = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.DOOR_WARNING_LEFT, true);
        MirrorLamps b = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.DOOR_WARNING_LEFT, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(MirrorLamps.UNKNOWN));
        assertEquals("blindSpot L? R?, doorWarning L1 R?", a.toString());
    }
}
