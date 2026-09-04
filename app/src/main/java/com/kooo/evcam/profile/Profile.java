package com.kooo.evcam.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份配置：用哪几路相机、每条流什么参数、画面怎么摆。
 *
 * <h3>和 {@code StreamLayoutTable} 的分工</h3>
 *
 * <p>那张表装的是<b>设备事实</b>——某相机 + 某分辨率下像素怎么排、拆出来哪一格是
 * 前后左右、录制流最终怎么摆。用户改不了，因为那不是一个选择。</p>
 *
 * <p>这里装的是<b>用户选择</b>——在设备允许的范围里，你要怎么用它。</p>
 *
 * <h3>为什么摊平成键值对而不是 JSON</h3>
 *
 * <p>配置最终落在 SharedPreferences 里，本来就是键值对；摊平之后不需要任何
 * 序列化库，诊断报告里能直接逐行看，单元测试也不必和 Android 的 JSON 桩实现打交道。
 * 键长这样：{@code cam.composite.record.resolution}。</p>
 */
public final class Profile {

    /** 内置预设：极氪 7X，只录环视合成流那一路。 */
    public static final String PRESET_COMPOSITE = "zeekr_7x";

    /** 内置预设：环视 + 两路座舱。 */
    public static final String PRESET_COMPOSITE_MULTI = "zeekr_7x_multi";

    /** 配置 id，也是存储时的命名空间。 */
    public String id = PRESET_COMPOSITE;

    /** 显示名。用户另存时自己起。 */
    public String name = "";

    public final List<CameraProfile> cameras = new ArrayList<>();

    public CameraProfile camera(String role) {
        for (CameraProfile camera : cameras) {
            if (camera.role.equals(role)) {
                return camera;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 摊平

    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        StringBuilder roles = new StringBuilder();
        for (CameraProfile camera : cameras) {
            if (roles.length() > 0) {
                roles.append(',');
            }
            roles.append(camera.role);
        }
        out.put("roles", roles.toString());

        for (CameraProfile camera : cameras) {
            String base = "cam." + camera.role + ".";
            out.put(base + "enabled", String.valueOf(camera.enabled));
            putAll(out, base + "preview.", camera.preview.toMap());
            putAll(out, base + "record.", camera.record.toMap());
            putAll(out, base + "photo.", camera.photo.toMap());
            out.put(base + "lanes", String.valueOf(camera.lanes.size()));
            for (int i = 0; i < camera.lanes.size(); i++) {
                putAll(out, base + "lane." + i + ".", camera.lanes.get(i).toMap());
            }
        }
        return out;
    }

    public static Profile fromMap(Map<String, String> values) {
        Profile profile = new Profile();
        if (values == null || values.isEmpty()) {
            return profile;
        }
        profile.id = value(values, "id", PRESET_COMPOSITE);
        profile.name = value(values, "name", "");

        String roles = value(values, "roles", "");
        if (roles.isEmpty()) {
            return profile;
        }
        for (String role : roles.split(",")) {
            if (role.isEmpty()) {
                continue;
            }
            String base = "cam." + role + ".";
            CameraProfile camera = new CameraProfile(role);
            // 缺这个键时按「启用」处理：配置里出现过的相机，默认就是要用的
            camera.enabled = !"false".equals(values.get(base + "enabled"));
            camera.preview = StreamSpec.fromMap(slice(values, base + "preview."));
            camera.record = StreamSpec.fromMap(slice(values, base + "record."));
            camera.photo = StreamSpec.fromMap(slice(values, base + "photo."));

            int lanes = 0;
            try {
                lanes = Integer.parseInt(value(values, base + "lanes", "0"));
            } catch (NumberFormatException e) {
                lanes = 0;
            }
            for (int i = 0; i < lanes; i++) {
                camera.lanes.add(LaneLayout.fromMap(slice(values, base + "lane." + i + ".")));
            }
            profile.cameras.add(camera);
        }
        return profile;
    }

    private static void putAll(Map<String, String> out, String prefix, Map<String, String> src) {
        for (Map.Entry<String, String> entry : src.entrySet()) {
            out.put(prefix + entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, String> slice(Map<String, String> values, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                out.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return out;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    /** 一份可读的全文，给日志和诊断报告用。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("配置 ").append(id);
        if (!name.isEmpty()) {
            sb.append("（").append(name).append("）");
        }
        sb.append('\n');
        for (CameraProfile camera : cameras) {
            sb.append("  ").append(camera);
        }
        return sb.toString();
    }
}
