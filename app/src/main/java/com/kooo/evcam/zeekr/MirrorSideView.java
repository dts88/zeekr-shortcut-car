package com.kooo.evcam.zeekr;

/** Temporary presentation state; the mirror's saved camera and framing stay untouched. */
public final class MirrorSideView {
    public static final class Placement {
        public final int x;
        public final int y;
        public final boolean docked;
        public final int width;
        public final int height;

        Placement(int x, int y, boolean docked) {
            this(x, y, -1, -1, docked);
        }

        Placement(int x, int y, int width, int height, boolean docked) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.docked = docked;
        }
    }

    private int lane = SideViewDecision.NONE;
    private Placement original;

    public boolean active() {
        return original != null;
    }

    public int lane() {
        return lane;
    }

    public int displayLane(int normalLane) {
        return active() ? lane : normalLane;
    }

    public void show(int side, int x, int y, boolean docked) {
        show(side, x, y, -1, -1, docked);
    }

    public void show(int side, int x, int y, int width, int height, boolean docked) {
        if (side != LaneCycle.LEFT && side != LaneCycle.RIGHT) {
            throw new IllegalArgumentException("Expected a left or right camera");
        }
        if (!active()) {
            original = new Placement(x, y, width, height, docked);
        }
        lane = side;
    }

    public void updateNormalSize(int width, int height) {
        if (original != null) {
            original = new Placement(original.x, original.y, width, height, original.docked);
        }
    }

    public Placement clear() {
        Placement restore = original;
        original = null;
        lane = SideViewDecision.NONE;
        return restore;
    }
}
