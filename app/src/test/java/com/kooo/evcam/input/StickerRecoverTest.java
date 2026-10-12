package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Both saved stickers stay in the connect set. A sticker that is already up is skipped. */
public class StickerRecoverTest {

    @Test
    public void liveDropSchedulesOneConnect() {
        List<Integer> actions = StickerRecover.onDisconnect(false, true, 8, false);
        assertEquals(1, actions.size());
        assertEquals(StickerRecover.CONNECT, (int) actions.get(0));
    }

    @Test
    public void dropWaitsWhileScanning() {
        assertEquals(0, StickerRecover.onDisconnect(false, true, 8, true).size());
    }

    @Test
    public void bothDownStickersNeedALink() {
        List<String> saved = Arrays.asList("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02");
        assertEquals(saved, StickerRecover.missing(saved, Collections.emptySet()));
    }

    @Test
    public void onlyOneConnectWhileTheRadioIsBusy() {
        List<String> missing = Arrays.asList("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02");
        assertEquals("", StickerRecover.due(missing, true, ""));
        assertEquals("AA:AA:AA:AA:AA:01", StickerRecover.due(missing, false, ""));
    }

    @Test
    public void timeoutYieldsTheRadioToTheOtherSticker() {
        List<String> missing = Arrays.asList("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02");
        assertEquals("AA:AA:AA:AA:AA:02", StickerRecover.due(missing, false, "AA:AA:AA:AA:AA:01"));
    }

    @Test
    public void upStickerStaysAndTheOtherIsStillConnected() {
        List<String> saved = Arrays.asList("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02");
        assertEquals(Collections.singletonList("AA:AA:AA:AA:AA:02"),
                StickerRecover.missing(saved, new HashSet<>(Collections.singleton("AA:AA:AA:AA:AA:01"))));
    }

    @Test
    public void intentionalCloseAndPreLinkNoiseDoNotReconnect() {
        assertEquals(0, StickerRecover.onDisconnect(true, true, 8, false).size());
        assertEquals(0, StickerRecover.onDisconnect(false, false, 0, false).size());
    }

    @Test
    public void secondStickerDialsGentlyWhileTheFirstStaysUp() {
        assertFalse(StickerRecover.gentleConnect(0));
        assertTrue(StickerRecover.gentleConnect(1));
        assertTrue(StickerRecover.gentleConnect(2));
    }

    @Test
    public void connectIsAllowedWhenThisStickerIsDown() {
        assertEquals(StickerRecover.CONNECT, StickerRecover.allowConnect(false, false));
        assertEquals(StickerRecover.WAIT, StickerRecover.allowConnect(true, false));
        assertEquals(StickerRecover.WAIT, StickerRecover.allowConnect(false, true));
    }
}
