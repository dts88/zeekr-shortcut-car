package com.kooo.evcam.profile;

/** Stable identities: exterior road feeds are never treated as surround crops or cabins. */
public final class RecordingSources {
    public static final String[] EXTRA_ROLES = {CameraProfile.ROLE_ROAD_FRONT, CameraProfile.ROLE_ROAD_REAR, CameraProfile.ROLE_MANUAL};
    public static final String[] ALL_ROLES = {CameraProfile.ROLE_COMPOSITE, CameraProfile.ROLE_CABIN_1,
            CameraProfile.ROLE_CABIN_2, CameraProfile.ROLE_ROAD_FRONT, CameraProfile.ROLE_ROAD_REAR, CameraProfile.ROLE_MANUAL};
    private RecordingSources() { }
    public static void ensure(Profile profile) {
        for (String role : EXTRA_ROLES) if (profile.camera(role) == null) {
            CameraProfile c = new CameraProfile(role); c.enabled = false;
            c.preview = StreamSpec.preview(StreamSpec.RESOLUTION_AUTO);
            c.record = StreamSpec.record(StreamSpec.RESOLUTION_AUTO, "20", "medium", "auto", 1);
            c.record.grid = false;
            c.lanes.add(LaneLayout.cell(-1, 0, 0, 1, 1));
            profile.cameras.add(c);
        }
    }
    public static String key(String role) {
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) return "front";
        if (CameraProfile.ROLE_CABIN_1.equals(role)) return "back";
        if (CameraProfile.ROLE_CABIN_2.equals(role)) return "left";
        for (String extra : EXTRA_ROLES) if (extra.equals(role)) return role;
        return null;
    }
    public static String role(String key) {
        if (key == null) return null;
        for (String role : ALL_ROLES) if (key.equals(key(role))) return role;
        return null;
    }
}
