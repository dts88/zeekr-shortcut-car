package com.kooo.evcam.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Every saved sticker that is down should be connected. One that is already up stays open.
 */
public final class StickerRecover {

    public static final int WAIT = 0;
    public static final int CONNECT = 1;

    /** After a failed attempt, wait, then connect again so a press can finish it. */
    public static final long AGAIN_MS = 500L;
    /** After an unexpected drop, connect while the sticker is still advertising. */
    public static final long RECONNECT_MS = 400L;
    /** Gap is unused while a connect is in flight. A second connectGatt then never calls back. */
    public static final long GAP_MS = 400L;

    private StickerRecover() {
    }

    /**
     * @param scanHold sticker screen owns the radio
     * @param busy     this sticker already has a gatt open, pending, or up
     */
    /**
     * A second direct connect drops a sticker this radio already has up.
     * Dial the next one gently when another link is up.
     */
    public static boolean gentleConnect(int othersUp) {
        return othersUp > 0;
    }

    public static int allowConnect(boolean scanHold, boolean busy) {
        if (scanHold || busy) {
            return WAIT;
        }
        return CONNECT;
    }

    /** Saved stickers that are not already up or connecting. */
    public static List<String> missing(List<String> saved, Set<String> skip) {
        List<String> out = new ArrayList<>();
        if (saved == null) {
            return out;
        }
        for (String address : saved) {
            if (skip == null || !skip.contains(address)) {
                out.add(address);
            }
        }
        return out;
    }

    /**
     * The one sticker to dial now. Empty while a connectGatt is already in flight.
     * {@code yield} is the address that just timed out, so the other sticker gets the radio.
     */
    public static String due(List<String> missing, boolean inFlight, String yield) {
        if (inFlight || missing == null || missing.isEmpty()) {
            return "";
        }
        if (yield != null && !yield.isEmpty()) {
            for (String address : missing) {
                if (!address.equals(yield)) {
                    return address;
                }
            }
        }
        return missing.get(0);
    }

    /**
     * Follow-ups for one disconnect callback. Empty, or a single {@link #CONNECT}.
     * Another sticker's link does not block this one.
     *
     * @param intentional close() asked for this disconnect
     * @param wasUp       STATE_CONNECTED had already arrived
     * @param status      GATT status on the disconnect
     */
    public static List<Integer> onDisconnect(boolean intentional, boolean wasUp, int status,
                                              boolean scanHold) {
        if (intentional || (!wasUp && status == 0)) {
            return Collections.emptyList();
        }
        if (allowConnect(scanHold, false) != CONNECT) {
            return Collections.emptyList();
        }
        List<Integer> actions = new ArrayList<>(1);
        actions.add(CONNECT);
        return actions;
    }
}
