package com.kooo.evcam.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link OverlayCoordinator#blindSpotWanted} 的单元测试。
 *
 * <p>这条规则有七个输入，光读代码判断不出哪些组合会开 —— 它原先被抄成了两份，
 * 两份的答案还不一样（前台服务那份漏了全局开关、避让、定制键三项）。
 * 规则本身就该有个地方把话说死。</p>
 */
public class OverlayCoordinatorTest {

    private static boolean wanted(boolean global, boolean secondary, boolean mainFloating,
                                  boolean turnSignal, boolean mockTurnSignal,
                                  boolean avmAvoidance, boolean customKey) {
        return OverlayCoordinator.blindSpotWanted(global, secondary, mainFloating,
                turnSignal, mockTurnSignal, avmAvoidance, customKey);
    }

    /** 全关就是不开。 */
    @Test
    public void nothingEnabledMeansNotWanted() {
        assertFalse(wanted(false, false, false, false, false, false, false));
    }

    /** 只开全局开关、一项子功能都没选，等于什么都不用做。 */
    @Test
    public void globalAloneIsNotEnough() {
        assertFalse(wanted(true, false, false, false, false, false, false));
    }

    /** 全局关着，子功能开再多也不该起来 —— 那个总开关得说了算。 */
    @Test
    public void globalOffSuppressesEverySubFeature() {
        assertFalse(wanted(false, true, true, true, true, true, false));
    }

    /** 五项子功能里任意一项，配上全局开关，都足以让它起来。 */
    @Test
    public void anySingleSubFeatureWithGlobalIsEnough() {
        assertTrue("副屏", wanted(true, true, false, false, false, false, false));
        assertTrue("主屏悬浮窗", wanted(true, false, true, false, false, false, false));
        assertTrue("转向灯联动", wanted(true, false, false, true, false, false, false));
        assertTrue("模拟转向灯", wanted(true, false, false, false, true, false, false));
        assertTrue("全景避让", wanted(true, false, false, false, false, true, false));
    }

    /**
     * 定制键唤醒是唯一一个不受全局开关管的。
     *
     * <p>它是从车上的实体键进来的，不该被界面里的一个总开关挡住 ——
     * 按了没反应，人会以为是键坏了。</p>
     */
    @Test
    public void customKeyWakeupIgnoresTheGlobalSwitch() {
        assertTrue(wanted(false, false, false, false, false, false, true));
        assertTrue(wanted(true, false, false, false, false, false, true));
    }
}
