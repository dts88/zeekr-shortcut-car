package com.kooo.evcam.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DimShadeTest {

    @Test
    public void defaultVeilIsNinetyPercentBlack() {
        assertEquals(0xE5000000, DimShade.argb(90, 0, 0));
    }

    @Test
    public void fullBrightnessWithNoWarmthIsWhite() {
        assertEquals(0xFFFFFFFF, DimShade.argb(100, 100, 0));
    }

    @Test
    public void fullWarmthPullsBlackTowardAmber() {
        assertEquals(0xFFFFB040, DimShade.argb(100, 0, 100));
    }

    @Test
    public void percentsOutsideZeroToHundredAreClamped() {
        assertEquals(DimShade.argb(100, 0, 0), DimShade.argb(150, -5, -1));
    }
}
