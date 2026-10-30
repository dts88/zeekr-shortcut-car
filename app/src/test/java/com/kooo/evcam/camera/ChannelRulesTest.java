package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;

import com.kooo.evcam.camera.CameraNeeds.Holder;
import com.kooo.evcam.camera.ChannelRules.Condition;
import com.kooo.evcam.camera.ChannelRules.Decision;
import com.kooo.evcam.camera.ChannelRules.Device;
import com.kooo.evcam.camera.ChannelRules.Event;
import com.kooo.evcam.camera.ChannelRules.Kind;
import com.kooo.evcam.camera.ChannelRules.Lane;
import com.kooo.evcam.camera.ChannelRules.Move;
import com.kooo.evcam.camera.ChannelRules.Out;
import com.kooo.evcam.camera.ChannelRules.Outcome;
import com.kooo.evcam.camera.ChannelRules.RecordPrep;
import com.kooo.evcam.camera.ChannelRules.Rescue;
import com.kooo.evcam.camera.ChannelRules.Why;
import com.kooo.evcam.camera.ChannelRules.World;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通道调度的规则（channel-logic §一、§二，项目所有者 2026-10-10 确认；§7 六问 2026-10-11 答「按建议」）。
 *
 * <p>确认文档的每一句在这里都有一条测试对着。判错的代价是实车上的：环视比座舱晚关完，下一次打开它一帧不出
 * （2026-10-09）；交接的中途动通道，就是自己顶自己（2026-10-08 那七次）；被拿着的一路去试开，在前台可能把原厂功能挤掉
 * （2026-10-10）。现场回放取自 {@code diag_15_bb.txt}（10-10）和 09-27 的冲突。</p>
 *
 * <p>相机那一侧由这里的几个小方法照 SingleCamera 的样子扮演：开好、配好、出第一帧、关完，都是测试说了算。</p>
 */
public class ChannelRulesTest {

    private static final long T0 = 1_000_000L;
    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;
    /** 设备错误 4（ERROR_CAMERA_DEVICE）：不属于「被占用」一类。 */
    private static final int DEVICE_ERROR = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE;

    private final Lane surround = new Lane(SUR, "2");
    private final Lane rear = new Lane(REAR, "1");
    private final Lane front = new Lane(FRONT, "0");
    /** 故意不按次序给：规则自己按开的次序排。 */
    private final World w = new World(Arrays.asList(front, rear, surround));

    private final Out pvS = new Out(Kind.PREVIEW, "preview-2");
    private final Out pvR = new Out(Kind.PREVIEW, "preview-1");
    private final Out pvF = new Out(Kind.PREVIEW, "preview-0");
    private final Out recS = new Out(Kind.RECORD, "recorder-2");
    private final Out recR = new Out(Kind.RECORD, "recorder-1");
    private final Out recF = new Out(Kind.RECORD, "recorder-0");
    private final Out mirror = new Out(Kind.MIRROR, "mirror");

    /** 最近这一轮记账报的事。 */
    private List<Event> events = new ArrayList<>();
    /** 照做了的每一步。 */
    private final List<Decision> done = new ArrayList<>();

    @Before
    public void setUp() {
        w.now = T0;
        w.screenOn = true;
        w.foregroundService = true;
        w.cameraUsable = true;
        w.heardAnything = true;
        // 「熄屏持续录制」默认开着（项目所有者 2026-10-27 的熄屏规矩里环视留不留要它）
        w.keepWhenDark = true;
    }

    /** 熄屏时环视留着：哨兵模式布防、通道刚算黑那一下已经判过（{@link ChannelRules#judgeSurroundInDark}）。 */
    private void keptInDark() {
        w.sentry = 2;
        w.lastSentry = 2;
        w.surroundInDark = ChannelRules.SurroundInDark.KEPT;
    }

    // ================================================================= 该开

    @Test
    public void previewWantsEveryLaneOnlyWhileTheScreenIsOn() {
        claim(Holder.PREVIEW);
        for (Lane lane : w.lanes) {
            assertEquals(EnumSet.of(Holder.PREVIEW), ChannelRules.wantedBy(w, lane));
        }
        w.screenOn = false;
        for (Lane lane : w.lanes) {
            assertFalse("熄屏时主界面看不见，PREVIEW 不算", ChannelRules.wanted(w, lane));
        }
    }

