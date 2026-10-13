package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link RecentRecordings#newestOfEachSlot}：诊断报告「每一路最新的录像文件」挑哪几个。
 *
 * <p>熄屏 v3 起座舱在屏幕一黑就放开、环视接着录几分钟（项目所有者 2026-10-12 D2）。以前挑全盘最新的三个，
 * 那时三个全是环视，座舱最后录到哪儿报告里看不见 —— 这里钉住每一路各一个。</p>
 */
public class RecentRecordingsTest {

    @Test
    public void eachSlotShowsItsOwnNewestFileEvenWhenTheSurroundRecordedOnAlone() {
        // 20:04 三路一起录；熄屏后座舱放开，环视一个人接着录到 20:07
        List<String> names = Arrays.asList(
                "20261012_200300_cabinrear.mp4",
                "20261012_200400_cabinfront.mp4", "20261012_200400_cabinrear.mp4", "20261012_200400_surround.mp4",
                "20261012_200500_surround.mp4", "20261012_200600_surround.mp4", "20261012_200700_surround.mp4");
        assertEquals("环视、后座舱、前座舱各一个，按开相机的次序",
                Arrays.asList("20261012_200700_surround.mp4", "20261012_200400_cabinrear.mp4",
                        "20261012_200400_cabinfront.mp4"),
                RecentRecordings.newestOfEachSlot(names));
    }

    @Test
    public void namesBeforeAndAfterTheRenameAreTheSameSlot() {
        List<String> names = Arrays.asList(
                "20260901_100000_front.mp4", "20261001_100000_surround.mp4",
                "20261002_100000_back.mp4", "20260901_100000_cabinfront.mp4");
        assertEquals(Arrays.asList("20261001_100000_surround.mp4", "20261002_100000_back.mp4"),
                RecentRecordings.newestOfEachSlot(names));
    }

    @Test
    public void filesThatAreNotOurSegmentsAreLeftOutAndUnknownSlotsComeLast() {
        List<String> names = Arrays.asList(
                "holiday.mp4", "20261012_200400_Surround.mp4", "20261012_200400_right.mp4",
                "20261012_200000_surround.mp4");
        assertEquals("用户自己的、名字不对的不算；第四路（自定义车型）排在后面",
                Arrays.asList("20261012_200000_surround.mp4", "20261012_200400_right.mp4"),
                RecentRecordings.newestOfEachSlot(names));
    }

    @Test
    public void nothingOfOursGivesNothing() {
        assertTrue(RecentRecordings.newestOfEachSlot(Collections.<String>emptyList()).isEmpty());
        assertTrue(RecentRecordings.newestOfEachSlot(Arrays.asList("holiday.mp4", "clip.mp4")).isEmpty());
    }
}
