package com.kooo.evcam.profile;

/** Recording selection and visible previews are separate; one camera is opened per source. */
public final class ProfileChannels {
    public final boolean surround, cabinFront, cabinRear;
    public final int count;
    public final java.util.Set<String> keys, previewKeys;
    private ProfileChannels(Profile profile, String mode, boolean overlayNeedsSurround) {
        java.util.Set<String> recording = new java.util.LinkedHashSet<>();
        for (CameraProfile c : profile.cameras) if (c.enabled) {
            String key = RecordingSources.key(c.role); if (key != null) recording.add(key);
        }
        java.util.Set<String> preview = new java.util.LinkedHashSet<>();
        if ("surround".equals(mode) || "surround_roads".equals(mode)) preview.add("front");
        if ("roads".equals(mode) || "roadfront".equals(mode) || "surround_roads".equals(mode)) preview.add("roadfront");
        if ("roads".equals(mode) || "roadrear".equals(mode) || "surround_roads".equals(mode)) preview.add("roadrear");
        if ("cabin".equals(mode)) preview.add("back");
        if (preview.isEmpty()) preview.addAll(recording);
        java.util.Set<String> all = new java.util.LinkedHashSet<>(recording); all.addAll(preview);
        if (overlayNeedsSurround) all.add("front");
        keys = java.util.Collections.unmodifiableSet(all);
        previewKeys = java.util.Collections.unmodifiableSet(preview); count = all.size();
        surround = preview.contains("front"); cabinFront = preview.contains("back"); cabinRear = preview.contains("left");
    }
    public static ProfileChannels of(Profile profile) { return new ProfileChannels(profile, "selected", false); }
    public static ProfileChannels of(Profile profile, String previewMode) { return new ProfileChannels(profile, previewMode, false); }
    public static ProfileChannels of(Profile profile, String previewMode, boolean overlayNeedsSurround) { return new ProfileChannels(profile, previewMode, overlayNeedsSurround); }
    public boolean cabinLayout() { return previewKeys.size() > (surround ? 1 : 0); }
    public int layoutSlots() { return cabinLayout() ? Math.max(3, previewKeys.size()) : 1; }
    public boolean needsTexture(String key) { return previewKeys.contains(key); }
    public String key() {
        String legacy = (keys.contains("front") ? "1" : "0") + (keys.contains("back") ? "1" : "0") + (keys.contains("left") ? "1" : "0");
        if (keys.size() <= 3 && !keys.contains("roadfront") && !keys.contains("roadrear") && !keys.contains("manual")
                && previewKeys.equals(keys)) return legacy;
        return legacy + "/sources=" + String.join(",", keys) + "/preview=" + String.join(",", previewKeys);
    }
}
