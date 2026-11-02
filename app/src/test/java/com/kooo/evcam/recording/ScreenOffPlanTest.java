package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.ChannelRules.DarkOutlook;

import org.junit.Test;

/**
 * 录制键上那行小字：相机管家说的那一种写成哪一句。2026-11-02 起熄屏怎么录只由相机管家判（项目所有者「熄屏判断收归通道调度」），
 * 规矩和它的测试在 {@code ChannelRules} / {@code RecordInDarkTest}；这里只剩句子。
 */
public class ScreenOffPlanTest {

    @Test
    public void eachOutlookHasItsOwnSentence() {
        assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(DarkOutlook.CONTINUE));
        assertEquals(ScreenOffPlan.PAUSE, ScreenOffPlan.of(DarkOutlook.PAUSE));
        assertEquals(ScreenOffPlan.STOP, ScreenOffPlan.of(DarkOutlook.STOP));
        assertEquals(R.string.record_screen_off_continue, ScreenOffPlan.CONTINUE.text);
        assertEquals(R.string.record_screen_off_pause, ScreenOffPlan.PAUSE.text);
        assertEquals(R.string.record_screen_off_stop, ScreenOffPlan.STOP.text);
    }
}