    @Test
    public void recordingWantsTheLanesInItsPlan() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        w.recordPlan.add(REAR);
        rear.declared.add(recR);
        assertTrue(ChannelRules.wanted(w, surround));
        assertTrue(ChannelRules.wanted(w, rear));
        assertEquals(set(recR), ChannelRules.wantedOutputs(w, rear));
        assertFalse("不在这次录像计划里", ChannelRules.wanted(w, front));
        w.screenOn = false;
        assertFalse("熄屏、环视没留着：放开（项目所有者 2026-10-27）", ChannelRules.wanted(w, surround));
        keptInDark();
        assertTrue("熄屏、环视留着（哨兵布防、熄屏持续录制开）：照录", ChannelRules.wanted(w, surround));
        assertFalse("熄屏座舱放开（项目所有者 2026-10-12 D2）", ChannelRules.wanted(w, rear));
        assertTrue("录像输出也不要", ChannelRules.wantedOutputs(w, rear).isEmpty());
        w.screenOn = true;
        assertTrue("亮屏又要了", ChannelRules.wanted(w, rear));
        assertEquals(set(recR), ChannelRules.wantedOutputs(w, rear));
    }

    /**
     * 配置里没启用环视（只开座舱）：环视照样建着。登记表 §一 里主界面预览、拍照要的是「启用的全部」，不要它；
     * 后视镜要的是环视，照样要（项目所有者 2026-10-16）。
     */
    @Test
    public void aSurroundThatIsNotEnabledOpensForTheMirrorOnly() {
        Lane sur = new Lane(SUR, "2");
        Lane cab = new Lane(REAR, "1");
        World cabinsOnly = new World(Arrays.asList(cab, sur), new LinkedHashSet<>(Collections.singletonList(REAR)));
        cabinsOnly.screenOn = true;
        cabinsOnly.holders.add(Holder.PREVIEW);
        cabinsOnly.holders.add(Holder.PHOTO);
        sur.declared.add(pvS);
        assertFalse("没启用：主界面预览、拍照都不要", ChannelRules.wanted(cabinsOnly, sur));
        assertTrue(ChannelRules.wantedOutputs(cabinsOnly, sur).isEmpty());
        assertEquals(EnumSet.of(Holder.PREVIEW, Holder.PHOTO), ChannelRules.wantedBy(cabinsOnly, cab));

        cabinsOnly.holders.add(Holder.MIRROR);
        sur.declared.add(mirror);
        assertEquals(EnumSet.of(Holder.MIRROR), ChannelRules.wantedBy(cabinsOnly, sur));
        assertEquals("会话里只带后视镜", set(mirror), ChannelRules.wantedOutputs(cabinsOnly, sur));
    }

    /**
     * 盲区画面和超级镜子同一条规矩（项目所有者 2026-10-16）：要环视，配置里没开环视也要；座舱不为它开；
     * 只要它自己那块画布。没登记 SIDE_VIEW 时声明着的画布不算要（留着的输出，等下一次本来要动时再摘）。
     */
    @Test
    public void theSideViewWantsTheSurroundLikeTheMirror() {
        Out side = new Out(Kind.SIDE, "side");
        Lane sur = new Lane(SUR, "2");
        Lane cab = new Lane(REAR, "1");
        World cabinsOnly = new World(Arrays.asList(cab, sur), new LinkedHashSet<>(Collections.singletonList(REAR)));
        cabinsOnly.screenOn = true;
        sur.declared.add(side);
        sur.declared.add(mirror);
        assertTrue("没登记：声明着也不要", ChannelRules.wantedOutputs(cabinsOnly, sur).isEmpty());
        assertFalse(ChannelRules.wanted(cabinsOnly, sur));

        cabinsOnly.holders.add(Holder.SIDE_VIEW);
        assertEquals(EnumSet.of(Holder.SIDE_VIEW), ChannelRules.wantedBy(cabinsOnly, sur));
        assertFalse("座舱不为它开", ChannelRules.wanted(cabinsOnly, cab));
        assertEquals("只要盲区画面那块画布", set(side), ChannelRules.wantedOutputs(cabinsOnly, sur));

        cabinsOnly.holders.add(Holder.MIRROR);
        assertEquals("和超级镜子各要各的", set(mirror, side), ChannelRules.wantedOutputs(cabinsOnly, sur));
    }

    /**
     * HUD 上的盲区画面（2026-10-21）：另一样输出，跟中控屏那块同一个登记方、同一条规矩 —— 只在环视上、SIDE_VIEW 登记着才要；
     * 两块可以同时要。通道算黑、没在开车时（2026-10-27）留着的环视也只要录像输出，盲区画面不算；在开车时照旧要。
     */
    @Test
    public void theHudCanvasFollowsTheSideViewHolder() {
        Out side = new Out(Kind.SIDE, "side");
        Out hud = new Out(Kind.HUD, "hud");
        Lane sur = new Lane(SUR, "2");
        Lane cab = new Lane(REAR, "1");
        World w = new World(Arrays.asList(cab, sur), new LinkedHashSet<>(Collections.singletonList(REAR)));
        w.screenOn = true;
        sur.declared.add(hud);
        cab.declared.add(new Out(Kind.HUD, "hud-on-cabin"));
        assertTrue("没登记：声明着也不要", ChannelRules.wantedOutputs(w, sur).isEmpty());

        w.holders.add(Holder.SIDE_VIEW);
        assertEquals("只要 HUD 那块", set(hud), ChannelRules.wantedOutputs(w, sur));
        assertTrue("座舱不为它开", ChannelRules.wantedOutputs(w, cab).isEmpty());
        assertFalse(ChannelRules.wanted(w, cab));

        sur.declared.add(side);
        assertEquals("中控屏、HUD 两块一起要", set(hud, side), ChannelRules.wantedOutputs(w, sur));

        w.screenOn = false;
        w.keepWhenDark = true;
        w.surroundInDark = ChannelRules.SurroundInDark.KEPT;
        assertTrue("熄屏、没在开车：环视留着也不要盲区画面", ChannelRules.wantedOutputs(w, sur).isEmpty());
        assertFalse(ChannelRules.wanted(w, sur));
        w.driving = true;
        assertEquals("在开车：照旧要", set(hud, side), ChannelRules.wantedOutputs(w, sur));
        w.surroundInDark = ChannelRules.SurroundInDark.RELEASED;
        assertEquals("在开车时环视不按熄屏放开", set(hud, side), ChannelRules.wantedOutputs(w, sur));
    }

    /**
     * 超级镜子「后视 + 后座舱」（项目所有者 2026-10-19）：登记着 MIRROR 时后座舱和环视一样有人要，只带超级镜子那块画布；
     * 配置里没启用后座舱也一样（管线照它建了这一路），主界面预览、拍照照旧不要它；熄屏照旧放开；前座舱不为它开。
     */
    @Test
    public void theMirrorWantsTheRearCabinOnlyInTheRearCabinViews() {
        Out cabinMirror = new Out(Kind.MIRROR, "mirror-cabin");
        Lane sur = new Lane(SUR, "2");
        Lane cab = new Lane(REAR, "1");
        Lane cabFront = new Lane(FRONT, "0");
        World surroundOnly = new World(Arrays.asList(cabFront, cab, sur), new LinkedHashSet<>(Collections.singletonList(SUR)));
        surroundOnly.screenOn = true;
        surroundOnly.holders.add(Holder.PREVIEW);
        surroundOnly.holders.add(Holder.PHOTO);
        surroundOnly.holders.add(Holder.MIRROR);
        cab.declared.add(cabinMirror);
        assertFalse("四向、前后：超级镜子不要后座舱", ChannelRules.wanted(surroundOnly, cab));
        assertTrue(ChannelRules.wantedOutputs(surroundOnly, cab).isEmpty());

        surroundOnly.mirrorCabin = true;
        assertEquals("没启用：只有超级镜子要它", EnumSet.of(Holder.MIRROR), ChannelRules.wantedBy(surroundOnly, cab));
        assertEquals("只带超级镜子那块画布", set(cabinMirror), ChannelRules.wantedOutputs(surroundOnly, cab));
        assertFalse("前座舱不为它开", ChannelRules.wanted(surroundOnly, cabFront));

        surroundOnly.holders.remove(Holder.MIRROR);
        assertFalse("超级镜子没登记：不要", ChannelRules.wanted(surroundOnly, cab));
        assertTrue(ChannelRules.wantedOutputs(surroundOnly, cab).isEmpty());

        surroundOnly.holders.add(Holder.MIRROR);
        surroundOnly.screenOn = false;
        assertTrue("熄屏照旧放开座舱", ChannelRules.releasedByDark(surroundOnly, cab));
        assertFalse(ChannelRules.wanted(surroundOnly, cab));
        assertTrue(ChannelRules.wantedOutputs(surroundOnly, cab).isEmpty());
    }

    /** 没说启用了哪几路：管线里的全部都算启用（以前的样子）。 */
    @Test
    public void everyLaneIsEnabledWhenNoneAreNamed() {
        assertEquals(new LinkedHashSet<>(Arrays.asList(SUR, REAR, FRONT)), w.enabled);
    }

    @Test
    public void photoWantsEveryLaneAndTheMirrorOnlyTheSurround() {
        claim(Holder.MIRROR);
        assertEquals(EnumSet.of(Holder.MIRROR), ChannelRules.wantedBy(w, surround));
        assertFalse(ChannelRules.wanted(w, rear));
        assertFalse(ChannelRules.wanted(w, front));
        claim(Holder.PHOTO);
        assertEquals(EnumSet.of(Holder.MIRROR, Holder.PHOTO), ChannelRules.wantedBy(w, surround));
        assertEquals(EnumSet.of(Holder.PHOTO), ChannelRules.wantedBy(w, rear));
        assertEquals(EnumSet.of(Holder.PHOTO), ChannelRules.wantedBy(w, front));
        w.screenOn = false;
        keptInDark();
        assertTrue("熄屏、没在开车：拍照、超级镜子都不算（项目所有者 2026-10-27），留着的环视也不要",
                ChannelRules.wantedBy(w, surround).isEmpty());
        assertTrue(ChannelRules.wantedBy(w, rear).isEmpty());
        assertTrue(ChannelRules.wantedBy(w, front).isEmpty());
        w.driving = true;
        assertEquals("在开车：熄屏拍照只要环视（和以前一样）", EnumSet.of(Holder.MIRROR, Holder.PHOTO),
                ChannelRules.wantedBy(w, surround));
        assertTrue(ChannelRules.wantedBy(w, rear).isEmpty());
        assertTrue(ChannelRules.wantedBy(w, front).isEmpty());
    }

    /**
     * 熄屏放开相机（项目所有者 2026-10-12 D2，设计 v3.1 C4；2026-10-27）：只看屏幕、开车、哨兵模式和设置，和谁登记着无关 ——
     * 预览、录像、拍照、后视镜一律不要座舱；环视没留着时一样放开，留着时只有录像算；在开车时环视照各自的规矩（和以前一样）。
     * 自定义车型上环视以外的每一路都一样放开。
     */
    @Test
    public void inChannelDarkOnlyAKeptSurroundIsWantedAndOnlyByRecording() {
        assertFalse("亮着、谁都没登记：不算放开", ChannelRules.releasedByDark(w, rear));
        assertFalse(ChannelRules.releasedByDark(w, surround));
        w.screenOn = false;
        assertTrue("谁都没登记也算", ChannelRules.releasedByDark(w, rear));
        assertTrue(ChannelRules.releasedByDark(w, front));
        assertTrue("环视没留着：也放开", ChannelRules.releasedByDark(w, surround));
        assertTrue("自定义车型的第四路也放开", ChannelRules.releasedByDark(w, new Lane(CameraSlots.KEY_FOURTH, "3")));
        keptInDark();
        assertFalse("环视留着：不放开", ChannelRules.releasedByDark(w, surround));
        w.keepWhenDark = false;
        assertTrue("熄屏持续录制关着：哨兵布防也放开", ChannelRules.releasedByDark(w, surround));
        w.keepWhenDark = true;

        planAll();
        for (Holder holder : Holder.values()) {
            w.holders.clear();
            claim(holder);
            assertFalse(holder + "：熄屏时后座舱谁都不要", ChannelRules.wanted(w, rear));
            assertFalse(holder + "：熄屏时前座舱谁都不要", ChannelRules.wanted(w, front));
            assertEquals(holder + "：留着的环视只算录像", holder == Holder.RECORDING, ChannelRules.wanted(w, surround));
        }
        w.surroundInDark = ChannelRules.SurroundInDark.RELEASED;
        for (Holder holder : Holder.values()) {
            w.holders.clear();
            claim(holder);
            assertFalse(holder + "：没留着的环视谁都不要", ChannelRules.wanted(w, surround));
        }
        w.driving = true;
        for (Holder holder : Holder.values()) {
            w.holders.clear();
            claim(holder);
            assertFalse(holder + "：在开车，座舱照旧放开", ChannelRules.wanted(w, rear));
            assertEquals(holder + "：在开车，环视照它自己的规矩", holder != Holder.PREVIEW, ChannelRules.wanted(w, surround));
        }
        w.driving = false;
        w.screenOn = true;
        for (Holder holder : Holder.values()) {
            w.holders.clear();
            claim(holder);
            assertEquals(holder + "：亮屏照登记表", holder != Holder.MIRROR && holder != Holder.SIDE_VIEW,
                    ChannelRules.wanted(w, rear));
            assertFalse(ChannelRules.releasedByDark(w, rear));
        }
    }

    /**
     * 通道眼里的熄屏（项目所有者 2026-10-13「黑满 30 秒再放」）：屏幕黑满 30 秒（和「保持 30 秒」同一个数）、或者车机要睡
     * （黑着时安卓也熄屏了）才算黑；黑了不到 30 秒照亮着算。起点不明按满 30 秒算。还要多久才算黑，调度照它到点醒。
     */
    @Test
    public void theChannelCallsItDarkOnlyAfterThirtySecondsOrWhenTheHeadUnitSleeps() {
        assertFalse("亮着", ChannelRules.darkForChannel(false, -1, false));
        assertFalse("刚黑", ChannelRules.darkForChannel(true, 0, false));
        assertFalse("10-13 20:10:11 车门开关的那 4.7 秒", ChannelRules.darkForChannel(true, 4_700, false));
        assertFalse("29.999 秒", ChannelRules.darkForChannel(true, ChannelRules.HOLD_MS - 1, false));
        assertTrue("黑满 30 秒", ChannelRules.darkForChannel(true, ChannelRules.HOLD_MS, false));
        assertTrue("起点不明：按满 30 秒算", ChannelRules.darkForChannel(true, -1, false));
        assertTrue("车机要睡：不等 30 秒", ChannelRules.darkForChannel(true, 5_000, true));
        assertTrue(ChannelRules.darkForChannel(true, -1, true));

        assertEquals("亮着不等", -1, ChannelRules.untilDarkForChannel(false, -1, false));
        assertEquals(ChannelRules.HOLD_MS, ChannelRules.untilDarkForChannel(true, 0, false));
        assertEquals(ChannelRules.HOLD_MS - 4_700, ChannelRules.untilDarkForChannel(true, 4_700, false));
        assertEquals(1, ChannelRules.untilDarkForChannel(true, ChannelRules.HOLD_MS - 1, false));
        assertEquals("已经算黑了", -1, ChannelRules.untilDarkForChannel(true, ChannelRules.HOLD_MS, false));
        assertEquals("起点不明", -1, ChannelRules.untilDarkForChannel(true, -1, false));
        assertEquals("车机要睡", -1, ChannelRules.untilDarkForChannel(true, 5_000, true));
    }

    /**
     * 屏幕真黑了、还不到 30 秒（{@link World#screenDark}，通道照亮着算）：开着的座舱照旧，关着的不在黑屏里开、算熄屏放开 ——
     * 「30 秒内一步不动」（项目所有者 2026-10-13）。这时来的录像只开环视（和 2.11.2 一样）；黑满 30 秒时没有座舱可放，
     * 什么都不动；亮了座舱照常一路一路开。
     */
    @Test
    public void aClosedCabinIsNotOpenedWhileTheScreenIsDarkButNotYetThirtySeconds() {
        w.screenDark = true;
        assertTrue("关着的座舱不开：算放开", ChannelRules.releasedByDark(w, rear));
        assertTrue(ChannelRules.releasedByDark(w, front));
        assertFalse("环视照登记表", ChannelRules.releasedByDark(w, surround));
        rear.device = Device.OPEN;
        assertFalse("开着的照旧", ChannelRules.releasedByDark(w, rear));
        rear.device = Device.OPENING;
        assertFalse("在开的照旧开完", ChannelRules.releasedByDark(w, rear));
        rear.device = Device.CLOSED;

        planAll();
        surround.declared.add(recS);
        rear.declared.add(recR);
        front.declared.add(recF);
        claim(Holder.RECORDING);
        assertEquals(Move.IDLE, run().move);
        assertEquals("只开了环视", 1, done.size());
        assertEquals(Move.OPEN, done.get(0).move);
        assertEquals(Collections.singletonList(SUR), done.get(0).keys);
        assertTrue(rear.device == Device.CLOSED && front.device == Device.CLOSED);

        w.sentry = 2;   // 哨兵模式布防：环视留着（项目所有者 2026-10-27）
        w.screenOn = false;
        assertEquals("黑满 30 秒：没有座舱可放，什么都不动", Move.IDLE, run().move);
        assertEquals(1, done.size());

        w.screenOn = true;
        w.screenDark = false;
        assertEquals(Move.IDLE, run().move);
        assertEquals("亮了：后座舱、前座舱一路一路开", 3, done.size());
        assertEquals(Collections.singletonList(REAR), done.get(1).keys);
        assertEquals(Collections.singletonList(FRONT), done.get(2).keys);
    }

    /**
     * 录像留着一路的录像输出（录制器）：RECORDING 登记着、这一路在计划里 —— 熄屏放开的座舱也留着（停放过夜，亮屏同一个录制器
     * 接回）；停了录、不在计划里就不留（设计 v3.1 C13）。
     */
    @Test
    public void recordingKeepsTheRecordOutputOfEveryPlannedLaneEvenInTheDark() {
        assertFalse(ChannelRules.keepsRecordOutput(w, rear));
        claim(Holder.RECORDING);
        assertFalse("不在计划里", ChannelRules.keepsRecordOutput(w, rear));
        w.recordPlan.add(REAR);
        assertTrue(ChannelRules.keepsRecordOutput(w, rear));
        w.screenOn = false;
        assertTrue("熄屏放开了也留着", ChannelRules.keepsRecordOutput(w, rear));
        drop(Holder.RECORDING);
        assertFalse("停了录不留", ChannelRules.keepsRecordOutput(w, rear));
    }

    /** 拍照（§一）：没开的路按次序开、带出帧口；已经开着的路什么都不改；拍完出帧口也不摘。 */
    @Test
    public void photoOpensClosedLanesWithTheSinkAndLeavesOpenOnesAlone() {
        // 主界面最小化：环视开着，预览画布还活着，PREVIEW 没登记 —— 留着的活输出
        streaming(surround, pvS);
        claim(Holder.PHOTO);
        assertEquals(Move.IDLE, run().move);
        assertEquals(2, done.size());
        assertEquals(Collections.singletonList(REAR), done.get(0).keys);
        assertEquals(set(Out.SINK), done.get(0).outputs);
        assertEquals(Collections.singletonList(FRONT), done.get(1).keys);
        assertEquals(set(Out.SINK), done.get(1).outputs);
        assertEquals("开着的环视什么都不改", 0, count(Move.APPLY));
        assertEquals(set(pvS), surround.session);

        drop(Holder.PHOTO);
        assertEquals(Move.IDLE, round().move);
        assertEquals("拍完：开着的路不动，出帧口不摘", 2, done.size());
        assertEquals(set(Out.SINK), rear.session);
    }

    // ================================================================= 保持（§7.1：每一路各自算）

    @Test
    public void holdsAnUnwantedLaneThirtySecondsWithTheScreenOnAndTheForegroundService() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);

        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        Event held = event(Event.Type.HOLD_STARTED);
        assertNotNull(held);
        assertEquals(Arrays.asList(SUR, REAR, FRONT), held.keys);

        at(ChannelRules.HOLD_MS - 1);
        assertEquals(Move.IDLE, round().move);

        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("环视单独先关", Collections.singletonList(SUR), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        assertEquals(ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        perform(d);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        assertEquals(ChannelRules.CLOSE_CABINS_MAX_MS, d.maxMs);
        assertEquals("会话一次都没改", 0, count(Move.APPLY));
    }

    @Test
    public void noHoldWithoutTheForegroundService() {
        w.foregroundService = false;
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        Decision d = round();
        assertNull(event(Event.Type.HOLD_STARTED));
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.UNWANTED, d.why);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.UNWANTED, d.why);
    }

    /**
     * 熄屏时 PREVIEW 不算、不保持（§7.5，项目所有者 2026-10-11）：开发者「熄屏录制（阻止休眠）」开着时主界面熄屏不退后台，
     * PREVIEW 还登记着，没在录也照登记表按次序关。环视关的原因是熄屏，座舱是熄屏放开（设计 v3.1 C14：环视熄屏时关不写成
     * 「座舱放开」）。
     */
    @Test
    public void screenOffClosesWhenNotRecordingEvenWithTheDevWakeLock() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        w.screenOn = false;
        Decision d = round();
        assertNull(event(Event.Type.HOLD_STARTED));
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
    }

    @Test
    public void noHoldWhenTheHeadUnitSleepsOrOnExit() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, rear));

        w.sleepSince = w.now;
        assertFalse(ChannelRules.holding(w, rear));
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.SLEEP, d.why);

        w.sleepSince = 0;
        w.exitSince = w.now;
        assertFalse(ChannelRules.holding(w, rear));
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.EXIT, d.why);
    }

    @Test
    public void aClosedLaneIsNeverOpenedToHold() {
        streaming(surround, pvS);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        assertFalse(ChannelRules.shouldOpen(w, rear));
        assertFalse(ChannelRules.shouldOpen(w, front));
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertTrue(done.isEmpty());
    }

    /**
     * §7.1 每一路各自算（项目所有者 2026-10-11）：只开超级镜子、主界面最小化时，两路座舱各自保持 30 秒，
     * 30 秒内回到主界面通道零变动；超过 30 秒两路座舱关，环视留给后视镜 —— 2.11.6 起（项目所有者 2026-10-15）先单独关环视，
     * 座舱关完它照常开回来给后视镜（关座舱前先关环视）。
     */
    @Test
    public void eachLaneHoldsOnItsOwn() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);

        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertEquals(Arrays.asList(REAR, FRONT), event(Event.Type.HOLD_STARTED).keys);

        at(20_000);
        claim(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertEquals(Arrays.asList(REAR, FRONT), event(Event.Type.HOLD_CANCELLED).keys);
        assertTrue("30 秒内回来，通道零变动", done.isEmpty());

        at(25_000);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        at(25_000 + ChannelRules.HOLD_MS - 1);
        assertEquals(Move.IDLE, round().move);
        at(25_000 + ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("关座舱前先关环视", Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SURROUND_FIRST, d.why);
        perform(d);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("座舱保持到点，一起关", Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        perform(d);
        d = round();
        assertEquals("环视留给后视镜：照常开回来", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(EnumSet.of(Holder.MIRROR), d.forWhom);
        assertEquals("留着的预览不带回来", set(mirror), d.outputs);
    }

    // ================================================================= 顺序

    /** 手上那一步没做完（含等第一帧）：别的路一步都不动。 */
    @Test
    public void theStepInHandBlocksEverythingIncludingTheWaitForTheFirstFrame() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        startOnly(d);
        at(500);
        opened(surround, d.outputs);
        Decision waiting = round();
        assertEquals(Move.WAIT, waiting.move);
        assertEquals(Why.STEP, waiting.why);
        assertEquals(ChannelRules.STEP_MAX_MS - 500, waiting.ms);
        at(3_000);
        frame(surround);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    /**
     * 别人的在途（一路 isBusy、一路正被接手）也挡住一切，各最多 60 秒。第 2 步起没有「另一个实例在关」：进程里只有一个调度，
     * 换下去的那一份的关在账本上（{@link Lane#closingSince}），照「有一次关没返回」那一条等。
     */
    @Test
    public void othersInFlightBlockEverythingForAtMostSixtySeconds() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        front.declared.add(pvF);

        rear.busySince = w.now;
        assertWait(Why.BUSY, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertWait(Why.BUSY, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(Move.OPEN, round().move);

        rear.busySince = 0;
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        assertWait(Why.LOSING, REAR, round());
    }

    @Test
    public void closesBeforeOpening() {
        w.foregroundService = false;
        claim(Holder.RECORDING);
        w.recordPlan.add(REAR);
        rear.declared.add(recR);
        streaming(surround, pvS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(recR), d.outputs);
    }

    /** §二.2：环视单独先关，关完了座舱才一起关（2026-10-09：环视比座舱晚关完，下一次打开它一帧不出）。 */
    @Test
    public void theSurroundClosesAloneThenTheCabinsTogether() {
        w.foregroundService = false;
        streamingAll();
        Decision d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        startOnly(d);
        at(100);
        assertWait(Why.STEP, SUR, round());
        at(200);
        closed(surround);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(ChannelRules.CLOSE_CABINS_MAX_MS, d.maxMs);
    }

    /**
     * 环视关卡住了：10 秒到点不再等它，接着关座舱。相机层还报着它在关（closingSince）也一样 —— 我们按次序关的那一步照 §二.2 的封顶，
     * 「在关一个设备就是在途」（2.11.4 第 1c 项）不管它（{@link #aCameraStillClosingItsOldDeviceIsInFlightAfterItsStepTimesOut}）。
     */
    @Test
    public void aStuckSurroundCloseStopsHoldingTheCabinsAfterTenSeconds() {
        w.foregroundService = false;
        streamingAll();
        Decision d = round();
        startOnly(d);
        assertTrue("相机层登记着在关", surround.closingSince != 0);
        at(ChannelRules.CLOSE_SURROUND_MAX_MS - 1);
        assertEquals(Outcome.RUNNING, ChannelRules.outcome(w));
        assertWait(Why.STEP, SUR, round());
        at(ChannelRules.CLOSE_SURROUND_MAX_MS);
        assertEquals(Outcome.TIMED_OUT, ChannelRules.outcome(w));
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
    }

    @Test
    public void closingDoesNotWaitForQuiet() {
        w.foregroundService = false;
        streamingAll();
        w.lastOthersChangeAt = w.now;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    // ================================================================= 关座舱前先关环视（项目所有者 2026-10-15 定，2.11.6）

    /**
     * 10-14 21:52:20（A7）的形状：三路在录、主界面在后台（前台服务是主界面在前时起的：能用相机），屏幕黑满 30 秒放开座舱。
     * 以前座舱在开着的环视旁边关，环视停在前座舱关完那一帧前后、救了 42 秒；现在先单独关环视（10 秒封顶），座舱等它关完再一起关
     * （熄屏：座舱放开），座舱关完「开」那一步照常把环视开回来、带着它停放着的录像输出。三步都是这里出的普通一步；
     * 登记表一样没变（谁要它都没变：录像要它），救援梯子一次都没算。
     */
    @Test
    public void replay1014_aDarkReleaseClosesTheSurroundFirstThenTheCabinsThenOpensItAgain() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);
        Set<Holder> holders = EnumSet.copyOf(w.holders);

        w.sentry = 2;         // 哨兵模式布防：环视留着（项目所有者 2026-10-27）
        w.screenOn = false;   // 黑满 30 秒
        Decision d = round();
        assertEquals("环视留着", ChannelRules.SurroundInDark.KEPT, w.surroundInDark);
        assertEquals(Move.CLOSE, d.move);
        assertEquals("先单独关环视", Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SURROUND_FIRST, d.why);
        assertEquals("环视单独关的封顶照旧", ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        startOnly(d);
        at(50);
        assertWait(Why.STEP, SUR, round());
        at(150);
        closed(surround);
        serviceSays(surround, true, true);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("座舱等环视关完，再一起关", Arrays.asList(REAR, FRONT), d.keys);
        assertEquals("原因照座舱自己的", Why.DARK_RELEASE, d.why);
        assertEquals(ChannelRules.CLOSE_CABINS_MAX_MS, d.maxMs);
        startOnly(d);
        at(250);
        closed(rear);
        closed(front);
        serviceSays(rear, true, true);
        serviceSays(front, true, true);
        d = round();
        assertEquals("座舱关完：开那一步照常开回环视", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.WANTED, d.why);
        assertEquals(EnumSet.of(Holder.RECORDING), d.forWhom);
        assertEquals("带着停放着的录制器的录像输出", set(recS), d.outputs);
        startOnly(d);
        at(450);
        opened(surround, d.outputs);
        frame(surround);
        assertEquals(Move.IDLE, round().move);

        assertEquals(3, done.size());
        assertEquals("登记表没动", holders, w.holders);
        assertEquals("救援梯子一次都没算", 0, surround.ladder.attempts());
        assertFalse(surround.lostForRescue);
        assertNull(surround.mark);
        assertTrue(ChannelRules.streaming(surround));
        assertTrue("座舱照旧熄屏放开", ChannelRules.releasedByDark(w, rear) && rear.device == Device.CLOSED);
    }

    /**
     * 同一个形状，哨兵模式关着（项目所有者 2026-10-27）：环视没留着，和座舱同一轮放开 —— 环视先单独关（原因「熄屏」，不是
     * 「关座舱前先关环视」），关完座舱一起关（熄屏：座舱放开），什么都不开回来。不会先关环视、开回来给录像、紧接着又关。
     * 录制器都停放着（录像还登记着），三路都不算在起来。
     */
    @Test
    public void replay1014NotKept_theSurroundClosesFirstThenTheCabinsAndNothingReopens() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);
        Set<Holder> holders = EnumSet.copyOf(w.holders);

        w.sentry = 0;
        w.screenOn = false;   // 黑满 30 秒
        Decision d = round();
        assertEquals(ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals(Event.Cause.DARK_ONSET, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(Move.CLOSE, d.move);
        assertEquals("环视先单独关", Collections.singletonList(SUR), d.keys);
        assertEquals("原因是熄屏，不写成座舱放开", Why.SCREEN_OFF, d.why);
        perform(d);
        serviceSays(surround, true, true);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
        perform(d);
        serviceSays(rear, true, true);
        serviceSays(front, true, true);
        assertEquals("什么都不开回来", Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS);
        assertEquals(Move.IDLE, round().move);
        assertEquals(2, done.size());
        assertEquals(0, count(Move.OPEN));
        assertEquals("登记表没动", holders, w.holders);
        for (Lane lane : w.lanes) {
            assertTrue(lane.key + "：熄屏放开", ChannelRules.releasedByDark(w, lane));
            assertTrue(lane.key + "：录制器停放着", ChannelRules.keepsRecordOutput(w, lane));
        }
    }

    /**
     * 10-15 07:42:42（车主的测试 A 第 2 次）的形状：三路录过一段、停了录（录像输出留在会话里），超级镜子开着、主界面最小化 ——
     * 两路座舱各自保持 30 秒，环视留给后视镜。以前到点座舱在环视旁边关，环视在开始关后 60 ms 停了、救了约 45 秒；现在先单独关环视，
     * 座舱保持到点一起关，环视照常开回来给后视镜 —— 只带后视镜：停录后留着的录像输出、最小化后留着的预览都跟着那一关走了。
     */
    @Test
    public void replay1015_aHoldExpiryBesideTheMirrorClosesTheSurroundFirst() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);   // 07:42:12 最小化：只剩后视镜要环视
        assertEquals(Move.IDLE, round().move);
        assertEquals(Arrays.asList(REAR, FRONT), event(Event.Type.HOLD_STARTED).keys);

        at(ChannelRules.HOLD_MS - 1);
        assertEquals(Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SURROUND_FIRST, d.why);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(EnumSet.of(Holder.MIRROR), d.forWhom);
        assertEquals(set(mirror), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("登记表没动", EnumSet.of(Holder.MIRROR), w.holders);
        assertEquals(0, surround.ladder.attempts());
        assertEquals(set(mirror), surround.session);
    }

    /**
     * 此刻用不了相机（主界面不在前、前台服务是后台起的）：环视关了开不回来 —— 照旧，座舱在开着的环视旁边关（项目所有者 2026-10-15 定的例外）。
     */
    @Test
    public void theCabinsCloseBesideTheSurroundWhileTheCameraCannotBeUsed() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);
        w.cameraUsable = false;
        w.sentry = 2;   // 哨兵模式布防：环视留着
        w.screenOn = false;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("座舱在环视旁边关", Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("环视一步不动", 0, count(Move.CLOSE, surround) + count(Move.OPEN, surround));
        assertEquals(set(recS), surround.session);
    }

    /** 开相机报过「用不了」、要等主界面回到前台的（{@link World#unusableSince}）也算此刻用不了：保持到点的座舱照旧在环视旁边关。 */
    @Test
    public void theCabinsCloseBesideTheSurroundWhileAnOpenReportedTheCameraUnusable() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        w.unusableSince = w.now;
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
    }

    /**
     * 环视自己只在保持（没人要、还不到 30 秒）而座舱先到点：先单独关它（关座舱前先关环视），它的保持跟着结束 —— 保持要开着，
     * 关了就不再开回来。环视自己也不该开了（没人要、没有前台服务不保持）：照旧它先单独关（原因照它自己的），座舱再关，谁都不开回来。
     */
    @Test
    public void anUnwantedSurroundClosesFirstAndIsNotOpenedAgain() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        at(10_000);
        drop(Holder.MIRROR);   // 后视镜 10 秒后才收起：环视的保持晚 10 秒到点
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals("座舱先到点：保持着的环视先单独关", Why.SURROUND_FIRST, d.why);
        perform(d);
        serviceSays(surround, true, true);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        perform(d);
        serviceSays(rear, true, true);
        serviceSays(front, true, true);
        assertEquals("保持要开着：环视不再开回来", Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS + 20_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, count(Move.OPEN));

        // 没人要、也不保持（前台服务不在）：环视先单独关，原因是它自己的，座舱再关，谁都不开回来
        w.foregroundService = false;
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.UNWANTED, d.why);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.UNWANTED, d.why);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, count(Move.OPEN));
    }

    /**
     * 不是这里出的关不走「先关环视」（项目所有者 2026-10-15）：救援重开一路座舱（相机层先关它的旧设备）、一路座舱丢了（报错之后那一次关）
     * 都不动环视。
     */
    @Test
    public void rescueAndLossClosesOfACabinLeaveTheSurroundAlone() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(front, recF);
        rear.declared.add(recR);
        rear.device = Device.OPEN;
        rear.deviceSince = w.now;
        configured(rear, set(recR));   // 配好了，一帧不出
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
        perform(d);

        at(1_000);
        ChannelRules.lossStarted(w, front, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        front.busySince = w.now;
        assertWait(Why.LOSING, FRONT, round());
        at(3_000);
        serviceSays(front, true, true);
        ChannelRules.lossClosed(w, front);
        closed(front);
        at(3_300);
        d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("环视一步都没动", 0, count(Move.CLOSE, surround) + count(Move.OPEN, surround));
        assertEquals(0, count(Move.CLOSE));
    }

    /**
     * 环视自己在救援梯子上（开着、8 秒没进展、救过一次没好）：计划里的关座舱照旧在它旁边关 —— 关了它，就只能由救援按梯子的节奏重开
     * （还要白算次数），不是「开」那一步照常开回来；它此刻本来也没在出画面。
     */
    @Test
    public void aSurroundOnTheRescueLadderIsNotClosedFirst() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        surround.progressAgeMs = CameraLiveness.STUCK_MS + 4_000;
        CameraLiveness.step(surround.ladder, true, Long.MAX_VALUE, T0 - 4_000);   // 救过一次
        assertTrue(ChannelRules.rescuing(surround));
        keptInDark();   // 哨兵模式布防：环视留着
        w.screenOn = false;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
    }

    /** 环视那一步 10 秒到点没关完（相机服务卡住）：座舱照旧不再等它、一起关；环视关完了，开那一步再把它开回来，不算救援。 */
    @Test
    public void aStuckSurroundFirstCloseStillLetsTheCabinsGoAndTheSurroundComesBackOnceClosed() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);
        w.sentry = 2;   // 哨兵模式布防：环视留着
        w.screenOn = false;
        Decision d = round();
        assertEquals(Why.SURROUND_FIRST, d.why);
        startOnly(d);
        at(ChannelRules.CLOSE_SURROUND_MAX_MS - 1);
        assertWait(Why.STEP, SUR, round());
        at(ChannelRules.CLOSE_SURROUND_MAX_MS);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
        perform(d);
        serviceSays(rear, true, true);
        serviceSays(front, true, true);
        assertEquals("环视还在关：不开它", Move.IDLE, round().move);
        at(ChannelRules.CLOSE_SURROUND_MAX_MS + 4_000);
        closed(surround);
        serviceSays(surround, true, true);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(recS), d.outputs);
        assertEquals(0, surround.ladder.attempts());
    }

    /** 环视关的时候人回到了主界面（座舱又有人要了）：座舱不关，环视照常开回来 —— 不多关一次。 */
    @Test
    public void cabinsWantedAgainWhileTheSurroundClosesStayOpenAndTheSurroundComesBack() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Why.SURROUND_FIRST, d.why);
        startOnly(d);
        at(ChannelRules.HOLD_MS + 100);
        claim(Holder.PREVIEW);   // 回到主界面
        closed(surround);
        serviceSays(surround, true, true);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(pvS, mirror), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("座舱一次都没关", 0, count(Move.CLOSE, rear) + count(Move.CLOSE, front));
    }

    /** §二.3：环视 → 后座舱 → 前座舱，上一路出第一帧、或者满 15 秒，才开下一路；满 15 秒的由救援接手、算第 1 次。 */
    @Test
    public void opensSurroundThenRearThenFrontOneAtATime() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(pvS), d.outputs);
        assertEquals(EnumSet.of(Holder.PREVIEW), d.forWhom);
        assertEquals(ChannelRules.STEP_MAX_MS, d.maxMs);
        startOnly(d);
        at(800);
        opened(surround, d.outputs);
        assertEquals("配好了还没出画面：下一路不开", Move.WAIT, round().move);
        at(1_100);
        frame(surround);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);

        // 后座舱一直没回音，相机层还挂着那一下打开
        at(1_100 + ChannelRules.STEP_MAX_MS - 1);
        assertWait(Why.STEP, REAR, round());
        at(1_100 + ChannelRules.STEP_MAX_MS);
        d = round();
        assertEquals("满 15 秒还没出画面：先往下走", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertEquals("由救援接手，算第 1 次", 1, rear.ladder.attempts());
    }

    @Test
    public void aTakenLaneIsSkippedNotWaitedFor() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        assertEquals(Move.IDLE, run().move);
        assertEquals(2, done.size());
        assertEquals(Collections.singletonList(SUR), done.get(0).keys);
        assertEquals(Collections.singletonList(FRONT), done.get(1).keys);
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);
        assertEquals(Condition.TAKEN, ChannelRules.condition(rear));
    }

    @Test
    public void nothingOpensWhileTheCameraCannotBeUsed() {
        w.cameraUsable = false;
        claim(Holder.RECORDING);
        planAll();
        surround.declared.add(recS);
        rear.declared.add(recR);
        front.declared.add(recF);
        Decision d = round();
        assertEquals(Move.IDLE, d.move);
        assertEquals(SUR, d.deferredKey);
        at(60_000);
        assertEquals(Move.IDLE, round().move);
        assertTrue(done.isEmpty());
        for (Lane lane : w.lanes) {
            assertEquals("不计次数", 0, lane.ladder.attempts());
        }
        w.cameraUsable = true;
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(set(recS), d.outputs);
    }

    // ================================================================= 安静（§二.6）

    @Test
    public void quietHoldsBackOpening() {
        claim(Holder.PREVIEW);
        declarePreviews();
        w.lastOthersChangeAt = w.now - (ChannelRules.QUIET_MS - 1);
        Decision d = round();
        assertWait(Why.QUIET, SUR, d);
        assertEquals(Move.OPEN, d.pending);
        assertEquals(1, d.ms);
        at(1);
        assertEquals(Move.OPEN, round().move);
    }

    @Test
    public void quietHoldsBackDeadRemovalApplyAndRescue() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, Out.SINK);
        streaming(front, pvF);
        rear.declared.add(pvR);
        // 环视的画布被系统收走了（撤回了声明，会话里的那一个死了）
        surround.dead.add(pvS);
        surround.declared.remove(pvS);

        w.lastOthersChangeAt = w.now;
        Decision d = round();
        assertWait(Why.QUIET, SUR, d);
        assertEquals(Move.APPLY, d.pending);
        at(ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.DEAD, d.why);
        assertEquals(set(Out.SINK), d.outputs);
        assertEquals(set(pvS), d.removed);
        perform(d);

        w.lastOthersChangeAt = w.now;
        d = round();
        assertWait(Why.QUIET, REAR, d);
        assertEquals(Move.APPLY, d.pending);
        at(2 * ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.MISSING, d.why);
        perform(d);

        front.progressAgeMs = CameraLiveness.STUCK_MS;
        w.lastOthersChangeAt = w.now;
        d = round();
        assertWait(Why.QUIET, FRONT, d);
        assertEquals(Move.RESCUE, d.pending);
        at(3 * ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
    }

    /** 我们自己开、关时相机服务报的那几声不挡安静；我们稳稳开着时它报的「空闲」算别人的（交接时的乱报）。 */
    @Test
    public void ourOwnOpenAndCloseDoNotCountAgainstQuiet() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision d = round();
        startOnly(d);
        serviceSays(surround, false, true);
        at(400);
        opened(surround, d.outputs);
        frame(surround);
        d = round();
        assertEquals("我们自己开引起的那一声不挡", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        serviceSays(surround, true, false);
        d = round();
        assertWait(Why.QUIET, FRONT, d);
        assertEquals(Move.OPEN, d.pending);
    }

    // ================================================================= 减输出能拖就拖（§二.4）

    /**
     * 不要了的活输出留在会话里，不为它改会话：主界面最小化、录像照录，预览画布还活着、没人要了 —— 一步都不动。熄屏以后两路座舱
     * 熄屏放开（项目所有者 2026-10-12 D2）：2.11.6 起先单独关环视（关座舱前先关环视），座舱一起关，环视开回来接着录 ——
     * 留着的预览是「下一次本来要动、或者关相机时一起摘」的那一种，跟着这一关走了，开回来只带要的录像输出；会话一次都没为它改过。
     */
    @Test
    public void aLiveOutputNobodyWantsStaysInTheSession() {
        claim(Holder.PREVIEW);
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, pvS, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertTrue(done.isEmpty());

        w.sentry = 2;   // 哨兵模式布防：环视留着（项目所有者 2026-10-27）
        w.screenOn = false;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("关座舱前先关环视", Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SURROUND_FIRST, d.why);
        assertNull("熄屏不保持", event(Event.Type.HOLD_STARTED));
        perform(d);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("座舱一起关", Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
        assertEquals(ChannelRules.CLOSE_CABINS_MAX_MS, d.maxMs);
        perform(d);
        d = round();
        assertEquals("环视开回来接着录", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals("留着的预览跟着那一关走了：只带要的", set(recS), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("会话一次都没为它改过", 0, count(Move.APPLY));
        assertEquals(set(recS), surround.session);
        assertEquals(ChannelRules.OutState.WANTED, ChannelRules.outState(w, surround, recS));
    }

    /**
     * 录着三路熄屏：环视先单独关（关座舱前先关环视，2.11.6），座舱一起关，环视开回来接着录；亮屏：座舱按开的次序一路一路回来
     * （后座舱 → 前座舱），带着各自停放着的录制器的录像输出开 —— 不多改一次输出。
     */
    @Test
    public void theCabinsLeaveAtDarkAndComeBackOneAtATimeWithTheirRecordersWhenLit() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);

        w.sentry = 2;   // 哨兵模式布防：环视留着（项目所有者 2026-10-27）
        w.screenOn = false;
        Decision d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SURROUND_FIRST, d.why);
        perform(d);
        serviceSays(surround, true, true);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.DARK_RELEASE, d.why);
        perform(d);
        // 相机服务照例为我们这一关说一句「空闲」（我们引起的）。不说的话，开着时那一句「被占用（我们开着）」过了
        // OWN_WORD_GRACE_MS 就只能算别人拿着（recognize），两路座舱被记成 TAKEN，亮屏也不开
        serviceSays(rear, true, true);
        serviceSays(front, true, true);
        d = round();
        assertEquals("环视开回来，带着它的录像输出接着录", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(recS), d.outputs);
        perform(d);
        at(60_000);
        assertEquals("熄屏这段时间里什么都不动", Move.IDLE, round().move);
        assertTrue("录像输出还声明着（录制器停放着）", rear.declared.contains(recR));

        at(120_000);
        w.screenOn = true;
        w.screenOnAt = w.now;
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(recR), d.outputs);
        startOnly(d);
        assertWait(Why.STEP, REAR, round());
        at(120_400);
        opened(rear, d.outputs);
        frame(rear);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertEquals(set(recF), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, count(Move.APPLY));
        assertEquals("环视只在座舱放开的那一下关了一次、开回来一次", 1, count(Move.CLOSE, surround));
        assertEquals(1, count(Move.OPEN, surround));
    }

    /**
     * 熄屏时开的录：座舱的录制器熄屏时不建（设计 v3.1 C4），亮屏才建 —— 开它之前等录像输出的 2 秒从它又要录像输出的那一轮算，
     * 不是从开录那一刻（那早过了 2 秒，开了还得再改一次输出）。
     */
    @Test
    public void theRecordOutputWaitCountsFromWhenTheLaneIsWantedAgain() {
        w.screenOn = false;
        keptInDark();   // 哨兵模式布防：环视留着，接着录
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        rear.record = RecordPrep.PREPARING;
        front.record = RecordPrep.PREPARING;
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, rear.recordWantedSince);

        at(60_000);
        w.screenOn = true;
        Decision d = round();
        assertWait(Why.RECORD_OUTPUT, REAR, d);
        assertEquals(ChannelRules.RECORD_OUTPUT_WAIT_MS, d.ms);
        at(61_000);
        assertWait(Why.RECORD_OUTPUT, REAR, round());
        rear.record = RecordPrep.NONE;
        rear.declared.add(recR);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals("录制器建好了：带着它开", set(recR), d.outputs);
    }

    /** §7 解读 4：死输出排在开前面摘 —— 有一路待开、同时环视的预览死了，先摘环视的死预览。 */
    @Test
    public void deadOutputsAreRemovedBeforeOpening() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        surround.dead.add(pvS);
        surround.declared.remove(pvS);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.DEAD, d.why);
        assertEquals(set(pvS), d.removed);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    @Test
    public void addingAnOutputDropsTheSpareOnesAlongTheWay() {
        claim(Holder.PREVIEW);
        streaming(surround, Out.SINK);
        streaming(rear, pvR);
        streaming(front, pvF);
        surround.declared.add(pvS);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.MISSING, d.why);
        assertEquals(set(pvS), d.outputs);
        assertEquals(set(pvS), d.added);
        assertEquals(set(Out.SINK), d.removed);
    }

    /** 保持中的路被迫改输出（画布被收走）：留着的录像输出就是它的出帧口，照留，不换成出帧口。 */
    @Test
    public void aHeldLaneForcedToChangeKeepsItsRecordOutputInsteadOfTheSink() {
        // 停了录像、主界面在后台：环视在保持，会话里带着停放的录像输出和预览
        streaming(surround, pvS, recS);
        surround.declared.remove(pvS);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        surround.dead.add(pvS);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.DEAD, d.why);
        assertEquals(set(recS), d.outputs);
        assertEquals(set(pvS), d.removed);
        assertTrue(d.added.isEmpty());
    }

    @Test
    public void stopRecordingThenHoldThenCloseNeverTouchesTheSession() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);
        // 停录：录制器停放，录像输出留在会话里
        drop(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);
        at(10_000);
        assertEquals(Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, count(Move.APPLY));
        assertEquals(2, done.size());
    }

    /**
     * §7.6（项目所有者 2026-10-11）：主界面重建（换日夜模式、换语言）时预览画面由我们留着 —— 撤回声明，SurfaceTexture 不放；
     * 新界面接回同一个 SurfaceTexture，就是同一个输出。停录 → 重建 → 30 秒内再开录：通道零变动，录制器沿用。
     */
    @Test
    public void aRecreatedMainScreenThatReattachesThePreviewChangesNothing() {
        claim(Holder.PREVIEW);
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, pvS, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);

        drop(Holder.PREVIEW);
        for (Lane lane : w.lanes) {
            lane.declared.removeIf(out -> out.kind == Kind.PREVIEW);
        }
        assertEquals(Move.IDLE, round().move);

        at(2_000);
        claim(Holder.PREVIEW);
        surround.declared.add(pvS);
        rear.declared.add(pvR);
        front.declared.add(pvF);
        assertEquals(Move.IDLE, round().move);

        at(20_000);
        claim(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);
        assertTrue("通道零变动", done.isEmpty());
        assertEquals("录制器沿用", set(pvS, recS), surround.session);
    }

    /**
     * 画布真被系统收走（没留住），新画布和再开录在调度的同一轮里到：每一路只有一次改输出 —— 摘死的预览、加新的，
     * 录像输出（停放的录制器）照留，录制器沿用。
     */
    @Test
    public void aCollectedCanvasAndANewOneCostOneApplyPerLaneAndKeepTheRecorder() {
        claim(Holder.PREVIEW);
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, pvS, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);

        Out[] old = {pvS, pvR, pvF};
        Lane[] lanes = {surround, rear, front};
        for (int i = 0; i < lanes.length; i++) {
            lanes[i].dead.add(old[i]);
            lanes[i].declared.remove(old[i]);
            lanes[i].declared.add(new Out(Kind.PREVIEW, "new-" + lanes[i].cameraId));
        }
        claim(Holder.RECORDING);
        assertEquals(Move.IDLE, run().move);
        assertEquals(3, count(Move.APPLY));
        for (int i = 0; i < lanes.length; i++) {
            assertEquals(1, count(Move.APPLY, lanes[i]));
        }
        for (Decision d : done) {
            assertEquals(1, d.removed.size());
            assertEquals(Kind.PREVIEW, d.removed.iterator().next().kind);
        }
        assertTrue("录制器沿用", surround.session.contains(recS));
        assertTrue(rear.session.contains(recR));
        assertTrue(front.session.contains(recF));
    }

    // ================================================================= 录像输出

    @Test
    public void aColdStartWithRecordingOpensEveryLaneWithItsRecordOutput() {
        claim(Holder.RECORDING);
        planAll();
        surround.declared.add(recS);
        rear.declared.add(recR);
        front.declared.add(recF);
        assertEquals(Move.IDLE, run().move);
        assertEquals(3, count(Move.OPEN));
        assertEquals(0, count(Move.APPLY));
        assertEquals(set(recS), done.get(0).outputs);
        assertEquals(set(recR), done.get(1).outputs);
        assertEquals(set(recF), done.get(2).outputs);
    }

    @Test
    public void aRecordOutputNotReadyInTwoSecondsIsAddedLaterWithOneApply() {
        claim(Holder.RECORDING);
        planAll();
        surround.record = RecordPrep.PREPARING;
        rear.declared.add(recR);
        front.declared.add(recF);
        Decision d = round();
        assertWait(Why.RECORD_OUTPUT, SUR, d);
        assertEquals(Move.OPEN, d.pending);
        assertEquals(ChannelRules.RECORD_OUTPUT_WAIT_MS, d.ms);
        at(1_000);
        d = round();
        assertWait(Why.RECORD_OUTPUT, SUR, d);
        assertEquals("等的时候后面的路也不开", 1_000, d.ms);
        at(ChannelRules.RECORD_OUTPUT_WAIT_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals("2 秒还没好：先开", set(Out.SINK), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, run().move);
        assertEquals(set(recR), done.get(1).outputs);

        surround.record = RecordPrep.NONE;
        surround.declared.add(recS);
        d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(recS), d.outputs);
        assertEquals(set(Out.SINK), d.removed);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, count(Move.APPLY));
    }

    // ================================================================= 能用相机

    /** 前台服务是后台起的：不开、不重开、不计次数；主界面回到前台以后照常。 */
    @Test
    public void aServiceStartedInTheBackgroundNeitherReopensNorCounts() {
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        assertEquals(Move.IDLE, round().move);
        w.cameraUsable = false;
        ChannelRules.lossStarted(w, surround, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        at(500);
        serviceSays(surround, true, true);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        at(800);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.IDLE, d.move);
        assertEquals(SUR, d.deferredKey);
        at(60_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, surround.ladder.attempts());
        assertTrue(done.isEmpty());

        w.cameraUsable = true;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);
    }

    // ================================================================= 开失败、被拿走（§二.7）

    /**
     * 冷启动时车机还拿着相机 1（相机服务的缓存里没有它）：直接判 TAKEN，零次试开；
     * 它回来、说空闲了，安静满 3 秒，只开一次。
     */
    @Test
    public void aMissingCameraAtColdStartIsTakenWithoutTryingAndOpensOnceWhenFree() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        assertEquals(Move.IDLE, run().move);
        assertEquals(0, count(Move.OPEN, rear));
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);
        assertTrue(ChannelRules.askDue(w));
        ChannelRules.asked(w);

        at(60_000);
        serviceSays(rear, true, false);
        Decision d = round();
        Event released = event(Event.Type.RELEASED);
        assertNotNull(released);
        assertEquals(60_000, released.ms);
        assertWait(Why.QUIET, REAR, d);
        assertEquals(Move.OPEN, d.pending);
        at(60_000 + ChannelRules.QUIET_MS - 1);
        assertWait(Why.QUIET, REAR, round());
        at(60_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, count(Move.OPEN, rear));
    }

    /**
     * 主动认只认别人的：相机服务说「被占用（我们开着）」、而我们刚关完它（0.5 秒以内，为这一关说的「空闲」还在路上），不算被拿走；
     * 关完 0.5 秒还被占着，只能是别人 —— 那个「我们开着」只记说的那一刻（审查 2026-10-11）。
     */
    @Test
    public void recognizesOnlyWhatOthersHold() {
        rear.deviceSince = w.now - 100;   // 我们刚关完它
        serviceSays(rear, false, true);
        serviceSays(front, false, false);
        w.lastOthersChangeAt = 0;
        round();
        assertNull("刚关完：那一句还算我们的", rear.mark);
        assertEquals(CameraTaken.Verdict.TAKEN, front.mark);
        Event recognized = event(Event.Type.RECOGNIZED);
        assertEquals(Collections.singletonList(FRONT), recognized.keys);
        assertFalse(recognized.absent);

        at(ChannelRules.OWN_WORD_GRACE_MS - 100 - 1);
        round();
        assertNull(rear.mark);
        at(ChannelRules.OWN_WORD_GRACE_MS - 100);
        round();
        assertEquals("关完 0.5 秒还被占着：别人拿着", CameraTaken.Verdict.TAKEN, rear.mark);
        assertEquals(Collections.singletonList(REAR), event(Event.Type.RECOGNIZED).keys);
    }

    /**
     * 管线在同一个进程里重建（配置里开关了一路、换车型、退出后马上又开）：新的一份从没碰过这一路，读到的「被占用（我们开着）」
     * 是旧的那一份开的时候记下的。旧的那一份那一轮还没结束（账本：被一轮占着）—— 算我们的，不开它、别的路照开；那一轮结束了，
     * 账本记下我们关完的那一刻（{@link Lane#ourCloseAt}），之后 0.5 秒以内还算我们的（为这一关说的「空闲」可能还在路上）；
     * 过了还被占着（原厂 360 拿着环视）—— 别人拿着，一次也不试开（§二.7，审查 2026-10-11；第 2 步：以前看「另一份管线还在关」那扇窗）。
     */
    @Test
    public void anOldOursWordIsOursOnlyWhileItsRoundLivesAndJustAfterItsClose() {
        claim(Holder.PREVIEW);
        declarePreviews();
        serviceSays(surround, false, true);
        surround.held = true;
        Decision d = round();
        assertNull("那一轮还没结束：那一句还算我们的", surround.mark);
        assertEquals("被一轮占着的跳过，别的路照开", Collections.singletonList(REAR), d.keys);
        perform(d);

        at(1_000);
        surround.held = false;
        surround.ourCloseAt = w.now;   // 旧的那一份那一轮关完、结束了
        round();
        assertNull("刚关完：还算我们的", surround.mark);
        at(1_000 + ChannelRules.OWN_WORD_GRACE_MS - 1);
        round();
        assertNull(surround.mark);
        at(1_000 + ChannelRules.OWN_WORD_GRACE_MS);
        assertEquals(Move.IDLE, run().move);
        assertEquals("关完 0.5 秒还被占着：别人拿着", CameraTaken.Verdict.TAKEN, surround.mark);
        assertEquals("一次也不试开", 0, count(Move.OPEN, surround));
        assertEquals("被拿着的跳过，不挡后面", 2, count(Move.OPEN));
        assertTrue(ChannelRules.askDue(w));
    }

    /**
     * 排着的那一份管线每一轮只做认、放两步（{@link ChannelRules#settleMarks}，第 2 步审查 2026-10-30）：被别的程序拿着的、不在的认出来，
     * 空出来的放开 —— 环视留不留不判、前座舱不去掉、登记表的变化不收，这几样留给换上以后它自己那一轮（{@link ChannelRules#settle}）。
     */
    @Test
    public void aPendingPipelineOnlyRecognizesAndReleases() {
        claim(Holder.RECORDING);
        planAll();
        w.frontCabinOneShot = true;
        w.surroundInDark = ChannelRules.SurroundInDark.LIT;
        w.screenOn = false;   // 通道刚算黑：settle 会判环视留不留、去掉前座舱
        w.sentry = 2;
        serviceSays(rear, false, false);   // 车机拿着后座舱
        front.absent = true;   // 前座舱不在
        List<Event> marks = ChannelRules.settleMarks(w);
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);
        assertEquals(CameraTaken.Verdict.TAKEN, front.mark);
        assertEquals(2, marks.size());
        for (Event event : marks) {
            assertEquals(Event.Type.RECOGNIZED, event.type);
        }
        assertEquals("环视留不留不判", ChannelRules.SurroundInDark.LIT, w.surroundInDark);
        assertNull("上一次读数不记", w.lastSentry);
        assertFalse("前座舱不去掉", w.frontCabinRetired);
        assertTrue("登记表的变化不收", w.changed.contains(Holder.RECORDING));
        assertEquals("不判、不记保持", 0, rear.unwantedSince);

        at(1_000);
        serviceSays(rear, true, false);   // 车机放开了后座舱
        marks = ChannelRules.settleMarks(w);
        assertNull(rear.mark);
        assertEquals(1, marks.size());
        assertEquals(Event.Type.RELEASED, marks.get(0).type);
        assertEquals(CameraTaken.Verdict.TAKEN, front.mark);

        events = ChannelRules.settle(w);   // 换上以后它自己那一轮：那几样照判
        assertEquals(ChannelRules.SurroundInDark.KEPT, w.surroundInDark);
        assertTrue(w.frontCabinRetired);
        assertTrue(w.changed.isEmpty());
    }

    /**
     * 这一份管线要换下去（管线重建，{@link World#retireSince}）：和退出一样只关、按次序关 —— 手上那一步做完，环视单独先关，
     * 座舱再一起关；关的原因是「管线重建」；不开、不改、不救、不问；退出、车机要睡时原因照写它们自己的。
     */
    @Test
    public void aRetiringPipelineClosesInOrderForARebuild() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        w.retireSince = w.now;
        assertTrue(ChannelRules.winding(w));
        assertFalse(ChannelRules.shouldOpen(w, rear));
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.REBUILD, d.why);
        assertEquals("环视单独先关", Collections.singletonList(SUR), d.keys);
        assertEquals(ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        perform(d);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.REBUILD, d.why);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);

        w.exitSince = w.now;
        streaming(surround, pvS);
        assertEquals("退出时照写退出", Why.EXIT, round().why);
    }

    /** 1 是 TAKEN 时开 2，报 MAX_CAMERAS_IN_USE：判 BLOCKED、不计次数；1 放开了，两路一起放开，先开环视再开后座舱。 */
    @Test
    public void maxCamerasWhileAnotherIsTakenIsBlockedAndNotCounted() {
        claim(Holder.PREVIEW);
        declarePreviews();
        serviceSays(rear, false, false);
        w.lastOthersChangeAt = 0;
        Decision d = round();
        assertNotNull(event(Event.Type.RECOGNIZED));
        assertEquals(Collections.singletonList(SUR), d.keys);
        startOnly(d);
        serviceSays(surround, false, true);
        at(400);
        failedToOpen(surround, CameraTaken.Reason.ofAccess(CameraAccessException.MAX_CAMERAS_IN_USE));
        serviceSays(surround, true, true);
        assertWait(Why.JUDGING, SUR, round());

        at(700);
        d = round();
        Event judged = event(Event.Type.JUDGED);
        assertEquals(CameraTaken.Verdict.BLOCKED, judged.verdict);
        assertEquals(CameraTaken.Verdict.BLOCKED, surround.mark);
        assertEquals("不计次数", 0, surround.ladder.attempts());
        assertEquals("被拿着的跳过，不挡后面", Collections.singletonList(FRONT), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);

        at(10_000);
        serviceSays(rear, true, false);
        d = round();
        assertEquals(2, all(Event.Type.RELEASED).size());
        assertWait(Why.QUIET, SUR, d);
        at(10_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    /** 开失败报错误 4：普通故障 —— 第一次开就算第 1 次，之后 12 秒一次，3 次歇 60 秒，3 轮停手。 */
    @Test
    public void error4RetriesEveryTwelveSecondsAndStopsAfterThreeRounds() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        List<Long> tries = new ArrayList<>();
        List<Long> gaveUp = new ArrayList<>();
        List<Long> stopped = new ArrayList<>();
        for (long t = 0; t <= 600_000; t += ChannelRules.TICK_MS) {
            at(t);
            Decision d = round();
            for (Event e : d.events) {
                if (e.type == Event.Type.GAVE_UP) {
                    gaveUp.add(t);
                } else if (e.type == Event.Type.STOPPED) {
                    stopped.add(t);
                }
            }
            if (d.move == Move.OPEN || d.move == Move.RESCUE) {
                startOnly(d);
                tries.add(t);
                failedToOpen(surround, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
                serviceSays(surround, true, true);
            }
        }
        assertEquals(Arrays.asList(0L, 12_000L, 24_000L, 96_000L, 108_000L, 120_000L,
                192_000L, 204_000L, 216_000L), tries);
        assertEquals(Arrays.asList(36_000L, 132_000L), gaveUp);
        assertEquals(Collections.singletonList(228_000L), stopped);
        assertTrue(surround.ladder.stopped());
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(surround));
        assertTrue(ChannelRules.excludedFromPhoto(w, surround));
    }

    /**
     * 报错误 3 / CAMERA_DISABLED（现在用不了相机），主界面不在前（前台服务是主界面在前时起的）：不计次数，等主界面回到前台。
     * 主界面回到前台、又退到后台以后开的那一次再报，照样等。
     */
    @Test
    public void cameraDisabledIsNotCountedAndWaitsForTheMainScreen() {
        w.mainForeground = false;
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        Decision d = round();
        startOnly(d);
        at(300);
        failedToOpen(surround, CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED));
        at(600);
        d = round();
        assertEquals(CameraTaken.Verdict.UNUSABLE, event(Event.Type.JUDGED).verdict);
        assertEquals(ChannelRules.UnusableThen.WAIT_FOR_MAIN, event(Event.Type.JUDGED).unusableThen);
        assertEquals(Move.IDLE, d.move);
        assertEquals(SUR, d.deferredKey);
        assertEquals(0, surround.ladder.attempts());
        at(60_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, done.size());

        ChannelRules.mainCameForward(w);
        d = round();
        assertEquals(Move.OPEN, d.move);
        startOnly(d);
        at(60_300);
        failedToOpen(surround, CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISABLED));
        at(60_600);
        d = round();
        assertEquals(CameraTaken.Verdict.UNUSABLE, event(Event.Type.JUDGED).verdict);
        assertEquals(ChannelRules.UnusableThen.WAIT_FOR_MAIN, event(Event.Type.JUDGED).unusableThen);
        assertEquals(Move.IDLE, d.move);
        assertEquals(0, surround.ladder.attempts());
    }

    /**
     * 开的时候报「用不了相机」，而主界面从开之前到判的时候一直在前（隐私开关、策略禁用那种）：没什么可等，按普通故障走梯子 ——
     * 算第 1 次，12 秒后重开（审查 2026-10-11：以前记下「用不了」、只等主界面「回到前台」那一下，主界面一直在前，就一直不开、
     * 也不上梯子）。
     */
    @Test
    public void cameraDisabledWithTheMainScreenInFrontAllAlongGoesOnTheLadder() {
        w.mainForeground = true;
        w.mainForwardAt = T0 - 60_000;
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        Decision d = round();
        startOnly(d);
        at(300);
        failedToOpen(surround, CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISABLED));
        at(600);
        d = round();
        Event judged = event(Event.Type.JUDGED);
        assertEquals(CameraTaken.Verdict.UNUSABLE, judged.verdict);
        assertEquals(ChannelRules.UnusableThen.LADDER, judged.unusableThen);
        assertEquals("不等主界面", 0, w.unusableSince);
        assertNull(d.deferredKey);
        assertEquals("算救援的第 1 次", 1, surround.ladder.attempts());
        assertEquals(Move.IDLE, d.move);
        at(CameraLiveness.RETRY_GAP_MS - 1);
        assertEquals(Move.IDLE, round().move);
        at(CameraLiveness.RETRY_GAP_MS);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(2, d.attempt);
    }

    /**
     * 丢了、报「用不了相机」（错误 3，后台），关的那几秒里主界面回到了前台：判的时候要等的已经等到了 —— 不计次数、不记「用不了」，
     * 当场重新开（审查 2026-10-11：以前记下「用不了」，可主界面「回到前台」那一下已经过去了，一直不开）。
     */
    @Test
    public void aLossJudgedUnusableAfterTheMainScreenCameBackIsNotWaitedFor() {
        w.mainForeground = false;
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        assertEquals(Move.IDLE, round().move);
        ChannelRules.lossStarted(w, surround,
                CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED));
        surround.busySince = w.now;
        at(2_000);
        w.mainForeground = true;
        ChannelRules.mainCameForward(w);
        assertWait(Why.LOSING, SUR, round());
        at(5_000);
        serviceSays(surround, true, true);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        at(5_300);
        Decision d = round();
        Event judged = event(Event.Type.JUDGED);
        assertEquals(CameraTaken.Verdict.UNUSABLE, judged.verdict);
        assertEquals(ChannelRules.UnusableThen.MAIN_CAME_BACK, judged.unusableThen);
        assertEquals(0, w.unusableSince);
        assertEquals("不计次数", 0, surround.ladder.attempts());
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    /**
     * 开那一步满 15 秒没出画面、这一轮那一次 openCamera 一直没回音：算救援第 1 次，之后不救 —— 这时重开相机层只会合并掉，梯子白算一次
     * （审查 2026-10-11）。开没返回照「关没返回」同一条（项目所有者 2026-10-29 Q1）：相机层的在途过了 60 秒（busySince 归 0，只管别的路
     * 等多久）也不救、不计次数，10 分钟以后也一样；满 60 秒录像那一侧不再算它在起来。它回音了（开好了）就照常。
     * 以前在途封顶一到就重开，算第 2 次（相机层作废那一轮、换一条相机线程开，2026-10-29 删）。
     */
    @Test
    public void anOpeningLaneWhoseOpenNeverAnswersIsNeverRescued() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        startOnly(d);
        surround.progressAgeMs = 20_000;
        at(ChannelRules.STEP_MAX_MS);
        assertEquals(Move.IDLE, round().move);
        assertEquals("开那一步到点：算第 1 次", 1, surround.ladder.attempts());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertEquals("这一轮在开：不救", Move.IDLE, round().move);
        assertFalse(ChannelRules.heldByClose(w, surround));
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        surround.busySince = 0;
        assertEquals("在途过了 60 秒：照样不救", Move.IDLE, round().move);
        assertTrue(ChannelRules.heldByClose(w, surround));
        assertTrue(ChannelRules.cannotOpen(w, surround));
        at(10 * 60_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals("不计次数", 1, surround.ladder.attempts());
        assertFalse(surround.ladder.gaveUp());

        opened(surround, set(mirror));   // 它终于回音了
        frame(surround);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.streaming(surround));
        assertFalse(ChannelRules.heldByClose(w, surround));
    }

    /** 一个卡死的关最多占住通道 60 秒，之后按当时的状态判（大纲 §6 风险 5）。 */
    @Test
    public void aStuckLossStopsBlockingAfterSixtySecondsAndIsJudgedThen() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        front.declared.add(pvF);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        assertWait(Why.LOSING, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertWait(Why.LOSING, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.TAKEN, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
    }

    // ================================================================= 车机要睡、退出

    /** 车机要睡：手上那一步最多再等 1 秒，然后按次序关（环视单独先关）；亮屏、这项事实清掉以后照常开。 */
    @Test
    public void headUnitSleepWaitsAtMostOneSecondThenClosesInOrder() {
        claim(Holder.PREVIEW);
        declarePreviews();
        perform(round());
        Decision d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);

        at(400);
        w.sleepSince = w.now;
        d = round();
        assertWait(Why.STEP, REAR, d);
        assertEquals(ChannelRules.SLEEP_GRACE_MS, d.ms);
        at(400 + ChannelRules.SLEEP_GRACE_MS - 1);
        assertEquals(Move.WAIT, round().move);
        at(400 + ChannelRules.SLEEP_GRACE_MS);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SLEEP, d.why);
        startOnly(d);
        at(1_500);
        assertWait(Why.SURROUND_CLOSING, SUR, round());
        at(1_600);
        closed(surround);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("开到一半的后座舱一起关", Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.allClosed(w));

        w.sleepSince = 0;
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    /** 退出：等手上那一步做完（没有 1 秒的宽限），再按次序关。 */
    @Test
    public void exitWaitsForTheStepInHandThenCloses() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision open = round();
        startOnly(open);
        at(100);
        w.exitSince = w.now;
        assertWait(Why.STEP, SUR, round());
        at(1_500);
        assertWait(Why.STEP, SUR, round());
        at(2_000);
        opened(surround, open.outputs);
        frame(surround);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.EXIT, d.why);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertFalse(ChannelRules.askDue(w));
    }

    @Test
    public void deadOutputsCauseNoApplyWhenSleepingOrExiting() {
        claim(Holder.PREVIEW);
        streamingAll();
        surround.dead.add(pvS);
        w.sleepSince = w.now;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        w.sleepSince = 0;
        w.exitSince = w.now;
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.EXIT, d.why);
    }

    // ================================================================= 救援（§二.5）

    @Test
    public void rescuesTheSurroundFirst() {
        claim(Holder.PREVIEW);
        streamingAll();
        rear.progressAgeMs = 9_000;
        surround.progressAgeMs = 9_000;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.NO_PROGRESS, d.why);
    }

    /** 第一次救、本会话出过画面才重建会话（便宜）；重建没用，12 秒后第 2 次就重开。 */
    @Test
    public void theFirstRescueRebuildsOnlyIfTheSessionHadStreamed() {
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        surround.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REBUILD, d.rescue);
        assertEquals(1, d.attempt);
        assertEquals(set(mirror), d.outputs);
        startOnly(d);
        at(500);
        configured(surround, d.outputs);
        at(ChannelRules.STEP_MAX_MS);
        surround.progressAgeMs = ChannelRules.STEP_MAX_MS - 500;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(2, d.attempt);
    }

    /** 配好了却一帧都没出过：第一次救就重开（重建没有东西可排空，2026-10-08 实测环视一次 4–13 秒还报错）。 */
    @Test
    public void aSessionThatNeverStreamedIsReopenedAtTheFirstRescue() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        surround.device = Device.OPEN;
        surround.session.add(mirror);
        surround.sessionSince = w.now - 9_000;
        surround.progressAgeMs = 9_000;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);

        // 缺了要的输出也一样：不改输出，第一次救就重开、带上它（2.11.4 第 1d 项）
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        surround.declared.add(recS);
        at(CameraLiveness.RETRY_GAP_MS);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(set(mirror, recS), d.outputs);
        assertEquals(0, count(Move.APPLY));
    }

    // ================================================================= 本会话没出过画面的不改输出（2.11.4 第 1d 项）

    /**
     * 10-13 20:10 的回放（项目所有者 2026-10-14 批准：本会话没出过画面的，不改输出、不摘死输出；由救援重开时带上现在要的输出）：
     * 后座舱只为录像开（主界面在后台），配好了、一帧不出；这 15 秒里主界面回到前台，要预览了。到点先开排在后面的前座舱，
     * 再救后座舱：第 2 次、重开、带着预览和录像 —— 不在一帧不出的会话上改输出（那一下卡在 waitUntilIdle 4.8 秒、报错 4、
     * 再关 6.2 秒，2.11.2 实测；10-12 20:04 环视、哨兵关着时也一样）。
     */
    @Test
    public void replay1013_aSessionThatNeverFramedIsReopenedWithTheOutputsWantedNowNotReconfigured() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        rear.declared.add(recR);
        front.declared.add(recF);
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(recR), d.outputs);
        startOnly(d);
        at(100);
        opened(rear, d.outputs);   // 配好了，一帧不出
        at(6_700);
        claim(Holder.PREVIEW);     // 主界面回到前台：两路座舱要预览（环视的画布这里不声明，免得它也改一次输出）
        rear.declared.add(pvR);
        front.declared.add(pvF);
        assertWait(Why.STEP, REAR, round());

        rear.progressAgeMs = ChannelRules.STEP_MAX_MS - 100;   // 配好以来一点进展都没有
        at(ChannelRules.STEP_MAX_MS);
        d = round();
        assertEquals("先往下走：排在后面的前座舱先开", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertEquals(set(pvF, recF), d.outputs);
        assertEquals("开那一步到点：后座舱算第 1 次", 1, rear.ladder.attempts());
        perform(d);

        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(2, d.attempt);
        assertEquals("重开带上现在要的输出", set(pvR, recR), d.outputs);
        assertEquals("一帧不出的会话不改输出", 0, count(Move.APPLY));
    }

    /** 本会话没出过画面的那一路，会话里的死输出也不摘：留给救援的重开丢掉（重开只带活着的、要的）。别的路照常。 */
    @Test
    public void aDeadOutputOnASessionThatNeverFramedIsLeftForTheReopenToDrop() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        Decision d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);
        at(100);
        opened(rear, d.outputs);   // 配好了，一帧不出
        rear.progressAgeMs = ChannelRules.STEP_MAX_MS - 100;
        at(ChannelRules.STEP_MAX_MS);
        // 主界面换了一块画布：旧的那一块被系统收走（会话里那个死了），新的声明了
        rear.dead.add(pvR);
        rear.declared.remove(pvR);
        Out pvR2 = new Out(Kind.PREVIEW, "preview-1b");
        rear.declared.add(pvR2);
        d = round();
        assertEquals("不摘死输出，直接救", Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals("重开带新画布、丢掉死的", set(pvR2), d.outputs);
        assertEquals(0, count(Move.APPLY));
    }

    /** 第一帧晚到了（开那一步已经到点）：本会话出过画面了，缺的输出照常由改输出加上，不重开。 */
    @Test
    public void aLateFirstFrameMakesTheSessionReconfigurableAgain() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.device = Device.OPEN;
        rear.deviceSince = w.now;
        configured(rear, set(Out.SINK));   // 只为拍照开着的那种：带出帧口，配好了，还没出过画面
        rear.declared.add(pvR);
        assertEquals("没出过画面：不改输出，也还不到救的时候", Move.IDLE, round().move);
        at(3_000);
        frame(rear);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.MISSING, d.why);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(pvR), d.added);
        assertEquals(set(Out.SINK), d.removed);
    }

    // ================================================================= 在关的设备是在途（2.11.4 第 1c 项）

    /**
     * 救援的重开（第 2b 步：先下达关）15 秒到点、旧设备还在关（重开的关那一半）：照样是在途，别的路一步都不动（项目所有者 2026-10-14 批准：
     * 在关旧设备的相机是在途，不管它属于哪一步，到点也不放过，封顶 60 秒 —— 和丢了之后的那一次关同一条规矩）。10-13 19:55:29
     * 那一次关了 14.0 秒，离到点只差 1 秒；2.10.x 的强制重开关到过 21.4 秒。这一步到点了，开的那一半不再下达；关完了这一路关着，
     * 交给平常的规矩：排着的前座舱照开的次序先开，后座舱照救援梯子再救。
     */
    @Test
    public void aCameraStillClosingItsOldDeviceIsInFlightAfterItsStepTimesOut() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        w.recordPlan.add(REAR);
        streaming(surround, recS);
        rear.declared.add(recR);
        rear.device = Device.OPEN;
        rear.deviceSince = w.now;
        configured(rear, set(recR));   // 配好了，一帧不出
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals("这一路手上有设备：先下达关", ChannelRules.Half.CLOSE, d.half);
        startOnly(d);
        assertEquals(Device.CLOSING, rear.device);
        assertTrue(rear.reopenClose);

        // 这期间前座舱也要了：它排着
        w.recordPlan.add(FRONT);
        front.declared.add(recF);
        assertWait(Why.STEP, REAR, round());
        at(ChannelRules.STEP_MAX_MS);
        d = round();
        assertEquals(Outcome.NONE, ChannelRules.outcome(w));
        assertWait(Why.BUSY, REAR, d);
        assertEquals(ChannelRules.IN_FLIGHT_MAX_MS - ChannelRules.STEP_MAX_MS, d.ms);
        at(ChannelRules.STEP_MAX_MS + 5_000);
        assertWait(Why.BUSY, REAR, round());
        assertEquals("旧设备还在关：前座舱不开", 1, done.size());

        // 旧设备关完了（20.0 秒）：这一步早结束了，开的那一半不再下达 —— 排着的前座舱照开的次序先开
        at(20_000);
        closed(rear);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        perform(d);
        d = round();
        assertEquals("后座舱照梯子再救（第 2 次）：关着的一路照开那样开", Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Why.DOWN, d.why);
        assertNull(d.half);
        assertEquals(2, d.attempt);
    }

    /**
     * 在关的设备一直关不完（上一轮晚到的设备在就地关、卡住了：那一轮一直不结束，账本上后座舱被它占着）：别的路最多等 60 秒（在途封顶），
     * 之后照常动 —— 前座舱 60 秒时开；后座舱那一轮结束之前不开、不救、不计次数，10 分钟以后也一样（项目所有者 2026-10-28、10-29：
     * 我们上一次关它还没返回，谁都不开它，不设时限）。60 秒以内录像那一侧照旧算它在起来，满 60 秒不再算（{@link ChannelRules#cannotOpen}）。
     * 那一轮结束了照常开。以前 60 秒一到后座舱和别的路一起照常开（一台相机两个句柄）。
     */
    @Test
    public void anOldDeviceThatNeverFinishesClosingHoldsOnlyItsOwnLane() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        front.declared.add(pvF);
        rear.declared.add(pvR);
        rear.held = true;   // 晚到的设备在就地关（上一轮的相机线程上）：那一轮还没结束
        rear.outstandingSince = w.now;
        rear.closingSince = w.now;
        assertWait(Why.BUSY, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertWait(Why.BUSY, REAR, round());
        assertFalse("60 秒以内照旧算在起来", ChannelRules.cannotOpen(w, rear));
        assertFalse(ChannelRules.heldByClose(w, rear));
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        Decision d = round();
        assertEquals("别的路照常：后座舱跳过，开前座舱", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertTrue(ChannelRules.heldByClose(w, rear));
        assertTrue("录像那一侧不再算它在起来", ChannelRules.cannotOpen(w, rear));
        perform(d);

        at(10 * 60_000);
        assertEquals(Move.IDLE, run().move);
        assertEquals("10 分钟以后也不开它", 0, count(Move.OPEN, rear));
        assertEquals("不救", 0, count(Move.RESCUE, rear));
        assertEquals("不计次数", 0, rear.ladder.attempts());
        assertTrue(ChannelRules.heldByClose(w, rear));
        drop(Holder.PREVIEW);
        assertFalse("没人要了：不算在等它", ChannelRules.heldByClose(w, rear));
        claim(Holder.PREVIEW);

        rear.held = false;   // 那一轮结束了
        rear.outstandingSince = 0;
        rear.closingSince = 0;
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertFalse(ChannelRules.cannotOpen(w, rear));
    }

    /**
     * 同 {@link #aCameraStillClosingItsOldDeviceIsInFlightAfterItsStepTimesOut}，旧设备那一次关（重开的关那一半）一直没返回（项目所有者 2026-10-28）：
     * 别的路等满 60 秒照常动（前座舱开）；后座舱被它自己那一轮占着，返回之前不再救、不计次数（3 分钟以后也一样）；
     * 关返回了，后座舱照救援梯子再救（关着的一路照开那样开），开好出了画面。
     */
    @Test
    public void aCameraWhoseOldDeviceNeverFinishesClosingHoldsOnlyItself() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        w.recordPlan.add(REAR);
        streaming(surround, recS);
        rear.declared.add(recR);
        rear.device = Device.OPEN;
        rear.deviceSince = w.now;
        configured(rear, set(recR));   // 配好了，一帧不出
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        int attempts = rear.ladder.attempts();
        startOnly(d);   // 关的那一半：这一轮摘下了、在关（账本），返回之前谁都不开它
        w.recordPlan.add(FRONT);
        front.declared.add(recF);

        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertWait(Why.BUSY, REAR, round());
        assertEquals("旧设备还在关：前座舱不开", 1, done.size());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        d = round();
        assertEquals("满 60 秒：别的路照常", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        perform(d);
        assertTrue(ChannelRules.heldByClose(w, rear));
        assertTrue("关了、还没结束的那一轮：被它占着", ChannelRules.camHeld(rear));

        rear.busySince = 0;   // 相机层那一下关的在途也过了 60 秒（只管别的路等多久）
        rear.progressAgeMs = 3 * 60_000;
        at(3 * 60_000);
        assertEquals(Move.IDLE, run().move);
        assertEquals("不再救", 1, count(Move.RESCUE, rear));
        assertEquals("不计次数", attempts, rear.ladder.attempts());
        assertFalse(rear.ladder.gaveUp());

        // 关返回了：后座舱关着，照救援梯子再救（关着的一路照开那样开），开好出了画面
        closed(rear);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertNull("关着的一路的重开不分两半", d.half);
        assertEquals(attempts + 1, d.attempt);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.streaming(rear));
        assertFalse(ChannelRules.heldByClose(w, rear));
    }

    // ================================================================= 救援重开分两半（第 2b 步，项目所有者 2026-10-29）

    /** 后座舱开着、配好了一帧不出、8 秒没进展：第一次救就重开（这一路手上有设备）。返回规则出的那一步，还没照做。 */
    private Decision stuckRearReopen() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        rear.device = Device.OPEN;
        rear.deviceSince = w.now;
        configured(rear, set(pvR));   // 配好了，一帧不出
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(Collections.singletonList(REAR), d.keys);
        return d;
    }

    /**
     * 手上有设备的一路，重开分两半：这一步先下达关（这一路记成在关）；关完的那一声到了、账本上那一轮还没结束 —— 不开；那一轮结束了，
     * 离下达关还不到 300 ms 就等到那一刻（关得比 300 ms 快）；然后同一步里下达开，带着这一步的输出，只这一次。这一步的开始照旧，
     * 出了第一帧才算完。
     */
    @Test
    public void aReopenClosesFirstThenOpensInTheSameStepOnceTheRoundEndedAndTheGapPassed() {
        Decision d = stuckRearReopen();
        assertEquals(Why.NO_PROGRESS, d.why);
        assertEquals("手上有设备：先下达关", ChannelRules.Half.CLOSE, d.half);
        startOnly(d);
        assertEquals(Device.CLOSING, rear.device);
        assertTrue(w.step.split);
        assertTrue(ChannelRules.reopenPending(w, rear));
        assertWait(Why.STEP, REAR, round());

        at(100);
        rear.device = Device.CLOSED;   // 关完的那一声到了，账本上那一轮还没结束（还有一台在关）
        rear.deviceSince = w.now;
        assertWait(Why.STEP, REAR, round());
        closed(rear);                  // 那一轮结束了
        Decision wait = round();
        assertWait(Why.STEP, REAR, wait);
        assertEquals("关完了，等到离下达关满 300 ms", ChannelRules.REOPEN_GAP_MS - 100, wait.ms);
        at(ChannelRules.REOPEN_GAP_MS - 1);
        assertWait(Why.STEP, REAR, round());

        at(ChannelRules.REOPEN_GAP_MS);
        Decision open = round();
        assertEquals(Move.RESCUE, open.move);
        assertEquals(ChannelRules.Half.OPEN, open.half);
        assertEquals(Rescue.REOPEN, open.rescue);
        assertEquals("带着这一步的输出", d.outputs, open.outputs);
        startOnly(open);
        assertEquals("不另起一步", T0, w.step.startedAt);
        assertEquals(T0 + ChannelRules.REOPEN_GAP_MS, w.step.openedAt);
        assertFalse(ChannelRules.reopenPending(w, rear));
        assertEquals(1, count(Move.RESCUE, rear));
        assertWait(Why.STEP, REAR, round());
        at(1_000);
        assertWait(Why.STEP, REAR, round());
        assertEquals("开的那一半只下达这一次", Device.OPENING, rear.device);

        at(1_200);
        opened(rear, open.outputs);
        frame(rear);
        assertEquals(Outcome.DONE, ChannelRules.outcome(w));
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.streaming(rear));
    }

    /** 关得比 300 ms 慢（5 秒）：关完、那一轮结束的这一轮就下达开 —— 和以前相机层「关完旧的，满 300 ms 才开新的」同一个时刻。 */
    @Test
    public void aSlowCloseHalfIsFollowedByTheOpenHalfAtOnce() {
        Decision d = stuckRearReopen();
        startOnly(d);
        at(5_000);
        assertWait(Why.STEP, REAR, round());
        closed(rear);
        Decision open = round();
        assertEquals(ChannelRules.Half.OPEN, open.half);
        startOnly(open);
        assertEquals(T0 + 5_000, w.step.openedAt);
        assertEquals(T0, w.step.startedAt);
        at(ChannelRules.STEP_MAX_MS);
        assertEquals("封顶照旧从下达关算", Outcome.TIMED_OUT, ChannelRules.outcome(w));
    }

    /**
     * 关的那一半关完、开的那一半下达之前，该开的样子不在了：开的那一半不再下达，这一步当场结束（不等到点、不等那 300 ms），这一路关着，
     * 交给平常的规矩 —— 退出、管线换下去、车机要睡、用不了相机、屏幕不知道黑没黑、没人要了、黑着没在开车的座舱。
     */
    @Test
    public void theOpenHalfIsDroppedOnExit() {
        assertOpenHalfDropped(() -> w.exitSince = w.now, Why.EXIT);
        assertEquals("退出：照次序关别的路（环视先）", Move.CLOSE, lastDecision.move);
        assertEquals(Collections.singletonList(SUR), lastDecision.keys);
    }

    @Test
    public void theOpenHalfIsDroppedWhenThePipelineRetires() {
        assertOpenHalfDropped(() -> w.retireSince = w.now, Why.REBUILD);
        assertEquals(Move.CLOSE, lastDecision.move);
    }

    @Test
    public void theOpenHalfIsDroppedWhenTheHeadUnitSleeps() {
        assertOpenHalfDropped(() -> w.sleepSince = w.now, Why.SLEEP);
        assertEquals(Move.CLOSE, lastDecision.move);
    }

    @Test
    public void theOpenHalfIsDroppedWhileTheCameraIsUnusable() {
        assertOpenHalfDropped(() -> w.cameraUsable = false, Why.UNUSABLE);
        assertEquals("用不了相机：先不开", REAR, lastDecision.deferredKey);
        w.cameraUsable = true;
        at(CameraLiveness.RETRY_GAP_MS);
        Decision d = round();
        assertEquals("能用了：照救援梯子再救（关着的一路照开那样开）", Move.RESCUE, d.move);
        assertNull(d.half);
        assertEquals(Why.DOWN, d.why);
    }

    @Test
    public void theOpenHalfIsDroppedWhileTheScreenIsUnknown() {
        assertOpenHalfDropped(() -> w.screenKnown = false, Why.SCREEN_UNKNOWN);
    }

    @Test
    public void theOpenHalfIsDroppedWhenTheLaneIsNoLongerWanted() {
        assertOpenHalfDropped(() -> drop(Holder.PREVIEW), Why.UNWANTED);
        assertEquals("没人要了：梯子归零", 0, rear.ladder.attempts());
    }

    @Test
    public void theOpenHalfOfADarkCabinIsDroppedUnlessDriving() {
        assertOpenHalfDropped(() -> w.screenDark = true, Why.DARK_RELEASE);
        assertTrue("关着的座舱在黑屏里放开，不开", ChannelRules.releasedByDark(w, rear));
    }

    /** R8 那一道不管在开车时：黑着、在开车，开的那一半照下；两半之间那一下关着也不算熄屏放开。 */
    @Test
    public void whileDrivingTheOpenHalfOfADarkCabinIsStillIssued() {
        Decision d = stuckRearReopen();
        startOnly(d);
        w.driving = true;
        w.screenDark = true;   // 黑了还不到 30 秒
        at(100);
        closed(rear);
        assertFalse("两半之间：还在我们的一步里", ChannelRules.releasedByDark(w, rear));
        assertTrue(ChannelRules.wanted(w, rear));
        at(ChannelRules.REOPEN_GAP_MS);
        Decision open = round();
        assertEquals(ChannelRules.Half.OPEN, open.half);
    }

    /** 最近一次 {@link #assertOpenHalfDropped} 里，这一步结束之后规则出的那一步。 */
    private Decision lastDecision;

    /**
     * 关的那一半 50 ms 就关完了，{@code change} 之后：开的那一半不再下达（{@code why}），这一步当场结束，这一路关着，梯子上那一次照旧算过
     * （没人要了的照旧归零）。记下这一步结束之后的那一步（{@link #lastDecision}）。
     */
    private void assertOpenHalfDropped(Runnable change, Why why) {
        Decision d = stuckRearReopen();
        startOnly(d);
        at(50);
        closed(rear);
        change.run();
        assertEquals(Outcome.DROPPED, ChannelRules.outcome(w));
        assertEquals(why, ChannelRules.reopenBlocked(w, rear));
        lastDecision = round();
        assertNull("这一步当场结束", w.step);
        assertTrue("开的那一半不再下达：" + lastDecision, lastDecision.half != ChannelRules.Half.OPEN);
        assertEquals(Device.CLOSED, rear.device);
        assertEquals(1, count(Move.RESCUE, rear));
    }

    /** 开的那一半开不起来：算救援里的重开开不起来（{@code Origin.REOPEN}），救的时候算过次数，不再算。 */
    @Test
    public void anOpenHalfThatFailsIsAReopenFailure() {
        Decision d = stuckRearReopen();
        int attempts = rear.ladder.attempts();
        startOnly(d);
        at(100);
        closed(rear);
        at(ChannelRules.REOPEN_GAP_MS);
        startOnly(round());
        at(400);
        failedToOpen(rear, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        assertEquals(ChannelRules.Failure.Origin.REOPEN, rear.failure.origin);
        assertEquals(Outcome.FAILED, ChannelRules.outcome(w));
        serviceSays(rear, true, true);
        at(400 + ChannelRules.JUDGE_DELAY_MS);
        round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertEquals("救的时候算过了", attempts, rear.ladder.attempts());
    }

    /** 关着的一路（丢了、判成普通故障）的重开不分两半：照开那样下达一轮，这一路记成在开。 */
    @Test
    public void aReopenOfAClosedLaneIsAPlainOpen() {
        claim(Holder.PREVIEW);
        streamingAll();
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        at(1_000);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        serviceSays(rear, true, true);
        at(1_000 + ChannelRules.JUDGE_DELAY_MS);
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(Why.DOWN, d.why);
        assertNull(d.half);
        startOnly(d);
        assertFalse(w.step.split);
        assertEquals(Device.OPENING, rear.device);
        assertFalse(rear.reopenClose);
    }

    /**
     * 两半之间那一下（关完了、开的那一半还没下达）相机服务说的话算我们的：主线程卡了一下、离关完早过了 500 ms，那一句「被占用」也不认成别人拿着，
     * 不在列表里也不认 —— 和以前整个重开都记成在开一样；开的那一半照下，开不起来再判。
     */
    @Test
    public void aWordInTheGapBetweenTheHalvesStaysOurs() {
        Decision d = stuckRearReopen();
        startOnly(d);
        at(100);
        closed(rear);
        serviceSays(rear, false, true);   // 「被占用」：说的时候这一路在两半之间（调度说在途 —— 分类算我们的，不挡安静）
        rear.absent = true;
        at(900);
        Decision open = round();
        assertNull("不认成被拿着", rear.mark);
        assertNull(event(Event.Type.RECOGNIZED));
        assertEquals(ChannelRules.Half.OPEN, open.half);
        startOnly(open);
        at(1_000);
        failedToOpen(rear, CameraTaken.Reason.ofNotListed());
        at(1_000 + ChannelRules.JUDGE_DELAY_MS);
        round();
        assertEquals("开不起来再判：不在列表里，被拿着", CameraTaken.Verdict.TAKEN, event(Event.Type.JUDGED).verdict);
        assertEquals(ChannelRules.Failure.Origin.REOPEN, event(Event.Type.JUDGED).failure.origin);
    }

    /**
     * 相机服务说「空闲」放不开被一轮占着的那一台（项目所有者 2026-10-28；同一个进程里问相机服务只是读本进程的缓存，我们还开着的相机也被报过空闲）：
     * 管线重建后的新一份里后座舱关着，旧的那一份那一轮关它一直没返回 —— 相机服务说它空闲了，安静满 3 秒也不开；那一轮结束了才开。
     */
    @Test
    public void aFreeWordDoesNotReleaseAHeldCamera() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        rear.held = true;   // 旧的那一份关它，已经关了 60 秒
        rear.outstandingSince = w.now - ChannelRules.IN_FLIGHT_MAX_MS;
        rear.closingSince = rear.outstandingSince;
        serviceSays(rear, true, false);
        at(ChannelRules.QUIET_MS);
        assertEquals(Move.IDLE, run().move);
        assertEquals("说空闲了也不开", 0, count(Move.OPEN, rear));
        assertNull(rear.mark);
        assertTrue(ChannelRules.heldByClose(w, rear));

        at(ChannelRules.QUIET_MS + 1_000);
        rear.held = false;
        rear.outstandingSince = 0;
        rear.closingSince = 0;
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    /**
     * 关着等救的一路（丢了、判成普通故障），这一台被一轮占着（另一份管线的那一轮一直没结束）：不救、不计次数，梯子不往前走 ——
     * 10 分钟里不会歇 60 秒、不会停手；那一轮结束了照常救，第一次救就是第 1 次（项目所有者 2026-10-28、10-29）。
     */
    @Test
    public void aHeldLaneIsNeitherRescuedNorCounted() {
        claim(Holder.PREVIEW);
        streamingAll();
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        at(1_000);
        ChannelRules.lossClosed(w, rear);
        rear.held = true;   // 那一轮结束了，可这一台上又有一轮没结束（另一份管线的）
        rear.outstandingSince = w.now;
        rear.closingSince = w.now;
        serviceSays(rear, true, true);
        at(1_000 + ChannelRules.JUDGE_DELAY_MS);
        round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertTrue(rear.lostForRescue);

        at(10 * 60_000);
        assertEquals(Move.IDLE, run().move);
        assertEquals("不救", 0, count(Move.RESCUE, rear));
        assertEquals("不计次数", 0, rear.ladder.attempts());
        assertFalse(rear.ladder.gaveUp());
        assertFalse(rear.ladder.stopped());

        rear.held = false;
        rear.outstandingSince = 0;
        rear.closingSince = 0;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);
    }

    /**
     * 关着的一路，相机服务说「被占用（我们引起的）」，而这一台被一轮占着（任何一份管线的）：那一句还算我们的，不认成别人拿着 ——
     * 那一轮结束之前我们的连接可能还挂在相机服务里（项目所有者 2026-10-28；以前旧的那一份关卡住超过 60 秒，新的一份就把它认成别人拿着）。
     * 结束了还被占着，才是别人。
     */
    @Test
    public void aBusyWordWhileTheCameraIsHeldIsStillOurs() {
        rear.deviceSince = w.now - 120_000;    // 早就关着（新的一份管线从没动过它）
        rear.held = true;                       // 旧的那一份关它，两分钟了还没返回
        rear.outstandingSince = w.now - 120_000;
        rear.closingSince = rear.outstandingSince;
        serviceSays(rear, false, true);
        w.lastOthersChangeAt = 0;
        round();
        assertNull("被一轮占着：那一句还算我们的", rear.mark);
        assertNull(event(Event.Type.RECOGNIZED));
        at(1_000);
        rear.held = false;
        rear.outstandingSince = 0;
        rear.closingSince = 0;
        round();
        assertEquals("结束了还被占着：别人拿着", CameraTaken.Verdict.TAKEN, rear.mark);
        assertEquals(Collections.singletonList(REAR), event(Event.Type.RECOGNIZED).keys);
    }

    /**
     * 开着的一路卡住了（8 秒没进展），账本上它这一台的那一轮却不是它手上那一轮（被占着：不该有的样子，SingleCamera 里同一台相机不会同时有两轮）：
     * 只许重建会话（不开新句柄），不重开 —— 重建过了还没救回来的，不再动它，不计次数（第 1 步「轮次 + 账本」）。
     */
    @Test
    public void anOpenHeldLaneIsOnlyRebuiltNeverReopened() {
        claim(Holder.PREVIEW);
        streamingAll();
        rear.held = true;
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals("本会话出过画面：第一次救重建会话，不开新句柄", Rescue.REBUILD, d.rescue);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        rear.firstFrameAt = 0;   // 重建了还是一帧不出
        rear.progressAgeMs = CameraLiveness.STUCK_MS + CameraLiveness.RETRY_GAP_MS;
        at(CameraLiveness.RETRY_GAP_MS);
        assertEquals("不重开", Move.IDLE, run().move);
        assertEquals(1, count(Move.RESCUE, rear));
    }

    /**
     * 这一路手上那一轮的 openCamera 一直没回音：开那一步 15 秒到点照旧先往下走（别的路照常开，它算救援第 1 次）；它自己不救、不计次数 ——
     * 相机层的在途过了 60 秒（busySince 归 0，只管别的路等多久）也一样（{@link Lane#outstandingSince}：开没返回照「关没返回」同一条，
     * 项目所有者 2026-10-29 Q1）；满 60 秒录像那一侧不再算它在起来。这一路手上那一轮不算被占着。
     */
    @Test
    public void anOwnOpenStillOutstandingIsNeverRescuedAndTheOthersGoOn() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        rear.declared.add(pvR);
        front.declared.add(pvF);
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);
        rear.progressAgeMs = ChannelRules.STEP_MAX_MS;
        at(ChannelRules.STEP_MAX_MS);
        d = round();
        assertEquals("开那一步到点：先往下走", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertEquals("算第 1 次", 1, rear.ladder.attempts());
        perform(d);

        at(ChannelRules.IN_FLIGHT_MAX_MS + 10_000);
        rear.busySince = 0;   // 相机层的在途过了 60 秒
        rear.progressAgeMs = ChannelRules.IN_FLIGHT_MAX_MS + 10_000;
        assertEquals(Move.IDLE, run().move);
        assertEquals("不救", 0, count(Move.RESCUE, rear));
        assertEquals("不计次数", 1, rear.ladder.attempts());
        assertTrue(ChannelRules.heldByClose(w, rear));
        assertFalse("这一路手上那一轮：不算被占着", ChannelRules.camHeld(rear));
    }

    @Test
    public void aLaneThatIsOnlyHeldIsNotRescued() {
        streaming(surround, pvS);
        surround.progressAgeMs = 20_000;
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        assertEquals(0, surround.ladder.attempts());
    }

    /** 登记表变了，停手的那一路从头救（项目所有者 2026-10-10）。 */
    @Test
    public void aRegisterChangeLiftsAStop() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        round();
        stopLadder(surround);
        at(1_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(surround));

        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        Decision d = round();
        Event lifted = event(Event.Type.STOP_LIFTED);
        assertNotNull(lifted);
        assertEquals(Event.Cause.NEEDS_CHANGED, lifted.cause);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);
    }

    /**
     * 只给「谁要它」变了的那一路解除停手（2026-10-20 审查，车主批）：盲区画面只要环视，它登记、放开不解除停手的座舱；
     * 主界面重新登记预览，要它的座舱解除。停了再开录并成一轮也算变了。
     */
    @Test
    public void onlyAChangeAmongItsOwnWantersLiftsAStop() {
        claim(Holder.PREVIEW);
        rear.declared.add(pvR);
        round();
        stopLadder(rear);
        at(1_000);
        round();
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(rear));

        claim(Holder.SIDE_VIEW);
        round();
        assertNull("盲区画面不要座舱：不解除", event(Event.Type.STOP_LIFTED));
        drop(Holder.SIDE_VIEW);
        round();
        assertNull(event(Event.Type.STOP_LIFTED));
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(rear));

        drop(Holder.PREVIEW);
        claim(Holder.PREVIEW);   // 放开又登记，并成一轮
        round();
        Event lifted = event(Event.Type.STOP_LIFTED);
        assertNotNull("要它的预览变过：解除", lifted);
        assertEquals(Collections.singletonList(REAR), lifted.keys);
        assertEquals(Event.Cause.NEEDS_CHANGED, lifted.cause);
    }

    /** §7.4（项目所有者 2026-10-11）：停手之后，相机服务报它「空闲」、或者亮屏，也解除停手、从头救。 */
    @Test
    public void theServiceSayingFreeOrTheScreenComingOnLiftsAStop() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        round();
        stopLadder(surround);
        at(1_000);
        assertEquals(Move.IDLE, round().move);

        at(5_000);
        serviceSays(surround, true, false);
        Decision d = round();
        assertEquals(Event.Cause.SERVICE_FREE, event(Event.Type.STOP_LIFTED).cause);
        assertWait(Why.QUIET, SUR, d);
        assertEquals(Move.RESCUE, d.pending);
        at(5_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(1, d.attempt);

        at(10_000);
        stopLadder(surround);
        assertEquals(Move.IDLE, round().move);
        at(11_000);
        w.screenOnAt = w.now;
        d = round();
        assertEquals(Event.Cause.SCREEN_ON, event(Event.Type.STOP_LIFTED).cause);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(1, d.attempt);
    }

    // ================================================================= 现场回放（diag_15_bb.txt，10-10；09-27 的冲突）

    /**
     * (a) 10-10 07:22（:149-166）：录着三路、主界面最小化，车机拿走后座舱相机 1。关它的 5.4 秒里相机 0 和 2 一步都不动；
     * 关完判 TAKEN；07:31:22 报「1 空闲」之后至少等 3 秒，相机 1 单独开。
     */
    @Test
    public void replayA_rearCabinTakenWhileRecording() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);

        serviceSays(front, true, false);
        serviceSays(surround, true, false);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        for (long t = 0; t < 5_409; t += 500) {
            at(t);
            assertWait(Why.LOSING, REAR, round());
        }
        at(5_409);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        assertWait(Why.JUDGING, REAR, round());
        at(5_709);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.TAKEN, event(Event.Type.JUDGED).verdict);
        assertEquals("被拿着从丢的那一刻算", T0, rear.markSince);
        assertEquals(0, rear.ladder.attempts());
        assertEquals(Move.IDLE, d.move);
        assertTrue("0 和 2 一步都没动", done.isEmpty());
        assertTrue(ChannelRules.askDue(w));
        ChannelRules.asked(w);

        long freeAt = 561_982;
        at(freeAt);
        serviceSays(rear, true, false);
        d = round();
        assertNotNull(event(Event.Type.RELEASED));
        assertWait(Why.QUIET, REAR, d);
        at(freeAt + ChannelRules.QUIET_MS - 1);
        assertWait(Why.QUIET, REAR, round());
        at(freeAt + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(recR), d.outputs);
    }

    /** (b) 10-10 07:51:16（:209-211）：「1 空闲」比关返回早 5 ms 到 —— 关完再判，它就是最后一句：普通故障，按救援的节奏重开。 */
    @Test
    public void replayB_freeSaidJustBeforeTheCloseReturnsIsOrdinary() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        at(9_622);
        serviceSays(rear, true, true);
        at(9_627);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        at(9_927);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertNull(rear.mark);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(Why.DOWN, d.why);
        assertEquals(1, d.attempt);
    }

    /**
     * (c) 10-10 08:56:57–08:57:13（:302-323）：我们开着相机 2 时相机服务报了一句「2 空闲」（乱报），之后 2 出错误 4 ——
     * 最后一句是空闲、错误 4 不属于「被占用」一类：普通故障，按救援的节奏重开（不因为 1 被拿着就判 BLOCKED）。
     */
    @Test
    public void replayC_error4AfterASpuriousFreeIsOrdinary() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);

        serviceSays(front, true, false);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        serviceSays(surround, true, false);
        at(7_975);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        at(8_275);
        assertEquals(Move.IDLE, round().move);
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);

        at(14_114);
        ChannelRules.lossStarted(w, surround, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        surround.busySince = w.now;
        at(15_270);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        at(15_570);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertNull(surround.mark);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
    }

    /** (d) 10-10 09:00:18（:329-330）：1 放开和一句假的「2 空闲」同时到 → 安静满 3 秒后，1 单独开。 */
    @Test
    public void replayD_aReleaseTogetherWithASpuriousFreeOpensTheRearAloneAfterQuiet() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(front, recF);
        rear.declared.add(recR);
        rear.mark = CameraTaken.Verdict.TAKEN;
        rear.markSince = T0 - 180_000;
        rear.serviceFree = Boolean.FALSE;
        rear.serviceWordOurs = true;
        rear.serviceWordAt = T0 - 200_000;
        assertEquals(Move.IDLE, round().move);

        serviceSays(rear, true, false);
        serviceSays(surround, true, false);
        Decision d = round();
        assertNotNull(event(Event.Type.RELEASED));
        assertWait(Why.QUIET, REAR, d);
        at(ChannelRules.QUIET_MS - 1);
        assertWait(Why.QUIET, REAR, round());
        at(ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("只开了 1", 1, count(Move.OPEN));
    }

    /**
     * (e) 09-27 的冲突：别的程序拿走相机 1，相机服务几毫秒内把我们的相机 2 断开（1 和 2 同时只能开一路）。
     * 等两路都关完一起判、先判 TAKEN：1 TAKEN，2 BLOCKED，一次也不试开；两路都空出来以后，先开环视，再开后座舱。
     */
    @Test
    public void replayE_aHandoverConflictBlocksTheSurroundUntilBothAreFree() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);

        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        ChannelRules.lossStarted(w, surround, CameraTaken.Reason.ofDisconnect());
        surround.busySince = w.now;
        at(50);
        serviceSays(surround, true, true);
        at(1_000);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        assertWait(Why.LOSING, REAR, round());
        assertNull("还有丢失没关完：等它一起判", surround.mark);
        at(5_400);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        at(5_700);
        Decision d = round();
        List<Event> judged = all(Event.Type.JUDGED);
        assertEquals(2, judged.size());
        assertEquals("先判 TAKEN", Collections.singletonList(REAR), judged.get(0).keys);
        assertEquals(CameraTaken.Verdict.TAKEN, judged.get(0).verdict);
        assertEquals(CameraTaken.Verdict.BLOCKED, judged.get(1).verdict);
        assertEquals(CameraTaken.Verdict.BLOCKED, surround.mark);
        assertEquals(Move.IDLE, d.move);

        for (long t = 10_000; t < 60_000; t += ChannelRules.TICK_MS) {
            at(t);
            assertEquals(Move.IDLE, round().move);
        }
        assertTrue("被拿着的时候一次也不试开", done.isEmpty());

        at(60_000);
        serviceSays(rear, true, false);
        d = round();
        assertEquals(2, all(Event.Type.RELEASED).size());
        assertWait(Why.QUIET, SUR, d);
        at(60_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, count(Move.OPEN, surround));
        assertEquals(1, count(Move.OPEN, rear));
    }

    // ================================================================= 问（只问，不开）

    @Test
    public void asksEveryThirtySecondsOnlyWhileATakenLaneIsWanted() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        round();
        assertTrue(ChannelRules.askDue(w));
        ChannelRules.asked(w);
        assertEquals(1, rear.asks);
        at(ChannelRules.ASK_EVERY_MS - 1);
        assertFalse(ChannelRules.askDue(w));
        at(ChannelRules.ASK_EVERY_MS);
        assertTrue(ChannelRules.askDue(w));
        drop(Holder.PREVIEW);
        round();
        assertFalse("没人要后座舱：不问", ChannelRules.askDue(w));
    }

    @Test
    public void asksAtOnceWhenATakenLaneBecomesWantedAgain() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        round();
        ChannelRules.asked(w);
        drop(Holder.PREVIEW);
        round();
        at(40_000);
        assertFalse(ChannelRules.askDue(w));
        claim(Holder.PREVIEW);
        assertTrue("刚变成有人要、上一次问已超过 30 秒：马上问", ChannelRules.askDue(w));
        ChannelRules.asked(w);
        at(50_000);
        assertFalse(ChannelRules.askDue(w));
    }

    // ================================================================= 一步做完没有

    @Test
    public void anOpenIsDoneOnItsFirstFrameAndFailedOnAnOpenFailure() {
        claim(Holder.PREVIEW);
        declarePreviews();
        assertEquals(Outcome.NONE, ChannelRules.outcome(w));
        Decision d = round();
        startOnly(d);
        assertEquals(Outcome.RUNNING, ChannelRules.outcome(w));
        at(300);
        opened(surround, d.outputs);
        assertEquals("配好了还没出画面", Outcome.RUNNING, ChannelRules.outcome(w));
        frame(surround);
        assertEquals(Outcome.DONE, ChannelRules.outcome(w));
        ChannelRules.finish(w, Outcome.DONE);
        assertNull(w.step);

        d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);
        at(500);
        ChannelRules.openFailed(w, rear, CameraTaken.Reason.ofNotListed());
        assertEquals(Outcome.FAILED, ChannelRules.outcome(w));
    }

    @Test
    public void anApplyIsDoneOnlyOnTheFirstFrameOfTheNewSession() {
        claim(Holder.PREVIEW);
        streaming(surround, Out.SINK);
        surround.declared.add(pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        startOnly(d);
        assertEquals("旧会话出过的画面不算", Outcome.RUNNING, ChannelRules.outcome(w));
        at(300);
        configured(surround, d.outputs);
        assertEquals(Outcome.RUNNING, ChannelRules.outcome(w));
        frame(surround);
        assertEquals(Outcome.DONE, ChannelRules.outcome(w));
    }

    /** 改输出卡在配会话里（相机层一直没回来）：到点不再改第二次，没进展满 8 秒由救援接手。 */
    @Test
    public void aStuckApplyIsNotRepeatedAndTheRescueTakesOver() {
        claim(Holder.PREVIEW);
        streaming(surround, Out.SINK);
        surround.declared.add(pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        startOnly(d);
        at(ChannelRules.STEP_MAX_MS - 1);
        assertWait(Why.STEP, SUR, round());
        at(ChannelRules.STEP_MAX_MS);
        surround.progressAgeMs = ChannelRules.STEP_MAX_MS;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(1, count(Move.APPLY));
    }

    // ================================================================= 状态、身份、常量

    @Test
    public void conditionAndPhotoExclusion() {
        streaming(surround, pvS);
        assertEquals(Condition.STREAMING, ChannelRules.condition(surround));
        assertFalse(ChannelRules.excludedFromPhoto(w, surround));
        assertEquals(Condition.BRINGING_UP, ChannelRules.condition(front));
        assertFalse("亮着、谁都没登记：照拍（拍照刚登记那一下读到的是上一轮的登记表）",
                ChannelRules.excludedFromPhoto(w, front));

        rear.mark = CameraTaken.Verdict.BLOCKED;
        assertEquals(Condition.TAKEN, ChannelRules.condition(rear));
        assertTrue(ChannelRules.excludedFromPhoto(w, rear));

        front.lostForRescue = true;
        assertEquals(Condition.RESCUING, ChannelRules.condition(front));
        assertFalse(ChannelRules.excludedFromPhoto(w, front));
        stopLadder(front);
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(front));
        assertTrue(ChannelRules.excludedFromPhoto(w, front));

        stopLadder(surround);
        assertEquals("停手的那一路在出画面就照拍", Condition.STREAMING, ChannelRules.condition(surround));
        assertFalse(ChannelRules.excludedFromPhoto(w, surround));
    }

    /**
     * 拍照不等卡住的那一台（2026-10-29）：它这一台那一轮满 60 秒还有开或关没返回，那一下返回之前它不会开，也不会被救到停手 ——
     * 不拍、不白等 10 秒。拍照刚登记、还没人要它的那一下也算（{@link ChannelRules#roundStuck} 不看谁要）；60 秒以内照等；
     * 那一轮结束了照拍。
     */
    @Test
    public void aPhotoDoesNotWaitForALaneWhoseRoundIsStuck() {
        rear.held = true;
        rear.outstandingSince = w.now;
        rear.closingSince = w.now;
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertFalse("60 秒以内照等", ChannelRules.excludedFromPhoto(w, rear));
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertTrue("还没人要它也不等", ChannelRules.excludedFromPhoto(w, rear));
        claim(Holder.PHOTO);
        assertTrue(ChannelRules.excludedFromPhoto(w, rear));
        assertEquals("不会被救到停手", Condition.BRINGING_UP, ChannelRules.condition(rear));

        rear.held = false;   // 那一轮结束了
        rear.outstandingSince = 0;
        rear.closingSince = 0;
        assertFalse(ChannelRules.excludedFromPhoto(w, rear));
    }

    /**
     * 熄屏拍照（设计 v3.1 C4；项目所有者 2026-10-27）：通道算黑、没在开车时不为拍照开相机 —— 不在出画面的一路都不拍、不白等 10 秒、
     * 不算在该拍的里面，一路都没有就当场收尾；在出画面的照拍（一般是留着的环视）。在开车时和以前一样：座舱熄屏放开，只开环视。
     */
    @Test
    public void aDarkPhotoOpensNothingAndUsesOnlyAStreamingSurround() {
        w.screenOn = false;
        claim(Holder.PHOTO);
        assertTrue(ChannelRules.excludedFromPhoto(w, rear));
        assertTrue(ChannelRules.excludedFromPhoto(w, front));
        assertTrue("没在出画面的环视：不为拍照开", ChannelRules.excludedFromPhoto(w, surround));
        assertEquals(Move.IDLE, run().move);
        assertTrue("一路都不开", done.isEmpty());
        keptInDark();
        assertTrue("环视留着也不为拍照开", ChannelRules.excludedFromPhoto(w, surround));
        assertEquals(Move.IDLE, run().move);
        assertTrue(done.isEmpty());

        w.driving = true;   // 在开车：和以前一样，只开环视
        assertFalse(ChannelRules.excludedFromPhoto(w, surround));
        assertTrue(ChannelRules.excludedFromPhoto(w, rear));
        assertEquals(Move.IDLE, run().move);
        assertEquals(1, done.size());
        assertEquals(Collections.singletonList(SUR), done.get(0).keys);
        assertEquals(set(Out.SINK), done.get(0).outputs);

        w.driving = false;
        assertTrue(ChannelRules.streaming(surround));
        assertFalse("在出画面的环视照拍", ChannelRules.excludedFromPhoto(w, surround));
        streaming(rear, pvR);
        assertFalse("还在出画面的照拍", ChannelRules.excludedFromPhoto(w, rear));
    }

    /**
     * 大纲 §0 的名词：会话里的输出算要的、留着的、死的 —— 诊断报告、卡顿现场、关相机那一行（「带走留着的输出」）照它分。
     * 只是分类：留着的不为它改会话（上面「减输出能拖就拖」那几条）。
     */
    @Test
    public void sessionOutputsAreWantedKeptOrDead() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS, recS);   // 停录后留着的录像输出（录制器停放着，还声明着）
        assertEquals(ChannelRules.OutState.WANTED, ChannelRules.outState(w, surround, pvS));
        assertEquals("没人要了、消费者还活着：留着", ChannelRules.OutState.KEPT, ChannelRules.outState(w, surround, recS));
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        assertEquals("又开录了：要的", ChannelRules.OutState.WANTED, ChannelRules.outState(w, surround, recS));
        w.screenOn = false;
        assertEquals("熄屏时主界面看不见：预览留着", ChannelRules.OutState.KEPT, ChannelRules.outState(w, surround, pvS));
        surround.dead.add(pvS);
        assertEquals("画布被系统收走：死的", ChannelRules.OutState.DEAD, ChannelRules.outState(w, surround, pvS));
        streaming(rear, Out.SINK);
        assertEquals("出帧口在会话里就算要的", ChannelRules.OutState.WANTED, ChannelRules.outState(w, rear, Out.SINK));
    }

    /** 输出按消费者认（§7.6）：同一块画布再声明一次还是同一个输出，换了画布才是另一个。 */
    @Test
    public void outputsAreIdentifiedByTheirConsumer() {
        Object canvas = new Object();
        Out once = new Out(Kind.PREVIEW, canvas);
        Out again = new Out(Kind.PREVIEW, canvas);
        assertEquals(once, again);
        assertEquals(once.hashCode(), again.hashCode());
        assertFalse(once.equals(new Out(Kind.PREVIEW, new Object())));
        assertFalse(once.equals(new Out(Kind.MIRROR, canvas)));
    }

    @Test
    public void lanesAreKeptInOpenOrder() {
        List<String> keys = new ArrayList<>();
        for (Lane lane : w.lanes) {
            keys.add(lane.key);
        }
        assertEquals(Arrays.asList(SUR, REAR, FRONT), keys);
    }

    /** 大纲 §2 1a 的常量表；退出、唤醒锁的上限由常量算出来。 */
    @Test
    public void constantsFollowTheTable() {
        assertEquals(2_000L, ChannelRules.TICK_MS);
        assertEquals(3_000L, ChannelRules.QUIET_MS);
        assertEquals(CameraTaken.QUIET_MS, ChannelRules.QUIET_MS);
        assertEquals(30_000L, ChannelRules.ASK_EVERY_MS);
        assertEquals(CameraTaken.ASK_EVERY_MS, ChannelRules.ASK_EVERY_MS);
        assertEquals(30_000L, ChannelRules.HOLD_MS);
        assertEquals(1_000L, ChannelRules.SLEEP_GRACE_MS);
        assertEquals(15_000L, ChannelRules.STEP_MAX_MS);
        assertEquals(2_000L, ChannelRules.RECORD_OUTPUT_WAIT_MS);
        assertEquals(10_000L, ChannelRules.CLOSE_SURROUND_MAX_MS);
        assertEquals(30_000L, ChannelRules.CLOSE_CABINS_MAX_MS);
        assertEquals(40_000L, ChannelRules.ORDERED_CLOSE_MAX_MS);
        assertEquals(300L, ChannelRules.JUDGE_DELAY_MS);
        assertEquals(60_000L, ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals("退出最多等：在途封顶 + 按次序关 + 5 秒", 105_000L, ChannelRules.EXIT_WAIT_MAX_MS);
        assertEquals("唤醒锁最多拉：等在途 1 秒 + 按次序关", 41_000L, ChannelRules.WAKE_HOLD_MAX_MS);
        assertEquals("梯子上的数字不变", 8_000L, CameraLiveness.STUCK_MS);
        assertEquals(12_000L, CameraLiveness.RETRY_GAP_MS);
        assertEquals(3, CameraLiveness.MAX_ATTEMPTS);
        assertEquals(60_000L, CameraLiveness.COOL_OFF_MS);
        assertEquals(3, CameraLiveness.MAX_CYCLES);
    }

    // ================================================================= 熄屏放开相机（项目所有者 2026-10-27）

    /**
     * 环视这一段熄屏留不留（{@link ChannelRules#judgeSurroundInDark}）的整张表：照亮着算、通道刚算黑那一下（R1、R3）、
     * 之后读到关（R5）、变成开或布防（R4）、读不到不变（R3）、车机要睡（R6，屏幕不知道也照判、上一次读数不动）、
     * 屏幕不知道时不判（R10）、在开车照亮着算。
     */
    @Test
    public void surroundInDarkFollowsItsTable() {
        final ChannelRules.SurroundInDark LIT = ChannelRules.SurroundInDark.LIT;
        final ChannelRules.SurroundInDark KEPT = ChannelRules.SurroundInDark.KEPT;
        final ChannelRules.SurroundInDark RELEASED = ChannelRules.SurroundInDark.RELEASED;
        //     名字                       原来      亮着   安卓熄屏 知道   开车   哨兵 上一次 → 之后      上一次  事件
        row("亮着：照亮着算，记下读数", LIT, true, false, true, false, 0, null, LIT, 0, null);
        row("亮着：读不到不动上一次", LIT, true, false, true, false, null, 2, LIT, 2, null);
        row("通道刚算黑，布防：留着", LIT, false, false, true, false, 2, 0, KEPT, 2, Event.Cause.DARK_ONSET);
        row("通道刚算黑，开：留着", LIT, false, false, true, false, 1, null, KEPT, 1, Event.Cause.DARK_ONSET);
        row("通道刚算黑，关：放开", LIT, false, false, true, false, 0, 2, RELEASED, 0, Event.Cause.DARK_ONSET);
        row("通道刚算黑，读不到：放开，上一次记成关", LIT, false, false, true, false, null, 2, RELEASED, 0,
                Event.Cause.DARK_ONSET);
        row("留着，读不到：不算关", KEPT, false, false, true, false, null, 2, KEPT, 2, null);
        row("留着，读到关：放开", KEPT, false, false, true, false, 0, 2, RELEASED, 0, Event.Cause.SENTRY_OFF);
        row("留着，布防变开：不变", KEPT, false, false, true, false, 1, 2, KEPT, 1, null);
        row("放开，关变布防：留着", RELEASED, false, false, true, false, 2, 0, KEPT, 2, Event.Cause.SENTRY_ON);
        row("放开，关变开：留着（不等布防）", RELEASED, false, false, true, false, 1, 0, KEPT, 1, Event.Cause.SENTRY_ON);
        row("放开，第一次读到布防：只当底", RELEASED, false, false, true, false, 2, null, RELEASED, 2, null);
        row("放开，读不到：不算开", RELEASED, false, false, true, false, null, 0, RELEASED, 0, null);
        row("放开，一直布防：不是变成开", RELEASED, false, false, true, false, 2, 2, RELEASED, 2, null);
        row("留着，车机要睡：放开，上一次不动", KEPT, false, true, true, false, 0, 2, RELEASED, 2,
                Event.Cause.ANDROID_OFF);
        row("照亮着算直接到车机要睡、没读到过：上一次记成关", LIT, false, true, true, false, null, null, RELEASED, 0,
                Event.Cause.ANDROID_OFF);
        row("照亮着算直接到车机要睡、这一下读到布防：记下它", LIT, false, true, true, false, 2, null, RELEASED, 2,
                Event.Cause.ANDROID_OFF);
        row("放开，睡着时哨兵变了：上一次不动", RELEASED, false, true, true, false, 2, 0, RELEASED, 0, null);
        row("屏幕不知道：不判", KEPT, false, false, false, false, 0, 2, KEPT, 2, null);
        row("屏幕不知道，亮着：不记底", RELEASED, true, false, false, false, 2, null, RELEASED, null, null);
        row("屏幕不知道也照判车机要睡", KEPT, false, true, false, false, 2, 2, RELEASED, 2, Event.Cause.ANDROID_OFF);
        row("在开车：照亮着算", RELEASED, false, false, true, true, 0, 2, LIT, 0, null);
        row("在开车，安卓熄屏：不算车机要睡", KEPT, false, true, true, true, 2, 2, LIT, 2, null);
    }

    private void row(String name, ChannelRules.SurroundInDark was, boolean lit, boolean androidOff, boolean known,
                     boolean driving, Integer sentry, Integer last, ChannelRules.SurroundInDark want, Integer wantLast,
                     Event.Cause wantCause) {
        w.surroundInDark = was;
        w.screenOn = lit;
        w.androidOff = androidOff;
        w.screenKnown = known;
        w.driving = driving;
        w.sentry = sentry;
        w.lastSentry = last;
        w.surroundCause = Event.Cause.SENTRY_ON;   // 上一轮留下的：每一轮重算
        List<Event> out = new ArrayList<>();
        ChannelRules.judgeSurroundInDark(w, out);
        assertEquals(name, want, w.surroundInDark);
        assertEquals(name + "：上一次读数", wantLast, w.lastSentry);
        assertTrue(name, out.size() <= 1);
        assertEquals(name + "：那一行", wantCause, out.isEmpty() ? null : out.get(0).cause);
        assertEquals(name + "：发给录像协调器的原因和那一行同一个", wantCause, w.surroundCause);
    }

    /** 没有环视那一路（管线重建、各路还没交）：不报那一行，原因照记 —— 通道的决定连同原因照样发给录像协调器。 */
    @Test
    public void theSurroundCauseIsKeptWithoutASurroundLane() {
        World bare = new World(new ArrayList<>());
        bare.keepWhenDark = true;
        bare.surroundInDark = ChannelRules.SurroundInDark.RELEASED;
        bare.screenOn = false;
        bare.sentry = 2;
        bare.lastSentry = 0;
        List<Event> out = new ArrayList<>();
        ChannelRules.judgeSurroundInDark(bare, out);
        assertTrue(out.isEmpty());
        assertEquals(ChannelRules.SurroundInDark.KEPT, bare.surroundInDark);
        assertEquals(Event.Cause.SENTRY_ON, bare.surroundCause);
        assertEquals(ChannelRules.SurroundInDark.KEPT, ChannelRules.surroundVerdict(bare));
    }

    /**
     * 通道对环视的熄屏决定（{@link ChannelRules#surroundVerdict}，发给录像协调器）：通道没算黑、或者在开车 → LIT；
     * 算黑、没在开车 → 留着（含「熄屏持续录制」开着）是 KEPT，不然 RELEASED。环视「熄屏放开」就是 RELEASED 那一个 —— 每一种组合都一样。
     */
    @Test
    public void theSurroundVerdictIsTheReleaseDecisionNamed() {
        for (boolean lit : new boolean[]{true, false}) {
            for (boolean driving : new boolean[]{true, false}) {
                for (boolean keep : new boolean[]{true, false}) {
                    for (ChannelRules.SurroundInDark latch : ChannelRules.SurroundInDark.values()) {
                        w.screenOn = lit;
                        w.screenDark = !lit;
                        w.driving = driving;
                        w.keepWhenDark = keep;
                        w.surroundInDark = latch;
                        String name = "亮着=" + lit + " 开车=" + driving + " 熄屏持续录制=" + keep + " " + latch;
                        ChannelRules.SurroundInDark want = lit || driving ? ChannelRules.SurroundInDark.LIT
                                : keep && latch == ChannelRules.SurroundInDark.KEPT ? ChannelRules.SurroundInDark.KEPT
                                : ChannelRules.SurroundInDark.RELEASED;
                        assertEquals(name, want, ChannelRules.surroundVerdict(w));
                        assertEquals(name, ChannelRules.surroundVerdict(w) == ChannelRules.SurroundInDark.RELEASED,
                                ChannelRules.releasedByDark(w, surround));
                    }
                }
            }
        }
    }

    /**
     * R1：通道刚算黑那一下，环视留着要三样 ——「熄屏持续录制」开着、哨兵模式开或布防、录像要它；少一样就和座舱同一轮放开
     * （录像不要它时留着也不开，开了录再开）。八种组合。
     */
    @Test
    public void theSurroundIsKeptAtThirtySecondsOnlyWithSettingSentryAndRecording() {
        for (boolean keep : new boolean[]{true, false}) {
            for (int sentry : new int[]{2, 0}) {
                for (boolean recording : new boolean[]{true, false}) {
                    String name = "熄屏持续录制" + (keep ? "开" : "关") + "、哨兵 " + sentry + (recording ? "、在录" : "、没在录");
                    w.holders.clear();
                    w.recordPlan.clear();
                    if (recording) {
                        claim(Holder.RECORDING);
                        planAll();
                    }
                    w.keepWhenDark = keep;
                    w.sentry = sentry;
                    w.screenOn = true;
                    ChannelRules.settle(w);
                    w.screenOn = false;
                    ChannelRules.settle(w);
                    boolean kept = keep && sentry == 2;
                    assertEquals(name, kept, ChannelRules.surroundKept(w));
                    assertEquals(name, !kept, ChannelRules.releasedByDark(w, surround));
                    assertEquals(name, kept && recording, ChannelRules.wanted(w, surround));
                    assertTrue(name + "：座舱照旧放开", ChannelRules.releasedByDark(w, rear));
                }
            }
        }
    }

    /** R2：黑了不到 30 秒哨兵模式变了，什么都不动，等 30 秒那一下照那时的读数判 —— 两个方向都一样。 */
    @Test
    public void aSentryChangeInTheFirstThirtySecondsWaitsForTheDecision() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        streaming(surround, recS);
        w.sentry = 2;
        assertEquals(Move.IDLE, round().move);
        w.screenDark = true;   // 屏幕真黑了，通道还照亮着算
        w.sentry = 0;
        assertEquals("黑了不到 30 秒：不判、不动", Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.LIT, w.surroundInDark);
        assertNull(event(Event.Type.SURROUND_IN_DARK));
        w.screenOn = false;
        Decision d = round();
        assertEquals("30 秒那一下照那时的读数：关 → 放开", ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals(Event.Cause.DARK_ONSET, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.SCREEN_OFF, d.why);

        w.screenOn = true;
        w.screenDark = false;
        assertEquals(Move.IDLE, round().move);
        w.screenDark = true;
        assertEquals(Move.IDLE, round().move);
        w.sentry = 2;   // 黑了 10 秒远程打开哨兵模式
        assertEquals(Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.LIT, w.surroundInDark);
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        assertEquals("30 秒那一下：布防 → 留着", ChannelRules.SurroundInDark.KEPT, w.surroundInDark);
        assertTrue(ChannelRules.wanted(w, surround));
    }

    /** R3：通道刚算黑时哨兵模式读不到算没开 —— 放开；留着之后读不到不算关，环视一直录。 */
    @Test
    public void anUnreadableSentryAtThirtySecondsReleases_butNeverClosesAKeptSurround() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        streaming(surround, recS);
        w.sentry = 2;
        assertEquals(Move.IDLE, round().move);
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.KEPT, w.surroundInDark);
        w.sentry = null;   // 10-24 15:43:58：哨兵模式读不到 60 秒
        at(60_000);
        assertEquals("留着的不算关", Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.KEPT, w.surroundInDark);
        assertNull(event(Event.Type.SURROUND_IN_DARK));
        assertTrue(ChannelRules.wanted(w, surround));

        w.screenOn = true;
        at(120_000);
        assertEquals(Move.IDLE, round().move);
        w.screenOn = false;   // 又黑满 30 秒，这时读不到
        Decision d = round();
        assertEquals("读不到算没开：放开", ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals("上一次读数记成关：之后布防算变成开", Integer.valueOf(0), w.lastSentry);
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
    }

    /**
     * 10-24 的两次（熄屏后 64 秒、267 秒远程打开哨兵模式，读数从关直接跳到布防）：30 秒那一下哨兵关，三路同一轮放开、什么都不开回来；
     * 读到布防，环视带着停放着的录制器的录像输出单独开起来，座舱一步不动。
     */
    @Test
    public void replay1024_sentryOffStraightToArmedOpensTheSurroundWithItsParkedRecorder() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        w.sentry = 0;
        assertEquals(Move.IDLE, round().move);
        w.screenOn = false;
        assertEquals(Move.IDLE, run().move);
        assertEquals(2, done.size());
        assertEquals(Why.SCREEN_OFF, done.get(0).why);
        assertEquals(Collections.singletonList(SUR), done.get(0).keys);
        assertEquals(Why.DARK_RELEASE, done.get(1).why);
        serviceSays(surround, true, true);
        serviceSays(rear, true, true);
        serviceSays(front, true, true);

        at(64_000);
        w.sentry = 2;
        Decision d = round();
        assertEquals(Event.Cause.SENTRY_ON, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(EnumSet.of(Holder.RECORDING), d.forWhom);
        assertEquals("带着停放着的录制器的录像输出", set(recS), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertTrue("座舱一步不动", rear.device == Device.CLOSED && front.device == Device.CLOSED);
        assertEquals(1, count(Move.OPEN));
    }

    /** R4：哨兵模式变成开，「熄屏持续录制」关着、或者录像不要环视（只有超级镜子、拍照登记着），环视不开。 */
    @Test
    public void aSentryRiseOpensNothingWithoutTheSettingOrRecording() {
        surround.declared.add(recS);
        surround.declared.add(mirror);
        w.sentry = 0;
        round();
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        w.keepWhenDark = false;
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        w.sentry = 2;
        assertEquals("熄屏持续录制关着：不开", Move.IDLE, round().move);
        assertEquals(Event.Cause.SENTRY_ON, event(Event.Type.SURROUND_IN_DARK).cause);
        assertFalse(ChannelRules.surroundKept(w));

        w.keepWhenDark = true;
        drop(Holder.RECORDING);
        claim(Holder.MIRROR);
        claim(Holder.PHOTO);
        assertEquals("录像不要它：超级镜子、拍照黑着不算，不开", Move.IDLE, round().move);
        assertTrue(ChannelRules.surroundKept(w));
        assertTrue(done.isEmpty());
    }

    /** R4 是此刻的事：哨兵模式先变成开、录像几秒后才登记（10-14 那一次 U 盘 4.5 秒后能用），录像一登记环视就开。 */
    @Test
    public void aSentryRiseBeforeRecordingIsClaimedOpensWhenItIs() {
        surround.declared.add(recS);
        w.sentry = 0;
        round();
        w.screenOn = false;
        round();
        w.sentry = 2;
        assertEquals("还没在录：不开", Move.IDLE, round().move);
        at(4_500);
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(recS), d.outputs);
    }

    /** R4：要开环视而用不了相机（后台起的前台服务）：照「能用相机」先不开、等，不另想办法。 */
    @Test
    public void aSentryRiseWhileTheCameraCannotBeUsedOnlyDefers() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        surround.declared.add(recS);
        w.sentry = 0;
        round();
        w.screenOn = false;
        round();
        w.cameraUsable = false;
        w.sentry = 1;
        Decision d = round();
        assertEquals(Move.IDLE, d.move);
        assertEquals("先不开", SUR, d.deferredKey);
        assertTrue(done.isEmpty());
    }

    /** R5：30 秒之后读到哨兵模式关，留着的环视当场放开（熄屏，10 秒封顶），录制器停放着。 */
    @Test
    public void sentryOffAfterThirtySecondsReleasesTheSurroundAtOnce() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        streaming(surround, recS);
        w.sentry = 2;
        round();
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        at(600_000);
        w.sentry = 0;
        Decision d = round();
        assertEquals(Event.Cause.SENTRY_OFF, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
        assertEquals(ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        assertTrue(ChannelRules.keepsRecordOutput(w, surround));
    }

    /**
     * R6：车机要睡（黑着时安卓熄屏、没在开车）环视放开，哨兵模式布防着也一样；醒来哨兵模式还布防着不算变成开，相机不开。
     */
    @Test
    public void androidOffReleasesTheSurroundAndAWakeWithSentryStillOnBringsNothing() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        streaming(surround, recS);
        w.sentry = 2;
        round();
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        w.androidOff = true;
        Decision d = round();
        assertEquals(Event.Cause.ANDROID_OFF, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
        perform(d);
        serviceSays(surround, true, true);

        at(3_600_000);
        w.androidOff = false;   // 醒了（自己醒、远程开空调），屏幕还黑着
        assertEquals("哨兵模式还布防着：不是变成开", Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals(1, done.size());
    }

    /**
     * R6 + R3：亮着时一次都没读到过哨兵模式，安卓直接熄屏 —— 那一下也算 30 秒那一下，上一次读数记成关；睡着时哨兵模式被打开，
     * 醒来读到布防算变成开，环视开起来录。
     */
    @Test
    public void aWakeWithSentryTurnedOnDuringTheSleepOpensTheSurround() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        surround.declared.add(recS);
        round();
        assertEquals(ChannelRules.SurroundInDark.LIT, w.surroundInDark);
        assertNull(w.lastSentry);
        w.screenDark = true;
        w.screenOn = false;
        w.androidOff = true;
        round();
        assertEquals(ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals(Integer.valueOf(0), w.lastSentry);
        at(3_600_000);
        w.androidOff = false;
        w.sentry = 2;
        Decision d = round();
        assertEquals(Event.Cause.SENTRY_ON, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    /** 进程在黑着时被拉起（保活、开机）：新世界从放开起，第一次读到的只当底 —— 不开相机，之后变成开才算。 */
    @Test
    public void aWorldStartingDarkKeepsNothingUntilASentryRise() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        surround.declared.add(recS);
        w.screenOn = false;
        w.screenDark = true;
        w.sentry = 2;
        assertEquals("哨兵模式布防着：只当底，不开", Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals(Integer.valueOf(2), w.lastSentry);
        w.sentry = 0;
        assertEquals(Move.IDLE, round().move);
        w.sentry = 1;
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    /**
     * 通道算黑、没在开车：留着的环视只要录像输出 —— 预览、超级镜子、盲区画面的画布声明着、登记着也不算，会话不为它们改，
     * 没在会话里的不加（不在黑屏里重建会话、多切一个文件）。
     */
    @Test
    public void inChannelDarkOnlyTheRecordOutputIsWanted() {
        Out side = new Out(Kind.SIDE, "side");
        claim(Holder.RECORDING);
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        claim(Holder.SIDE_VIEW);
        planAll();
        streaming(surround, recS, pvS);
        surround.declared.add(mirror);
        surround.declared.add(side);
        keptInDark();
        w.screenOn = false;
        assertEquals(set(recS), ChannelRules.wantedOutputs(w, surround));
        assertEquals(EnumSet.of(Holder.RECORDING), ChannelRules.wantedBy(w, surround));
        assertEquals("会话不改", Move.IDLE, round().move);
        assertEquals(set(recS, pvS), surround.session);
        w.screenOn = true;
        assertEquals("亮屏又都要了", set(recS, pvS, mirror, side), ChannelRules.wantedOutputs(w, surround));
    }

    /**
     * R8：熄屏期间绝不开座舱 —— 谁登记着都一样：黑了不到 30 秒关着的不开、通道算黑时放开；黑了不到 30 秒开着的卡住了，
     * 救援只做第一次的重建会话（不开设备），不重开；亮了才重开。
     */
    @Test
    public void theCabinsNeverOpenWhileDarkForAnyHolder() {
        planAll();
        declarePreviews();
        rear.declared.add(mirror);
        w.mirrorCabin = true;
        w.screenDark = true;
        for (Holder holder : Holder.values()) {
            w.holders.clear();
            claim(holder);
            w.screenOn = true;
            assertFalse(holder + "：黑了不到 30 秒，关着的后座舱不开", ChannelRules.wanted(w, rear));
            assertFalse(ChannelRules.wanted(w, front));
            w.screenOn = false;
            assertFalse(holder + "：通道算黑，后座舱放开", ChannelRules.wanted(w, rear));
            assertFalse(ChannelRules.wanted(w, front));
        }

        // 黑了不到 30 秒，开着的后座舱卡住了（预览开着它）
        w.holders.clear();
        w.screenOn = true;
        w.screenDark = false;
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);
        w.screenDark = true;
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals("第一次：重建会话（不开设备）", Rescue.REBUILD, d.rescue);
        startOnly(d);
        at(500);
        configured(rear, d.outputs);   // 配好了，一帧不出
        at(ChannelRules.STEP_MAX_MS);
        rear.progressAgeMs = CameraLiveness.STUCK_MS + ChannelRules.STEP_MAX_MS;
        assertEquals("黑着：不重开", Move.IDLE, round().move);
        at(ChannelRules.STEP_MAX_MS + CameraLiveness.RETRY_GAP_MS);
        assertEquals(Move.IDLE, round().move);
        assertEquals("只救了那一次", 1, count(Move.RESCUE));

        w.screenDark = false;   // 亮了
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
    }

    /** R8 救援那一道不管在开车时：开车时显示屏关了、开着的后座舱卡住，和以前一样，重建会话没用就重开。 */
    @Test
    public void whileDrivingAStalledCabinInTheDarkIsStillReopened() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        w.driving = true;
        assertEquals(Move.IDLE, round().move);
        w.screenDark = true;   // 黑了还不到 30 秒
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Rescue.REBUILD, d.rescue);
        startOnly(d);
        at(500);
        configured(rear, d.outputs);   // 配好了，一帧不出
        at(ChannelRules.STEP_MAX_MS);
        rear.progressAgeMs = CameraLiveness.STUCK_MS + ChannelRules.STEP_MAX_MS;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals("在开车：照以前重开", Rescue.REOPEN, d.rescue);
    }

    /** R8 救援那一道只管座舱：熄屏留着的环视卡住了，照常救 —— 重建会话没用就重开。 */
    @Test
    public void aKeptSurroundStalledInTheDarkIsStillReopened() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        streaming(surround, recS);
        keptInDark();
        w.screenDark = true;
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        surround.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Rescue.REBUILD, d.rescue);
        startOnly(d);
        at(500);
        configured(surround, d.outputs);   // 配好了，一帧不出
        at(ChannelRules.STEP_MAX_MS);
        surround.progressAgeMs = CameraLiveness.STUCK_MS + ChannelRules.STEP_MAX_MS;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals("环视不归这一道：重开", Rescue.REOPEN, d.rescue);
    }

    /**
     * R10：屏幕不知道黑没黑时谁登记着都不开、不重开（等 SCREEN_UNKNOWN，不写「先不开」）；改输出、第一次重建会话同一轮照做，
     * 都没有才等。知道了照常开。
     */
    @Test
    public void noCameraOpensOrReopensWhileTheScreenIsUnknownButApplyAndFirstRebuildsGoOn() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        declarePreviews();
        streaming(surround, pvS);
        surround.declared.add(mirror);   // 后视镜的画布声明着、会话里还没有：要改输出
        streaming(front, pvF);
        front.progressAgeMs = CameraLiveness.STUCK_MS;   // 前座舱卡住了：出过画面，第一次救是重建会话
        w.screenKnown = false;
        Decision d = round();
        assertEquals("改输出照做", Move.APPLY, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals("第一次重建会话照做", Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertEquals(Rescue.REBUILD, d.rescue);
        startOnly(d);
        at(500);
        configured(front, d.outputs);   // 一帧不出
        at(ChannelRules.STEP_MAX_MS + CameraLiveness.RETRY_GAP_MS);
        front.progressAgeMs = CameraLiveness.STUCK_MS + ChannelRules.STEP_MAX_MS;
        d = round();
        assertWait(Why.SCREEN_UNKNOWN, REAR, d);
        assertEquals(Move.OPEN, d.pending);
        assertFalse("不写「先不开」", d.deferralKnown);
        assertEquals(0, count(Move.OPEN));
        assertEquals("不重开", 1, count(Move.RESCUE));

        w.screenKnown = true;
        d = round();
        assertEquals("知道了：照常开", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    /** R10：屏幕不知道时不先关环视 —— 关了它，「开」那一步也开不回来；保持到点的座舱照旧在它旁边关。 */
    @Test
    public void theSurroundIsNotClosedFirstWhileTheScreenIsUnknown() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        w.screenKnown = false;
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
    }

    /**
     * 环视被一轮占着（不该有的样子：开着的环视，账本上它这一台的那一轮却不是它手上那一轮）：关座舱前不先关它 ——「开」那一步那一轮结束之前不开它，
     * 关了开不回来（第 1 步「轮次 + 账本」）。座舱照旧在它旁边关。
     */
    @Test
    public void theSurroundIsNotClosedFirstWhileHeld() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        surround.held = true;
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("座舱在环视旁边关", Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        assertFalse("开着的环视不算在等它", ChannelRules.heldByClose(w, surround));
    }

    /**
     * 在开车：2026-10-27 的熄屏规矩一条都不用 —— 环视不按熄屏放开、不判、盲区画面照要、拍照照开，车机要睡那一行也不放开；
     * 座舱照旧放开。不再开车时还算黑，就在那一刻判。
     */
    @Test
    public void whileDrivingTheDarkLeavesTheSurroundAsTodayAndDecidesWhenDrivingEnds() {
        Out side = new Out(Kind.SIDE, "side");
        claim(Holder.RECORDING);
        claim(Holder.SIDE_VIEW);
        w.recordPlan.add(SUR);   // 只录环视：座舱亮着时不为录像开
        streaming(surround, recS, side);
        w.sentry = 0;   // 开车时哨兵模式读关
        w.driving = true;
        assertEquals(Move.IDLE, round().move);
        w.screenDark = true;
        w.screenOn = false;   // 开车时显示屏关了，通道算黑
        assertEquals(Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.LIT, w.surroundInDark);
        assertNull(event(Event.Type.SURROUND_IN_DARK));
        assertFalse(ChannelRules.releasedByDark(w, surround));
        assertEquals(set(recS, side), ChannelRules.wantedOutputs(w, surround));
        assertEquals(EnumSet.of(Holder.RECORDING, Holder.SIDE_VIEW), ChannelRules.wantedBy(w, surround));
        assertFalse(ChannelRules.excludedFromPhoto(w, surround));
        assertTrue("座舱照旧放开", ChannelRules.releasedByDark(w, rear));
        w.androidOff = true;
        assertEquals("安卓熄屏也不放开环视", Move.IDLE, round().move);
        assertEquals(ChannelRules.SurroundInDark.LIT, w.surroundInDark);

        w.androidOff = false;
        w.driving = false;   // 停车了，屏幕还黑着
        Decision d = round();
        assertEquals(Event.Cause.DARK_ONSET, event(Event.Type.SURROUND_IN_DARK).cause);
        assertEquals(ChannelRules.SurroundInDark.RELEASED, w.surroundInDark);
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
    }

    // ================================================================= 帮手：扮演相机层、登记表

    private void at(long ms) {
        w.now = T0 + ms;
    }

    // ================================================================= 前座舱只用一次（暂时的规矩，2026-10-22 定、10-23 改成只管前座舱）

    /**
     * 通道眼里熄屏的那一刻，启用的前座舱去掉、报一次；之后亮屏，主界面预览、拍照、录像都不再要它（录像计划里还写着也不要）。
     * 后座舱照一般的规矩：熄屏放开、亮屏又要它，录像输出留着。环视照旧。
     */
    @Test
    public void withTheOneShotRuleOnlyTheFrontCabinIsRetiredWhenTheChannelGoesDark() {
        w.frontCabinOneShot = true;
        claim(Holder.PREVIEW);
        claim(Holder.PHOTO);
        claim(Holder.RECORDING);
        planAll();
        round();
        assertTrue("亮着：不去掉", retiredEvents().isEmpty());
        w.screenOn = false;
        round();
        assertEquals(Collections.singletonList(Collections.singletonList(FRONT)), retiredEvents());
        assertTrue(w.frontCabinRetired);
        round();
        assertTrue("只报一次", retiredEvents().isEmpty());

        w.screenOn = true;
        assertTrue(ChannelRules.wanted(w, surround));
        assertFalse("前座舱亮屏也不回来", ChannelRules.wanted(w, front));
        assertTrue("不拍前座舱", ChannelRules.excludedFromPhoto(w, front));
        front.declared.add(recF);
        front.declared.add(pvF);
        assertFalse("前座舱的录像输出不再留着", ChannelRules.keepsRecordOutput(w, front));
        assertTrue(ChannelRules.wantedOutputs(w, front).isEmpty());

        assertTrue("后座舱亮屏回来", ChannelRules.wanted(w, rear));
        assertTrue("后座舱接着录", ChannelRules.keepsRecordOutput(w, rear));
        assertFalse(ChannelRules.excludedFromPhoto(w, rear));
    }

    /** 去掉前座舱之后，超级镜子要后座舱照旧。 */
    @Test
    public void theMirrorStillGetsTheRearCabinAfterTheFrontCabinIsRetired() {
        w.frontCabinOneShot = true;
        w.screenOn = false;
        round();
        assertTrue(w.frontCabinRetired);
        w.screenOn = true;
        w.mirrorCabin = true;
        claim(Holder.MIRROR);
        assertTrue(ChannelRules.wanted(w, rear));
        assertFalse(ChannelRules.wanted(w, front));
    }

    /** 这条规矩没开：熄屏照旧只是放开，两路座舱亮屏都回来。 */
    @Test
    public void withoutTheOneShotRuleTheCabinsComeBackWhenTheScreenTurnsOn() {
        claim(Holder.PREVIEW);
        w.screenOn = false;
        round();
        assertFalse(w.frontCabinRetired);
        assertTrue(retiredEvents().isEmpty());
        w.screenOn = true;
        assertTrue(ChannelRules.wanted(w, rear));
        assertTrue(ChannelRules.wanted(w, front));
    }

    /** 没启用前座舱（环视 + 后座舱）：没有可去掉的，不报；后座舱亮屏照旧回来。 */
    @Test
    public void nothingIsRetiredWhenTheFrontCabinIsNotEnabled() {
        World noFront = new World(Arrays.asList(front, rear, surround),
                new LinkedHashSet<>(Arrays.asList(SUR, REAR)));
        noFront.frontCabinOneShot = true;
        noFront.holders.add(Holder.PREVIEW);
        noFront.screenOn = false;
        noFront.now = T0;
        assertTrue(ChannelRules.settle(noFront).stream()
                .noneMatch(e -> e.type == Event.Type.FRONT_CABIN_RETIRED));
        assertFalse(noFront.frontCabinRetired);
        noFront.screenOn = true;
        assertTrue(ChannelRules.wanted(noFront, rear));
    }

    /** 最近这一轮报的「前座舱去掉了」，每一次是去掉的那一路。 */
    private List<List<String>> retiredEvents() {
        List<List<String>> out = new ArrayList<>();
        for (Event event : events) {
            if (event.type == Event.Type.FRONT_CABIN_RETIRED) {
                out.add(event.keys);
            }
        }
        return out;
    }

    private void claim(Holder holder) {
        if (w.holders.add(holder)) {
            w.changed.add(holder);
        }
    }

    private void drop(Holder holder) {
        if (w.holders.remove(holder)) {
            w.changed.add(holder);
        }
    }

    private void planAll() {
        w.recordPlan.addAll(Arrays.asList(SUR, REAR, FRONT));
    }

    private void declarePreviews() {
        surround.declared.add(pvS);
        rear.declared.add(pvR);
        front.declared.add(pvF);
    }

    private static Set<Out> set(Out... outs) {
        return new LinkedHashSet<>(Arrays.asList(outs));
    }

    /** 一轮：手上那一步怎样了 → 记账 → 下一步（调度在主线程上的那一轮）。 */
    private Decision round() {
        ChannelRules.finish(w, ChannelRules.outcome(w));
        events = ChannelRules.settle(w);
        return ChannelRules.next(w);
    }

    /** 一轮一轮照做（相机当场做完），直到空闲或者要等；返回最后那个答案。 */
    private Decision run() {
        for (int i = 0; i < 30; i++) {
            Decision d = round();
            if (d.move == Move.IDLE || d.move == Move.WAIT) {
                return d;
            }
            perform(d);
        }
        fail("30 步还没停下来");
        return null;
    }

    /**
     * 照做，相机当场做完：开好、配好并出第一帧，或者关完。分两半的重开（第 2b 步）两半都做：关当场关完，等到 300 ms
     * 那一刻再轮一轮出开的那一半，开好出第一帧。
     */
    private void perform(Decision d) {
        startOnly(d);
        for (String key : d.keys) {
            Lane lane = w.lane(key);
            if (d.move == Move.CLOSE) {
                closed(lane);
            } else if (d.half == ChannelRules.Half.CLOSE) {
                closed(lane);
                w.now = Math.max(w.now, w.step.startedAt + ChannelRules.REOPEN_GAP_MS);
                Decision open = round();
                assertEquals("关完、满 300 ms：同一步里下达开的那一半", ChannelRules.Half.OPEN, open.half);
                perform(open);
            } else if (d.move == Move.APPLY || d.rescue == Rescue.REBUILD) {
                configured(lane, d.outputs);
                frame(lane);
            } else {
                opened(lane, d.outputs);
                frame(lane);
            }
        }
    }

    /**
     * 照做，相机这边才开始（在开、在关、在配）。账本上那一轮照 SingleCamera 的样子填（调度每一轮照账本填的那几样）：开（含重开的开那一半、
     * 关着的一路的重开）下达了，这一路手上那一轮有开没返回；关（含重开的关那一半）下达了，那一轮摘下了、在关（被它占着，有一次关没返回）。
     * 重开的开那一半不是新的一步，不记进 {@link #done}。
     */
    private void startOnly(Decision d) {
        assertTrue("只有一步才照做：" + d, d.move != Move.IDLE && d.move != Move.WAIT);
        ChannelRules.begin(w, d);
        if (d.half != ChannelRules.Half.OPEN) {
            done.add(d);
        }
        for (String key : d.keys) {
            Lane lane = w.lane(key);
            lane.busySince = w.now;
            if (d.move == Move.OPEN || d.move == Move.RESCUE && d.rescue == Rescue.REOPEN
                    && d.half != ChannelRules.Half.CLOSE) {
                lane.device = Device.OPENING;
                lane.deviceSince = w.now;
                if (lane.outstandingSince == 0) {
                    lane.outstandingSince = w.now;
                }
            } else if (d.move == Move.CLOSE || d.half == ChannelRules.Half.CLOSE) {
                lane.device = Device.CLOSING;
                lane.deviceSince = w.now;
                // 照 SingleCamera.closeCamera：这一轮下达了关、摘下了 —— 一直有关没返回，直到它结束
                if (lane.outstandingSince == 0) {
                    lane.outstandingSince = w.now;
                }
                lane.closingSince = lane.outstandingSince;
                lane.held = true;
            }
        }
    }

    /**
     * 开不起来（照 SingleCamera：那一轮先结束、再报开不起来 —— 调度看到的时候账本上这一台已经空了，相机层手上也没有动作）。
     */
    private void failedToOpen(Lane lane, CameraTaken.Reason reason) {
        lane.busySince = 0;
        lane.outstandingSince = 0;
        lane.closingSince = 0;
        lane.held = false;
        ChannelRules.openFailed(w, lane, reason);
    }

    private void opened(Lane lane, Set<Out> outputs) {
        lane.device = Device.OPEN;
        lane.deviceSince = w.now;
        lane.outstandingSince = 0;   // 这一轮稳稳开着
        lane.closingSince = 0;
        configured(lane, outputs);
    }

    /** 会话按这一组配好了（新会话：第一帧归零，不在新会话里的死输出跟着没了）。 */
    private void configured(Lane lane, Set<Out> outputs) {
        lane.session.clear();
        lane.session.addAll(outputs);
        lane.dead.retainAll(outputs);
        lane.sessionSince = w.now;
        lane.firstFrameAt = 0;
        lane.progressAgeMs = 0;
        lane.busySince = 0;
    }

    private void frame(Lane lane) {
        if (lane.firstFrameAt == 0) {
            lane.firstFrameAt = w.now;
        }
        lane.progressAgeMs = 0;
    }

    private void closed(Lane lane) {
        lane.device = Device.CLOSED;
        lane.deviceSince = w.now;
        lane.busySince = 0;
        // 关完了、这一轮结束了：账本上这一台空了
        lane.closingSince = 0;
        lane.outstandingSince = 0;
        lane.held = false;
        lane.session.clear();
        lane.dead.clear();
        lane.sessionSince = 0;
        lane.firstFrameAt = 0;
        lane.progressAgeMs = 0;
    }

    /** 一路早就开着、在出画面，会话里带着这几样（出帧口之外的都声明着）；开的时候相机服务说过「被占用（我们开着）」。 */
    private void streaming(Lane lane, Out... outs) {
        lane.device = Device.OPEN;
        lane.deviceSince = w.now - 60_000;
        lane.session.clear();
        lane.session.addAll(Arrays.asList(outs));
        for (Out out : outs) {
            if (out.kind != Kind.SINK) {
                lane.declared.add(out);
            }
        }
        lane.sessionSince = w.now - 60_000;
        lane.firstFrameAt = w.now - 59_000;
        lane.progressAgeMs = 0;
        lane.serviceFree = Boolean.FALSE;
        lane.serviceWordOurs = true;
        lane.serviceWordAt = w.now - 60_000;
    }

    private void streamingAll() {
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
    }

    /** 相机服务报了一句；别人引起的挡「安静」。报了就说明它在。 */
    private void serviceSays(Lane lane, boolean free, boolean ours) {
        lane.serviceFree = free;
        lane.serviceWordOurs = ours;
        lane.serviceWordAt = w.now;
        lane.absent = false;
        if (!ours) {
            w.lastOthersChangeAt = w.now;
        }
    }

    /** 把这一路的梯子推到彻底停手（梯子自己的钟，和 w.now 无关），记下停手的时刻。 */
    private void stopLadder(Lane lane) {
        lane.ladder.released();
        long t = 0;
        while (!lane.ladder.stopped()) {
            CameraLiveness.step(lane.ladder, true, Long.MAX_VALUE, t);
            t += CameraLiveness.COOL_OFF_MS;
            assertTrue("梯子该停手了", t < 100 * CameraLiveness.COOL_OFF_MS);
        }
        lane.stoppedAt = w.now;
    }

    private void assertWait(Why why, String key, Decision d) {
        assertEquals(d.toString(), Move.WAIT, d.move);
        assertEquals(d.toString(), why, d.why);
        assertEquals(d.toString(), key, d.key());
    }

    private int count(Move move) {
        int n = 0;
        for (Decision d : done) {
            if (d.move == move) {
                n++;
            }
        }
        return n;
    }

    private int count(Move move, Lane lane) {
        int n = 0;
        for (Decision d : done) {
            if (d.move == move && d.keys.contains(lane.key)) {
                n++;
            }
        }
        return n;
    }

    private Event event(Event.Type type) {
        for (Event e : events) {
            if (e.type == type) {
                return e;
            }
        }
        return null;
    }

    private List<Event> all(Event.Type type) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.type == type) {
                out.add(e);
            }
        }
        return out;
    }
}
