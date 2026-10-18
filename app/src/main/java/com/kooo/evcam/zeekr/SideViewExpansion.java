package com.kooo.evcam.zeekr;

/** Temporary expand/restore geometry; never writes the normal window preferences. */
public final class SideViewExpansion {
    public static final class Geometry {
        public final int x, y, width, height;

        public Geometry(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private Geometry original;

    public boolean expanded() {
        return original != null;
    }

    public Geometry toggle(Geometry current, int screenWidth, int screenHeight, int margin) {
        if (expanded()) {
            return restore();
        }
        original = current;
        int maxWidth = Math.max(1, screenWidth - 2 * margin);
        int maxHeight = Math.max(1, screenHeight - 2 * margin);
        double scale = Math.min((double) maxWidth / Math.max(1, current.width),
                (double) maxHeight / Math.max(1, current.height));
        int width = Math.min(maxWidth, Math.max(1, (int) Math.round(current.width * scale)));
        int height = Math.min(maxHeight, Math.max(1, (int) Math.round(current.height * scale)));
        int x = Math.max(margin, Math.min(screenWidth - margin - width,
                current.x + (current.width - width) / 2));
        int y = Math.max(margin, Math.min(screenHeight - margin - height,
                current.y + (current.height - height) / 2));
        return new Geometry(x, y, width, height);
    }

    public Geometry restore() {
        Geometry result = original;
        original = null;
        return result;
    }
}
