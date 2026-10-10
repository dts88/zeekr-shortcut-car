package com.kooo.evcam.camera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.blackbox.BlackBox;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 别的程序拿着相机的时候我们怎么办。
 *
 * <h3>实测（2026-09-27，三份日志）</h3>
 *
 * <p>车机上相机 1 和相机 2 同时只能开一路：别的程序一拿相机 1，相机服务几毫秒内就把我们的相机 2
 * 断开；之后我们每次重开都报 {@code ERROR_MAX_CAMERAS_IN_USE}，直到它放开。谁拿得到由相机服务按
 * 进程优先级定 —— 我们在前台它开不了（我们收不到任何信号），我们在后台就是我们被踢。
 * 所以「让路」没有意义：前台让不出去，后台不用让。</p>
 *
 * <h3>规则（项目所有者 2026-09-27 定）</h3>
 *
 * <ul>
 *   <li>它拿着的时候不按退避猛试（每次都失败、还刷日志），只每 {@link #RETRY_WHILE_HELD_MS} 试一次、不计次数
 *       （2.10.10 起这个节奏在看门狗 {@link CameraLiveness} 里，相机自己不再重连）——
 *       这一下是保险：它要是占着很久、而我们是别的原因断的（哨兵模式下相机 1 被车机自己的服务占过两个小时，
 *       我们照常录着），不至于一直不接；</li>
 *   <li>它一放开（相机服务会通知）就接回 —— 2026-10-10 起由看门狗的闸门接（{@link #gate}）：别的程序一路都不占了、
 *       这一路自己空闲满 {@link #QUIET_MS}、所有相机的空闲 / 占用这么久没变过，才单独重开这一路。
 *       以前是相机服务一说放开，就把断掉的几路当场全部重开，不管别的相机是不是正在开、正在关；</li>
 *   <li>相机服务说访问优先级变了（我们回到前台那种）立刻试一次：前台的一方拿得到。</li>
 * </ul>
 *
 * <h3>哪一路算「被拿走」（2026-10-10）</h3>
 *
 * <p>我们的一路被断开、或者报错 1 / 2，那一次关设备关完了才判（{@link #judgeLoss}）：相机服务说这一路被占用、
 * 而我们自己已经不占着它 —— 那就是别的程序拿着；或者报的是 2（相机数到上限）、而别的程序占着另一路 ——
 * 1 和 2 在相机服务里冲突的那种，它自己没人占，是被另一路挡着。都不是就按普通的失败算（看门狗计次数，三次停手）：
 * 2026-10-08 那七次「被断开」全是自己顶自己，相机服务并没说别的程序占着，不能因此不计次数。</p>
 *
 * <p>以前没有这一步：被拿走的那一刻我们还卡在关设备里（5–8 秒），相机服务报「被占用」时设备字段还在，
 * 于是被记成「我们开着」，这一路从来没进过这张表 —— 30 秒的节奏、放开就接回、嫌疑应用都没有它的份
 * （2026-10-10 黑匣子，车主离车时车机拿走后座舱相机 1）。</p>
 *
 * <p>录像那一侧（2026-10-10）：录着的一路被断开时只停这一路，别的路照录（{@link RecordingLanes}）；
 * 它放开、通道安静了，看门狗单独重开它，那一下重开就是这一路单独接回录像。它是最后一路在录的，
 * 才整次停、由主界面按「录像被打断」的路子等环视恢复再接。</p>
 *
 * <h3>2.11 起：只问、不开；判定和放开是纯规则（项目所有者 2026-10-10 确认）</h3>
 *
 * <p>动相机的只剩通道调度一处。一路丢了、或者开不起来，等它关完、再等 300 ms，用
 * {@link #judge(Reason, boolean, boolean, boolean, boolean)} 判 —— 丢失和开失败共用这一个，不再分两套。
 * 判成被别的程序拿着（{@link Verdict#TAKEN}）、被另一路挡着（{@link Verdict#BLOCKED}）的不抢、不试开：
 * 每 {@link #ASK_EVERY_MS} 只问一次相机服务它空没空（只问，不开 —— 以前每 30 秒试开一次，试开在前台
 * 可能把原厂功能挤掉，项目所有者 2026-10-10 同意改掉）。放开了（{@link #releases}）、通道安静 {@link #QUIET_MS} 之后，
 * 按开的顺序单独开它，救援从头算。</p>
 *
 * <p>怎么知道是被拿走的：我们这边关完以后，相机服务对这一路说的最后一句不是「空闲」—— 它还被占着，
 * 那只能是别人；或者这一路干脆不在（10-10 车机拿走后座舱时，相机服务一句话都不说，开的时候相机列表里也没有它）。
 * 上面的账本、闸门、事件入口是 2.10 的做法，调用方都换到通道调度上之后删掉。</p>
 */
public final class CameraTaken {

    /** 别的程序占着相机时多久试一次（看门狗 {@link CameraLiveness#step} 用）。 */
    static final long RETRY_WHILE_HELD_MS = 30_000L;

    /**
     * 被拿着的一路（{@link Verdict#TAKEN} / {@link Verdict#BLOCKED}）有人要时，多久问一次相机服务它空没空。
     *
     * <p>只问、不开（项目所有者 2026-10-10 同意把「30 秒试开一次」改成这样）：问的是进程里的缓存 ——
     * 注册一个新的可用性回调、收它回放的现状再注销，不进相机服务、不开任何相机。它补的只是我们漏收的那几声，
     * 不会比相机服务自己的通知更新；节奏照旧是 30 秒。</p>
     */
    static final long ASK_EVERY_MS = 30_000L;

    /**
     * 通道要安静多久才开、改输出、救（项目所有者 2026-10-10 确认）：相机服务这么久没报过<b>别人引起的</b>变化
     * （{@link CameraAvailabilityWatch#classify}）。
     *
     * <p>交接的时候相机服务一连串地报（2026-10-10：车机拿走相机 1 的同一毫秒，还报了我们正开着的 0 和 2「空闲」），
     * 那几秒里再动一路，就是在交接的中途动通道 —— 通道规则是一次只变一路、变完一步再下一步。</p>
     *
     * <p>2.11 起只算别人引起的：我们自己开、关一路时它报的那几声不挡。旧的闸门（{@link #gate}，只管被拿走的那一路）
     * 算的是所有相机多久没变过，连我们自己开关时它报的那几声都算 —— 2.11 开、改输出、救都要等安静，照那个算法，
     * 按次序开每一路都要多等 3 秒。旧的闸门还按旧的算法喂它，数值没变。</p>
     */
    static final long QUIET_MS = 3_000L;

    /**
     * 别的程序拿走一路时，相机服务那一声「被占用」最多比我们收到断开早多久还算是它。被拿走时相机服务先报这一路空闲
     * （我们被踢了）、紧接着报被占用（新主人连上了）；这两声和断开不在同一条线上到，被占用可能先到 ——
     * 那时设备还算我们的，被记成了「我们开着」。
     */
    static final long TAKE_LINK_MS = 5_000L;

    /** 丢了一路、关完之后的判定，见 {@link #judge}。 */
    enum Loss {
        /** 相机服务没说别的程序占着：按普通的失败算，看门狗计次数、三次停手。 */
        ORDINARY,
        /** 这一路被别的程序拿着。 */
        HELD,
        /** 这一路自己没人占，是别的程序占着的另一路把它挡住了（报错 2，1 和 2 冲突的那种）。 */
        BLOCKED
    }

    /** 被拿走的一路，看门狗这一次检查怎么办，见 {@link #gate}。 */
    enum Gate {
        /** 通道还在变：这一次不动这一路。 */
        WAIT,
        /** 还被占着（或者相机服务的说法对不上）：按 {@link #RETRY_WHILE_HELD_MS} 的节奏试一下，不计次数。 */
        PROBE,
        /** 放开了、通道也安静了：现在单独重开。 */
        REOPEN
    }

    private static final Book BOOK = new Book();

    private CameraTaken() {
    }

    // ================================================================= 纯规则

    /**
     * 丢了一路、关完之后，相机服务的表对它怎么说：true 空闲、false 被别的程序占着、null 说不准。
     *
     * <p>「被占用」当时就认出不是我们的，或者是在丢之前 {@link #TAKE_LINK_MS} 以内才报的，算别的程序占着。
     * 更早报的、记成「我们开着」的，是我们自己开着它时的旧账：被断开之后相机服务那一声「空闲」可能还在路上，
     * 不能因此当成被拿走 —— 不然自己被断开也不计次数了（2026-10-08 那七次都是自己顶自己）。</p>
     *
     * @param available    表上最近一次的状态：true 空闲、false 被占用、null 没收到过
     * @param recordedOurs 那一声报的时候记成了我们开着
     * @param sinceMs      那一声的时刻（开机起算，含深睡）
     * @param lostAt       丢的那一刻（同一个钟）
     */
    static Boolean serviceSays(Boolean available, boolean recordedOurs, long sinceMs, long lostAt) {
        if (!Boolean.FALSE.equals(available)) {
            return available;
        }
        if (!recordedOurs || sinceMs >= lostAt - TAKE_LINK_MS) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 我们的一路丢了（被断开、报错 1 / 2），那一次关也关完了：是不是被别的程序拿走的。
     *
     * @param error             相机层报的错：-4 被断开，或者 {@code onError} 的错误码
     * @param available         相机服务的表对它怎么说（{@link #serviceSays}）：true 空闲、false 被别的程序占着、null 说不准
     * @param oursNow           我们此刻又占着、或者正在开这一路（另一次打开排在后面）：被占用说的是我们自己
     * @param othersHoldAnother 别的程序此刻占着另外哪一路
     */
    static Loss judge(int error, Boolean available, boolean oursNow, boolean othersHoldAnother) {
        if (oursNow) {
            return Loss.ORDINARY;
        }
        if (Boolean.FALSE.equals(available)) {
            return Loss.HELD;
        }
        if (error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE && othersHoldAnother) {
            return Loss.BLOCKED;
        }
        return Loss.ORDINARY;
    }

    /**
     * 被拿走的这一路，有人要画面时看门狗这一次检查怎么办 —— 它只能从这里重开，别处（按次序开相机、后视镜）一律跳过。
     *
     * <p>放开不看「这一路自己的空闲」一个事件，而看两样此刻的状态：别的程序一路都不占了（1 和 2 冲突时，
     * 相机 1 放开也就放开了被它挡着的 2 —— 2 自己从头到尾都是空闲），这一路自己空闲满 {@link #QUIET_MS}；
     * 再加上所有相机这么久没变过。相机服务的说法对不上（别的程序都不占了、这一路却还报被占用）时
     * 照 {@link #RETRY_WHILE_HELD_MS} 的节奏试，不至于永远不接。</p>
     *
     * @param othersHoldAny     别的程序此刻占着哪一路（{@link #othersHold()}）
     * @param ownAvailableForMs 相机服务说这一路空闲了多久；被占用、没收到过都是负数
     * @param quietForMs        所有相机的空闲 / 占用多久没变过
     */
    static Gate gate(boolean othersHoldAny, long ownAvailableForMs, long quietForMs) {
        if (quietForMs < QUIET_MS) {
            return Gate.WAIT;
        }
        if (!othersHoldAny && ownAvailableForMs >= QUIET_MS) {
            return Gate.REOPEN;
        }
        return Gate.PROBE;
    }

    // ================================================================= 2.11 的纯规则：丢失和开失败共用一个判法

    /** 一路丢了（关完了）、或者开不起来之后的判定，见 {@link #judge(Reason, boolean, boolean, boolean, boolean)}。 */
    public enum Verdict {
        /**
         * 被别的程序拿着：不抢，每 {@link CameraTaken#ASK_EVERY_MS} 问一次相机服务（只问，不开），
         * 它说空闲了（{@link CameraTaken#releases}）、通道安静了再单独开。不计救援的次数。
         */
        TAKEN,
        /**
         * 它自己空着却开不起来，另一路是 {@link #TAKEN}：相机 1、2 在相机服务里冲突，一路被别人拿着，另一路也开不了
         * （2026-09-27 实测）。也算被拿着，不计次数，等再也没有 TAKEN 的路再开。
         */
        BLOCKED,
        /**
         * 现在用不了相机（{@code onError} 3、{@code CameraAccessException.CAMERA_DISABLED}；多半是在后台、
         * 前台服务又是后台才起的那种）：不计次数，记下「用不了」，等主界面回到前台。
         */
        UNUSABLE,
        /** 普通故障：算救援梯子的一次（{@link CameraLiveness}），按 12 秒的节奏救。 */
        ORDINARY
    }

    /**
     * 一路丢了、或者开不起来，相机层报的原因：从哪儿来的、码是几。
     *
     * <p>只给一个整数不够：几种来源的数字是重叠的 —— {@code onError} 的 1 是「被占用」，{@code CameraAccessException}
     * 的 1 却是「用不了相机」；{@code onError} 的 3 是「用不了相机」，{@code CameraAccessException} 的 3 只是普通的相机错误；
     * 不在相机列表里、别的异常又各是一种。判法要的是「属于哪一类」，所以来源跟着码一起走。</p>
     *
     * <p>只是数据，不带中文：黑匣子里的叫法由 {@link SingleCamera} / {@link MultiCameraManager} 照
     * {@link #source}、{@link #code} 拼。</p>
     */
    public static final class Reason {

        /** 原因从哪儿来。 */
        public enum Source {
            /** 相机服务把我们断开（{@code onDisconnected}）。 */
            DISCONNECTED,
            /** 设备报错（{@code CameraDevice.StateCallback.onError}），码是 1–5。 */
            DEVICE_ERROR,
            /** 打开时抛的 {@code CameraAccessException}，码是 {@code getReason()}。 */
            ACCESS,
            /** 打开时这一路不在相机列表里（{@code getCameraIdList()} 没有它）。 */
            NOT_LISTED,
            /** 别的异常（{@code SecurityException}、{@code IllegalArgumentException} …），码没有意义。 */
            OTHER
        }

        /** {@link SingleCamera} 把「被相机服务断开」报成的码：{@code onError} 的码都是正的，所以借一个负数。 */
        public static final int DISCONNECTED_CODE = -4;

        public final Source source;
        /** {@link Source#DEVICE_ERROR} 是 {@code onError} 的码，{@link Source#ACCESS} 是 {@code getReason()}；别的来源没有意义。 */
        public final int code;
        /** {@link Source#OTHER} 是哪一种异常（类名），只给日志看；别的来源是 null。 */
        public final String detail;

        private Reason(Source source, int code, String detail) {
            this.source = source;
            this.code = code;
            this.detail = detail;
        }

        public static Reason ofDisconnect() {
            return new Reason(Source.DISCONNECTED, DISCONNECTED_CODE, null);
        }

        public static Reason ofDeviceError(int error) {
            return new Reason(Source.DEVICE_ERROR, error, null);
        }

        public static Reason ofAccess(int reason) {
            return new Reason(Source.ACCESS, reason, null);
        }

        public static Reason ofNotListed() {
            return new Reason(Source.NOT_LISTED, 0, null);
        }

        /** @param exceptionName 异常的类名，只给日志看 */
        public static Reason ofException(String exceptionName) {
            return new Reason(Source.OTHER, 0, exceptionName);
        }

        /**
         * 丢失那条路报的码换成原因：{@link #DISCONNECTED_CODE} 是被断开，其余是 {@code onError} 的码
         * （{@link SingleCamera} 今天就是这么报的）。
         */
        public static Reason ofLoss(int code) {
            return code == DISCONNECTED_CODE ? ofDisconnect() : ofDeviceError(code);
        }

        /**
         * 属于「被占用」一类：被断开；{@code onError} 1 IN_USE、2 MAX_CAMERAS_IN_USE；{@code CameraAccessException} 的
         * CAMERA_IN_USE、MAX_CAMERAS_IN_USE、CAMERA_DISCONNECTED。另一路被别人拿着时，
         * 只有这一类才说明是被它挡着。
         *
         * <p>设备错误 4 不在这一类：10-10 08:57 实测，出错 2 秒后重开就成功了，它和谁占着无关。</p>
         */
        public boolean inUseKind() {
            switch (source) {
                case DISCONNECTED:
                    return true;
                case DEVICE_ERROR:
                    return code == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                            || code == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                case ACCESS:
                    return code == CameraAccessException.CAMERA_IN_USE
                            || code == CameraAccessException.MAX_CAMERAS_IN_USE
                            || code == CameraAccessException.CAMERA_DISCONNECTED;
                default:
                    return false;
            }
        }

        /** 现在用不了相机：{@code onError} 3（ERROR_CAMERA_DISABLED）、{@code CameraAccessException.CAMERA_DISABLED}。 */
        public boolean unusable() {
            return source == Source.DEVICE_ERROR && code == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
                    || source == Source.ACCESS && code == CameraAccessException.CAMERA_DISABLED;
        }

        /**
         * 码的短名，给日志和黑匣子拼句子用：{@code MAX_CAMERAS_IN_USE}、{@code CAMERA_DEVICE}、{@code DISCONNECTED}、
         * {@code NOT_LISTED}、异常的类名；认不出的码就是数字本身。{@code onError} 和 {@code CameraAccessException}
         * 都有 MAX_CAMERAS_IN_USE，要分来源时看 {@link #source}。
         */
        public String codeName() {
            switch (source) {
                case DISCONNECTED:
                    return "DISCONNECTED";
                case NOT_LISTED:
                    return "NOT_LISTED";
                case OTHER:
                    return detail != null ? detail : "EXCEPTION";
                case DEVICE_ERROR:
                    switch (code) {
                        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                            return "CAMERA_IN_USE";
                        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                            return "MAX_CAMERAS_IN_USE";
                        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                            return "CAMERA_DISABLED";
                        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                            return "CAMERA_DEVICE";
                        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                            return "CAMERA_SERVICE";
                        default:
                            return String.valueOf(code);
                    }
                case ACCESS:
                    switch (code) {
                        case CameraAccessException.CAMERA_DISABLED:
                            return "CAMERA_DISABLED";
                        case CameraAccessException.CAMERA_DISCONNECTED:
                            return "CAMERA_DISCONNECTED";
                        case CameraAccessException.CAMERA_ERROR:
                            return "CAMERA_ERROR";
                        case CameraAccessException.CAMERA_IN_USE:
                            return "CAMERA_IN_USE";
                        case CameraAccessException.MAX_CAMERAS_IN_USE:
                            return "MAX_CAMERAS_IN_USE";
                        default:
                            return String.valueOf(code);
                    }
                default:
                    return String.valueOf(code);
            }
        }

        /** 日志用：{@code onError 2 MAX_CAMERAS_IN_USE}、{@code CameraAccessException 5 MAX_CAMERAS_IN_USE}、{@code onDisconnected} …… */
        @Override
        public String toString() {
            switch (source) {
                case DISCONNECTED:
                    return "onDisconnected";
                case DEVICE_ERROR:
                    return "onError " + code + " " + codeName();
                case ACCESS:
                    return "CameraAccessException " + code + " " + codeName();
                case NOT_LISTED:
                    return "not in getCameraIdList";
                default:
                    return codeName();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Reason)) {
                return false;
            }
            Reason other = (Reason) o;
            return source == other.source && code == other.code && Objects.equals(detail, other.detail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, code, detail);
        }
    }

    /**
     * 一路丢了（我们这边关完了）、或者开不起来（等它关完了；根本没开起来的不用等）之后，再等 300 ms：
     * 是被别的程序拿着、被另一路挡着、现在用不了相机，还是普通故障（项目所有者 2026-10-10 确认）。按顺序判：
     *
     * <ol>
     *   <li>这一路不在（相机服务的缓存里没有它、问的时候回放里也没有；或者开的时候不在相机列表里）→ TAKEN。
     *       10-10 车机拿着后座舱相机 1 时就是这样：相机服务一声不吭，开的时候列表里干脆没有它；</li>
     *   <li>现在用不了相机 → UNUSABLE；</li>
     *   <li>相机服务从没说过话（容器没把可用性回调转过来）→ 普通故障：没有依据，照计数的路子救；</li>
     *   <li>相机服务对这一路说的最后一句不是「空闲」→ TAKEN：我们这边已经关完了，它还被占着，只能是别人；</li>
     *   <li>最后一句是「空闲」、原因属于「被占用」一类（{@link Reason#inUseKind()}）、另一路是 TAKEN → BLOCKED；</li>
     *   <li>其余 → 普通故障。</li>
     * </ol>
     *
     * <p>「最后一句」不论是多早说的（项目所有者 2026-10-11 确认这样读）：AOSP 对「空闲 → 空闲」不再回调 ——
     * 10-10 08:56:57 那句假的「2 空闲」之后，我们关 2 时相机服务就没再说话；只认关完前后那几句的话，那一次的错误 4
     * 就会被判成被拿走。「空闲」有时比关返回还早几毫秒到（10-10 07:51:16，早 5 ms），等关完再判就不会漏。</p>
     *
     * <p>几路一起待判时（同一次交接里断了两路），调用方先判出 TAKEN 的，再拿它当 {@code anotherTaken} 判别的：
     * 09-27 那次相机 1 被拿走、我们的相机 2 跟着被断开，这样 2 直接判 BLOCKED，一次也不试开
     * （项目所有者 2026-10-11 确认；试开在前台可能把对方挤掉）。</p>
     *
     * @param reason          相机层报的原因；null 当作认不出的异常
     * @param heardAnything   相机服务的可用性回调收到过没有（哪一路都算）
     * @param serviceSaysFree 相机服务对这一路说的最后一句是「空闲」
     * @param absent          这一路不在：相机服务的缓存里没有它，回放里也没有它
     * @param anotherTaken    另外有一路此刻判的是 TAKEN
     */
    static Verdict judge(Reason reason, boolean heardAnything, boolean serviceSaysFree, boolean absent,
                         boolean anotherTaken) {
        if (absent || reason != null && reason.source == Reason.Source.NOT_LISTED) {
            return Verdict.TAKEN;
        }
        if (reason != null && reason.unusable()) {
            return Verdict.UNUSABLE;
        }
        if (!heardAnything) {
            return Verdict.ORDINARY;
        }
        if (!serviceSaysFree) {
            return Verdict.TAKEN;
        }
        if (reason != null && reason.inUseKind() && anotherTaken) {
            return Verdict.BLOCKED;
        }
        return Verdict.ORDINARY;
    }

    /**
     * 被拿着的一路此刻算不算放开了。
     *
     * <ul>
     *   <li>{@link Verdict#TAKEN}：等相机服务说它「空闲」（而且它在）；</li>
     *   <li>{@link Verdict#BLOCKED}：等再也没有 TAKEN 的路 —— 它自己从头到尾都空着，那一声「空闲」不会再来，
     *       它等的是挡着它的那一路放开（09-27：1 放开了，2 才开得起来）。</li>
     * </ul>
     *
     * <p>放开之后不是马上开：等通道安静 {@link #QUIET_MS}，按开的顺序单独开，救援梯子从头算 ——
     * 那是通道调度的事，这里只答「放开没有」。别的判定（普通故障、用不了）本来就不算被拿着，一律 false。</p>
     *
     * @param mark            这一路现在的判定
     * @param serviceSaysFree 相机服务对这一路说的最后一句是「空闲」
     * @param absent          这一路不在
     * @param anyTaken        此刻还有哪一路判的是 TAKEN
     */
    static boolean releases(Verdict mark, boolean serviceSaysFree, boolean absent, boolean anyTaken) {
        if (mark == Verdict.TAKEN) {
            return serviceSaysFree && !absent;
        }
        if (mark == Verdict.BLOCKED) {
            return !anyTaken;
        }
        return false;
    }

    /**
     * 账本：别的程序占着哪几路（相机服务报的，全局一张），我们的哪几路被拿走了（每一路自己一条）。
     *
     * <p>全局那张表的规矩不变：别的程序一路都不占了才算真放开 —— 1 和 2 冲突时，2 自己没人占，
     * 等的是 1 放开。纯数据，见 {@code CameraTakenTest}；进程里只有 {@link #BOOK} 一份。</p>
     */
    static final class Book {
        private final Set<String> held = ConcurrentHashMap.newKeySet();
        /** 我们被拿走的那几路：相机 id → 从什么时候起（开机起算，含深睡）、是不是被另一路挡着。 */
        private final Map<String, Bench> benched = new ConcurrentHashMap<>();

        boolean othersHold() {
            return !held.isEmpty();
        }

        /** 别的程序此刻占着除这一路以外的哪一路。 */
        boolean othersHoldOtherThan(String cameraId) {
            for (String id : held) {
                if (!id.equals(cameraId)) {
                    return true;
                }
            }
            return false;
        }

        Set<String> heldIds() {
            return new TreeSet<>(held);
        }

        /** @return true：这一路原来不在表上 */
        boolean othersTook(String cameraId) {
            return held.add(cameraId);
        }

        /** @return true：划掉这一路之后别的程序一路都不占了 —— 这一下才是真放开 */
        boolean released(String cameraId) {
            return held.remove(cameraId) && held.isEmpty();
        }

        boolean benched(String cameraId) {
            return benched.containsKey(cameraId);
        }

        boolean anyBenched() {
            return !benched.isEmpty();
        }

        /** 记下我们这一路被拿走了。已经在表上的（试的那一下又没拿到）从最初那一刻算起。 */
        void bench(String cameraId, long sinceMs, boolean blocked) {
            Bench before = benched.get(cameraId);
            benched.put(cameraId, new Bench(before != null ? before.sinceMs : sinceMs, blocked));
        }

        /** @return 被拿走了多久（毫秒）；-1 = 本来就不在表上 */
        long unbench(String cameraId, long nowMs) {
            Bench bench = benched.remove(cameraId);
            return bench == null ? -1 : Math.max(0, nowMs - bench.sinceMs);
        }

        /** 给日志看：{@code 1, 2<-[1]}（2 被别的程序占着的 1 挡着）。 */
        String describeBenched() {
            StringBuilder sb = new StringBuilder();
            for (String id : new TreeSet<>(benched.keySet())) {
                Bench bench = benched.get(id);
                sb.append(sb.length() > 0 ? ", " : "").append(id)
                        .append(bench != null && bench.blocked ? "<-" + heldIds() : "");
            }
            return sb.toString();
        }
    }

    private static final class Bench {
        final long sinceMs;
        final boolean blocked;

        Bench(long sinceMs, boolean blocked) {
            this.sinceMs = sinceMs;
            this.blocked = blocked;
        }
    }

    // ================================================================= 对外

    /** 此刻有没有别的程序占着相机（哪一路都算）。 */
    public static boolean othersHold() {
        return BOOK.othersHold();
    }

    /** 别的程序占着哪几路、我们哪几路被拿走了，给日志看：{@code [1]}，有被拿走的再跟 {@code  out=1, 2<-[1]}。 */
    public static String describe() {
        String benched = BOOK.describeBenched();
        return BOOK.heldIds() + (benched.isEmpty() ? "" : " out=" + benched);
    }

    /**
     * 我们的这一路被别的程序拿走了、还没接回：按次序开相机、后视镜都不开它，只有看门狗的闸门（{@link #gate}）重开它。
     */
    public static boolean benched(String cameraId) {
        return BOOK.benched(cameraId);
    }

    /**
     * 这一路不算被拿走了：看门狗的闸门开了、单独重开它，或者试的那一下拿到了、出了画面（主线程）。
     *
     * @return 被拿走了多久（毫秒）；-1 = 本来就不在表上
     */
    static long unbench(String cameraId) {
        return BOOK.unbench(cameraId, SystemClock.elapsedRealtime());
    }

    /** 相机服务报：这一路被别的程序拿了。 */
    static void othersTook(String cameraId) {
        BOOK.othersTook(cameraId);
    }

    /** 相机服务报：这一路空出来了。别的程序一路都不占了，被拿走的那几路下一次检查就看闸门。 */
    static void othersReleased(String cameraId) {
        if (BOOK.released(cameraId)) {
            retryNow(cameraId);
        }
    }

    /**
     * 我们把这一路开成了（相机线程）：别的程序这会儿不占着它 —— 前台的一方拿得到。相机服务这时报不报
     * 「空出来了」都算不上放开（我们正开着它，见 {@link CameraAvailabilityWatch} 里「乱报」那一条），
     * 所以表上要我们自己划掉，不然被它挡着的另一路一直等。
     */
    static void ourCameraOpened(String cameraId) {
        if (BOOK.released(cameraId)) {
            retryNow(cameraId);
        }
    }

    /** 相机服务说访问优先级变了（前后台切换那种）：还有被占着、被拿走的，趁机试一次。 */
    static void prioritiesChanged() {
        if (BOOK.othersHold() || BOOK.anyBenched()) {
            retryNow(null);
        }
    }

    /**
     * 我们的一路丢了（被断开、报错 1 / 2），那一次关设备也关完了（主线程，{@link SingleCamera} 关完之后 post 过来）：
     * 是不是被别的程序拿走的，见 {@link #judge}。
     *
     * <p>放在关完之后、挪到主线程上判：关完了我们才算不占着它；相机服务那几声「空闲 / 被占用」也是在主线程上收的，
     * 排在这之前的都已经记进表里。是的话记进账本、相机服务的表改成「被占用、不是我们」；那一声「被占用」当时没被
     * 认成别的程序的（比断开到得早，设备还算我们的；或者被占用只是换了主人、根本没有那一声），就按丢的那一刻
     * 补记争用、查嫌疑应用。</p>
     *
     * @param who       黑匣子里的叫法：编号加环视 / 前座舱 / 后座舱
     * @param errorName 报的什么错（短名）
     * @param lostAt    丢的那一刻（开机起算，含深睡）
     * @param closeMs   从丢到关完用了多久
     */
    static void judgeLoss(String cameraId, String who, int error, String errorName, long lostAt, long closeMs) {
        boolean oursNow = CameraAvailabilityWatch.weHoldOrOpen(cameraId);
        Set<String> held = BOOK.heldIds();
        Loss loss = judge(error, CameraAvailabilityWatch.statusAfterLoss(cameraId, lostAt), oursNow,
                BOOK.othersHoldOtherThan(cameraId));
        boolean wasBenched = BOOK.benched(cameraId);
        if (loss == Loss.ORDINARY) {
            boolean unbenched = BOOK.unbench(cameraId, SystemClock.elapsedRealtime()) >= 0;
            BlackBox.noteImportant("相机 " + who + " 丢了（" + errorName + "，到关完 " + closeMs
                    + "ms），相机服务没说别的程序占着" + (oursNow ? "（我们又在开它）" : "")
                    + "：按普通的失败算，看门狗计次数重开" + (unbenched ? "；不再算被拿走" : ""));
            return;
        }
        if (loss == Loss.HELD) {
            CameraAvailabilityWatch.markTaken(cameraId);
            if (BOOK.othersTook(cameraId)) {
                CameraContention.othersTookAt(cameraId, lostAt);
            }
        }
        BOOK.bench(cameraId, lostAt, loss == Loss.BLOCKED);
        if (wasBenched) {
            BlackBox.noteImportant("相机 " + who + " 试开没成（" + errorName + "，到关完 " + closeMs + "ms）："
                    + (loss == Loss.HELD ? "还被别的程序拿着" : "还被别的程序占着的相机 " + held + " 挡着")
                    + "，" + (RETRY_WHILE_HELD_MS / 1000) + " 秒后再试");
            return;
        }
        BlackBox.noteImportant("相机 " + who + " 被拿走（" + errorName + "，到关完 " + closeMs + "ms）："
                + (loss == Loss.HELD ? "相机服务说别的程序拿着它"
                : "它自己没人占，别的程序占着的相机 " + held + " 挡着它（相机数到上限）")
                + "。看门狗不再按次数重开：别的程序一路都不占、通道安静 " + (QUIET_MS / 1000)
                + " 秒后单独重开它，等的时候每 " + (RETRY_WHILE_HELD_MS / 1000) + " 秒试一次");
    }

    /**
     * 叫看门狗下一次检查就看被拿走的那几路（不在这里重开：重开只走闸门，见 {@link #gate}）。
     *
     * @param releasedCameraId 放开的那一路；优先级变了那种传 null
     */
    private static void retryNow(String releasedCameraId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> retryNow(releasedCameraId));
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager != null) {
            manager.retryTaken(releasedCameraId);
        }
    }
}
