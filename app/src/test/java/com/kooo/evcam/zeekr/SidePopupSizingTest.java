package com.kooo.evcam.zeekr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SidePopupSizingTest {
    @Test
    public void sizeRespectsHalfScreenWidthAndTheSliderLimits() {
        assertEquals(550, SidePopupSizing.size(55, 2000, 1000));
        assertEquals(600, SidePopupSizing.size(90, 1200, 1000));
        assertEquals(250, SidePopupSizing.size(0, 2000, 1000));
        assertEquals(900, SidePopupSizing.size(500, 2000, 1000));
        assertEquals(100, SidePopupSizing.size(55, 200, 100));
    }

    @Test
    public void pinchStartsFromTheActualClampedWindowSize() {
        assertEquals(60, SidePopupSizing.resizedPercent(600, 1f, 1000));
        assertEquals(30, SidePopupSizing.resizedPercent(600, 0.5f, 1000));
        assertEquals(90, SidePopupSizing.resizedPercent(600, 2f, 1000));
        assertEquals(25, SidePopupSizing.resizedPercent(600, 0.01f, 1000));
    }

    @Test
    public void invalidGestureGeometryDoesNotProduceAnInvalidPreference() {
        assertEquals(55, SidePopupSizing.resizedPercent(550, Float.NaN, 1000));
        assertEquals(55, SidePopupSizing.resizedPercent(550, Float.POSITIVE_INFINITY, 1000));
        assertEquals(55, SidePopupSizing.resizedPercent(550, 2f, 0));
    }

    @Test
    public void growingWindowKeepsSavedPositionInsideTheScreen() {
        assertEquals(1600, SidePopupSizing.clampPosition(1600, 2000, 300));
        assertEquals(1500, SidePopupSizing.clampPosition(1600, 2000, 500));
        assertEquals(500, SidePopupSizing.clampPosition(900, 1000, 500));
    }

    @Test
    public void draggingCannotLoseTheWindowBeyondScreenEdges() {
        assertEquals(0, SidePopupSizing.clampPosition(-100, 2000, 550));
        assertEquals(1450, SidePopupSizing.clampPosition(2500, 2000, 550));
        assertEquals(0, SidePopupSizing.clampPosition(100, 300, 550));
    }
}
