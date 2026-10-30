package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置编辑开着时存下的那份被别处改了（离车时「前座舱只用一次」关掉前座舱的开关）：存的时候在存下的上面只套这一页改了的那几项
 * （{@link ProfileEditorFragment#rebase}），不拿这一页那份旧的整份盖回去。
 */
public class ProfileEditorRebaseTest {

    private static Map<String, String> map(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** 别处关掉了前座舱，这一页改了码率：两样都留下，前座舱不被这一页旧的那份打开。 */
    @Test
    public void anOutsideChangeSurvivesAnEditHere() {
        Map<String, String> base = map("cam.cabin1.enabled", "true", "cam.composite.record.bitrate", "medium");
        Map<String, String> stored = map("cam.cabin1.enabled", "false", "cam.composite.record.bitrate", "medium");
        Map<String, String> mine = map("cam.cabin1.enabled", "true", "cam.composite.record.bitrate", "high");

        Map<String, String> out = ProfileEditorFragment.rebase(stored, base, mine);
        assertEquals("false", out.get("cam.cabin1.enabled"));
        assertEquals("high", out.get("cam.composite.record.bitrate"));
    }

    /** 这一页自己改的那一项照这一页的，哪怕存下的那份在这一项上也变过；这一页没动的照存下的。 */
    @Test
    public void anEditHereWinsOnTheSameKey() {
        Map<String, String> base = map("cam.composite.record.bitrate", "medium");
        Map<String, String> stored = map("cam.composite.record.bitrate", "low");

        assertEquals("high", ProfileEditorFragment.rebase(stored, base,
                map("cam.composite.record.bitrate", "high")).get("cam.composite.record.bitrate"));
        assertEquals("low", ProfileEditorFragment.rebase(stored, base,
                map("cam.composite.record.bitrate", "medium")).get("cam.composite.record.bitrate"));
    }

    /** 这一页加的键加上、去掉的键去掉；别处加的键留着。 */
    @Test
    public void addedAndRemovedKeysFollowThisPage() {
        Map<String, String> base = map("a", "1", "gone", "x");
        Map<String, String> stored = map("a", "1", "gone", "x", "theirs", "y");
        Map<String, String> mine = map("a", "1", "added", "z");

        Map<String, String> out = ProfileEditorFragment.rebase(stored, base, mine);
        assertEquals("z", out.get("added"));
        assertFalse(out.containsKey("gone"));
        assertEquals("y", out.get("theirs"));
    }
}
