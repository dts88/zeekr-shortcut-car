package com.kooo.evcam.settings;

import static com.kooo.evcam.settings.SettingSpec.entry;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link SettingSpec} 与 {@link SettingsRegistry} 的单元测试。
 *
 * <p>这两个类存在的目的就是消灭「界面显示一个值、程序按另一个值跑」。
 * 那种错误在车上极难发现 —— 界面看着一切正常 —— 所以判据必须在这里钉死。</p>
 */
public class SettingSpecTest {

    private static final SettingSpec SAMPLE = SettingSpec.of(
            "sample", "示例", "b",
            entry("a", "甲"),
            entry("b", "乙"),
            entry("c", "丙"));

    // ---------- 核心：找不到必须能和「第一项」区分开 ----------

    @Test
    public void unknownValueGivesMinusOneNotZero() {
        // 这正是原来那个 bug：没匹配上和匹配到第 0 项被当成同一件事，
        // 界面于是默默显示第一项，而配置里还是那个对不上的值
        assertEquals(-1, SAMPLE.indexOf("不存在的值"));
        assertEquals(-1, SAMPLE.indexOf(null));
        assertEquals(0, SAMPLE.indexOf("a"));
    }

    @Test
    public void sanitizeFallsBackToDefault() {
        assertEquals("a", SAMPLE.sanitize("a"));
        assertEquals("b", SAMPLE.sanitize("不存在的值"));
        assertEquals("b", SAMPLE.sanitize(null));
        assertEquals("b", SAMPLE.sanitize(""));
    }

    @Test
    public void validityIsExact() {
        assertTrue(SAMPLE.isValid("c"));
        assertFalse(SAMPLE.isValid("C"));      // 不做大小写宽容，存的就该是原值
        assertFalse(SAMPLE.isValid(" c"));
        assertFalse(SAMPLE.isValid(null));
    }

    // ---------- 取值与显示名必须一一对应 ----------

    @Test
    public void valuesAndDisplayNamesLineUp() {
        assertArrayEquals(new String[]{"a", "b", "c"}, SAMPLE.values());
        assertArrayEquals(new String[]{"甲", "乙", "丙"}, SAMPLE.displayNames());
        assertEquals(SAMPLE.values().length, SAMPLE.displayNames().length);
        for (int i = 0; i < SAMPLE.size(); i++) {
            assertEquals(SAMPLE.displayNames()[i],
                    SAMPLE.displayNameOf(SAMPLE.values()[i]));
        }
    }

    @Test
    public void valueAtClampsToDefault() {
        assertEquals("a", SAMPLE.valueAt(0));
        assertEquals("b", SAMPLE.valueAt(-1));
        assertEquals("b", SAMPLE.valueAt(99));
    }

    @Test
    public void roundTripsThroughIndex() {
        for (String value : SAMPLE.values()) {
            assertEquals(value, SAMPLE.valueAt(SAMPLE.indexOf(value)));
        }
    }

    // ---------- 定义写错时当场炸掉，别留到运行时 ----------

    @Test
    public void rejectsDefaultOutsideTheValueList() {
        try {
            SettingSpec.of("bad", "坏的", "z", entry("a", "甲"));
            fail("默认值不在取值列表里，应该直接抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("默认值"));
        }
    }

    @Test
    public void rejectsDuplicateValues() {
        try {
            SettingSpec.of("dup", "重复", "a", entry("a", "甲"), entry("a", "又是甲"));
            fail("取值重复应该直接抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("重复"));
        }
    }

    @Test
    public void rejectsEmptyValueList() {
        try {
            SettingSpec.of("empty", "空的", "a");
            fail("没有取值应该直接抛异常");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ---------- 注册表本身的自洽性 ----------

    @Test
    public void everyRegisteredSpecIsSelfConsistent() {
        for (SettingSpec spec : SettingsRegistry.ALL) {
            assertTrue(spec.key + ": 默认值必须合法", spec.isValid(spec.defaultValue));
            assertTrue(spec.key + ": 至少要有一个取值", spec.size() > 0);
            assertEquals(spec.key + ": 取值与显示名数量必须相同",
                    spec.values().length, spec.displayNames().length);
            assertNotNull(spec.key + ": 需要一个名字用于日志", spec.label);
        }
    }

    @Test
    public void registeredKeysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (SettingSpec spec : SettingsRegistry.ALL) {
            assertTrue("键名重复: " + spec.key, seen.add(spec.key));
        }
    }

    /**
     * 键名一旦改了，用户升级后这个设置就会「恢复默认」而没有任何提示。
     * 这里把当前键名钉住，改动必须是有意识的。
     */
    @Test
    public void storageKeysAreStable() {
        assertEquals("record_layout", SettingsRegistry.RECORD_LAYOUT.key);
        assertEquals("record_fps", SettingsRegistry.RECORD_FPS.key);
        assertEquals("preview_resolution", SettingsRegistry.PREVIEW_RESOLUTION.key);
        assertEquals("recording_mode", SettingsRegistry.RECORDING_MODE.key);
        assertEquals("bitrate_level", SettingsRegistry.BITRATE_LEVEL.key);
        assertEquals("car_model", SettingsRegistry.CAR_MODEL.key);
    }

    /** 默认值同样钉住：它决定全新安装后的行为。 */
    @Test
    public void defaultsAreWhatWeIntend() {
        assertEquals("grid2x2", SettingsRegistry.RECORD_LAYOUT.defaultValue);
        assertEquals("auto", SettingsRegistry.RECORD_FPS.defaultValue);
        assertEquals("1280x720", SettingsRegistry.PREVIEW_RESOLUTION.defaultValue);
        assertEquals("auto", SettingsRegistry.RECORDING_MODE.defaultValue);
        assertEquals("medium", SettingsRegistry.BITRATE_LEVEL.defaultValue);
        assertEquals("zeekr_7x", SettingsRegistry.CAR_MODEL.defaultValue);
    }

    /**
     * 全新安装时，界面选中的那一项必须就是配置会返回的值。
     *
     * <p>旧代码里这件事靠一个隐含约定维持：「默认值恰好是选项数组的第 0 项」——
     * 因为找不到时会落到 0。那个约定没有任何东西强制，把数组顺序调一下就破了。</p>
     *
     * <p>现在不需要那个约定了：读取时 sanitize 保证取值合法，界面用 indexOf 定位，
     * 默认值排在第几项都无所谓（预览分辨率默认第 2 项、码率默认第 2 项，都是对的）。
     * 这里验证的就是这一点。</p>
     */
    @Test
    public void freshInstallSelectsExactlyTheDefault() {
        for (SettingSpec spec : SettingsRegistry.ALL) {
            // 全新安装：存储里没有值，getter 返回默认值
            String asRead = spec.sanitize(null);
            assertEquals(spec.key, spec.defaultValue, asRead);

            // 界面据此定位，必须找得到，且回推得到同一个值
            int index = spec.indexOf(asRead);
            assertTrue(spec.key + ": 默认值必须能在选项里定位到", index >= 0);
            assertEquals(spec.key + ": 界面选中项与配置值必须一致",
                    asRead, spec.valueAt(index));
        }
    }
}
