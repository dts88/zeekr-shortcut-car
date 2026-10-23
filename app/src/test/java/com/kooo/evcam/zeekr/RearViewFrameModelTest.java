package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link RearViewFrameModel} 的单元测试：窗口编辑框里按在哪、拖边拖角、拖框内。
 */
public class RearViewFrameModelTest {

    private static final int MIN = 120;
    private static final int SCREEN_W = 3200;
    private static final int SCREEN_H = 1200;

    private static RearViewFrameModel.Frame frame(int x, int y, int w, int h) {
        return new RearViewFrameModel.Frame(x, y, w, h);
    }

    private static void assertFrame(int x, int y, int w, int h, RearViewFrameModel.Frame f) {
        assertEquals("x", x, f.x);
        assertEquals("y", y, f.y);
        assertEquals("width", w, f.width);
        assertEquals("height", h, f.height);
    }

    // ---------- 按在哪 ----------

    @Test
    public void edgesCornersInsideAndOutside() {
        RearViewFrameModel.Frame f = frame(1000, 400, 900, 340);   // 右 1900，下 740
        float reach = 40;

        assertEquals(RearViewFrameModel.LEFT, RearViewFrameModel.hit(f, 1010, 570, reach));
        assertEquals(RearViewFrameModel.LEFT, RearViewFrameModel.hit(f, 970, 570, reach));     // 边外一点也算
        assertEquals(RearViewFrameModel.RIGHT, RearViewFrameModel.hit(f, 1895, 570, reach));
        assertEquals(RearViewFrameModel.TOP, RearViewFrameModel.hit(f, 1450, 395, reach));
        assertEquals(RearViewFrameModel.BOTTOM, RearViewFrameModel.hit(f, 1450, 760, reach));
        assertEquals(RearViewFrameModel.LEFT | RearViewFrameModel.TOP,
                RearViewFrameModel.hit(f, 1005, 405, reach));
        assertEquals(RearViewFrameModel.RIGHT | RearViewFrameModel.BOTTOM,
                RearViewFrameModel.hit(f, 1920, 760, reach));
        assertEquals(RearViewFrameModel.MOVE, RearViewFrameModel.hit(f, 1450, 570, reach));
        assertEquals(RearViewFrameModel.NONE, RearViewFrameModel.hit(f, 500, 570, reach));
        assertEquals(RearViewFrameModel.NONE, RearViewFrameModel.hit(f, 1450, 900, reach));
    }

    @Test
    public void aSmallFrameStillHasRoomToMoveInTheMiddle() {
        RearViewFrameModel.Frame f = frame(100, 100, MIN, MIN);
        // 抓取范围 60 的话两边早就盖满了；框里一侧最多四分之一（30）
        assertEquals(RearViewFrameModel.MOVE, RearViewFrameModel.hit(f, 160, 160, 60));
        assertEquals(RearViewFrameModel.LEFT, RearViewFrameModel.hit(f, 120, 160, 60));
        assertEquals(RearViewFrameModel.RIGHT, RearViewFrameModel.hit(f, 200, 160, 60));
    }

    // ---------- 拖边、拖角 ----------

    @Test
    public void draggingAnEdgeMovesOnlyThatEdge() {
        RearViewFrameModel.Frame f = frame(1000, 400, 900, 340);

        assertFrame(1000, 400, 1000, 340, RearViewFrameModel.resize(f, RearViewFrameModel.RIGHT,
                100, 50, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
        assertFrame(900, 400, 1000, 340, RearViewFrameModel.resize(f, RearViewFrameModel.LEFT,
                -100, 50, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
        assertFrame(1000, 300, 900, 440, RearViewFrameModel.resize(f, RearViewFrameModel.TOP,
                30, -100, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
        assertFrame(1000, 400, 900, 400, RearViewFrameModel.resize(f, RearViewFrameModel.BOTTOM,
                30, 60, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
    }

    @Test
    public void draggingACornerChangesBothAndKeepsTheOppositeCorner() {
        RearViewFrameModel.Frame f = frame(1000, 400, 900, 340);
        RearViewFrameModel.Frame r = RearViewFrameModel.resize(f,
                RearViewFrameModel.LEFT | RearViewFrameModel.TOP, -200, -100,
                MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H);
        assertFrame(800, 300, 1100, 440, r);
        assertEquals(f.right(), r.right());
        assertEquals(f.bottom(), r.bottom());
    }

    @Test
    public void sizeStopsAtTheMinimumAndTheEdgesStayOnScreen() {
        RearViewFrameModel.Frame f = frame(1000, 400, 900, 340);

        // 往里拖过头：停在最小，对边不动
        assertFrame(1000, 400, MIN, 340, RearViewFrameModel.resize(f, RearViewFrameModel.RIGHT,
                -5000, 0, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
        assertFrame(1780, 400, MIN, 340, RearViewFrameModel.resize(f, RearViewFrameModel.LEFT,
                5000, 0, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
        // 往外拖过头：停在屏幕边
        assertFrame(1000, 400, SCREEN_W - 1000, 340, RearViewFrameModel.resize(f,
                RearViewFrameModel.RIGHT, 5000, 0, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
        assertFrame(1000, 0, 900, 740, RearViewFrameModel.resize(f, RearViewFrameModel.TOP,
                0, -5000, MIN, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H));
    }

    @Test
    public void sizeStopsAtTheWindowLimitEvenWhenTheFrameHasRoom() {
        // 编辑框比窗口能有的高度高（状态栏那一截）：框的高度照窗口的上限停
        RearViewFrameModel.Frame f = frame(0, 0, 900, 340);
        assertFrame(0, 0, 900, 1100, RearViewFrameModel.resize(f, RearViewFrameModel.BOTTOM,
                0, 5000, MIN, SCREEN_W, 1100, SCREEN_W, SCREEN_H));
    }

    // ---------- 拖框内、收回屏幕 ----------

    @Test
    public void movingKeepsTheSizeAndStaysInside() {
        RearViewFrameModel.Frame f = frame(1000, 400, 900, 340);

        assertFrame(1100, 350, 900, 340, RearViewFrameModel.move(f, 100, -50, SCREEN_W, SCREEN_H));
        assertFrame(SCREEN_W - 900, 0, 900, 340,
                RearViewFrameModel.move(f, 9000, -9000, SCREEN_W, SCREEN_H));
        assertFrame(0, SCREEN_H - 340, 900, 340,
                RearViewFrameModel.move(f, -9000, 9000, SCREEN_W, SCREEN_H));
    }

    @Test
    public void aWindowDockedHalfOffScreenOpensFullyInside() {
        // 贴边藏着时窗口一大半在屏幕外
        assertFrame(SCREEN_W - 900, 40, 900, 340,
                RearViewFrameModel.keepInside(SCREEN_W - 72, 40, 900, 340, SCREEN_W, SCREEN_H));
        assertFrame(0, 40, 900, 340,
                RearViewFrameModel.keepInside(72 - 900, 40, 900, 340, SCREEN_W, SCREEN_H));
    }
}
