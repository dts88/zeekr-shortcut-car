package com.kooo.evcam.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical source choices: absent = Automatic, empty = Unassigned, otherwise camera ID. */
public final class SourceMappings {
    public static final String MIGRATED = "source_mapping_migrated_v1";
    public static final String PREFIX = "source_id_";
    public static final String LEGACY_PREFIX = "zeekr_camera_override_";
    private SourceMappings() {}
    public static Map<String, String> migrate(Map<String, ?> saved, boolean developerEnabled) {
        Map<String, String> copies = new LinkedHashMap<>();
        if (!developerEnabled) return copies;
        for (String slot : new String[]{"front", "back", "left"}) {
            String target = PREFIX + RecordingSources.role(slot);
            Object legacy = saved.get(LEGACY_PREFIX + slot);
            if (!saved.containsKey(target) && legacy instanceof String && !((String)legacy).trim().isEmpty())
                copies.put(target, (String)legacy);
        }
        return copies;
    }
}
