package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账本：进程里每台相机此刻还没结束的那一轮（{@link CameraRound}），按相机编号（第 1 步「轮次 + 账本」，项目所有者 2026-10-29 批）。
 * 纯 Java，见 {@code CameraRoundsTest}。
 *
 * <p>两条保证都落在这里：一台相机我们上一次关它（或者开它）还没返回，谁都不再开它，直到那一轮结束、或者进程结束 —— 不设时限，
 * 相机服务说它「空闲」也不算数（项目所有者 2026-10-28、10-29 Q1）；进程里同一台相机永远不会有两个句柄。办法只有一个：
 * 下达开之前看账本，这一台有一轮还没结束就不下达（{@link #issue}）—— 谁的都算：别的管线的、这一份上一次开的。</p>
 *
 * <p>谁读：每一份调度（管线重建时可能有两份）在下达任何一次开之前照它判（ChannelRules.camHeld：不开、不救、关着的不重开）；
 * 相机层的开、重开再看一次，是断言（SingleCamera：不该走到那里，走到了就不开、记一行）；相机服务的那几声认人
 * （SingleCamera.holdsOrIsOpening）；退出的看门线程（{@link #closingIds}，MainActivity.finishExit）。第 2 步原样搬进唯一的那一个调度。</p>
 *
 * <p>线程：只在主线程上改 —— 下达（{@link #issue}，SingleCamera 的开、关着的一路的重开）、结束（{@link #end}，相机线程报上来的
 * 那一声 post 到主线程）。读的方法哪个线程都能调。</p>
 */
public final class CameraRounds {

    private static final CameraRounds PROCESS = new CameraRounds();

    /** 进程里的那一本（SingleCamera 用它；调度默认也用它，测试里换一本新的）。 */
    public static CameraRounds process() {
        return PROCESS;
    }

    private final Map<String, CameraRound> live = new ConcurrentHashMap<>();
    /**
     * 每台相机我们最近一次关完的时刻（调度的钟）：一轮在下达了关、或者丢了之后结束的那一下（{@link CameraRound#closedOut}），
     * 谁的那一轮都算。相机服务为这一关说的话从 binder 线程上来，可能晚于这一下 —— 之后 OWN_WORD_GRACE_MS 以内还算我们引起的：
     * 管线重建、换上新的一份之后也一样（第 2 步「只有一个调度」，ChannelScheduler.ourStepInFlight、ChannelRules.stillOurs）。
     */
    private final Map<String, Long> closedAt = new ConcurrentHashMap<>();
    /** 下达过几轮（主线程）：轮次号照它数，进程里唯一。 */
    private int issued;

    /** 进程里只有 {@link #process()} 那一本；测试里各建一本（几份假相机、两份调度共用）。 */
    CameraRounds() {
    }

    /**
     * 主线程：下达一轮。这一台账上有一轮还没结束，不下达，返回 null（谁的都算）。
     *
     * @param owner    下达它的那一份相机对象（凭它认「是不是这一份的」）
     * @param ownerKey 那一份是哪一路（诊断报告用）
     * @param now      调度的钟
     */
    CameraRound issue(String cameraId, Object owner, String ownerKey, long now) {
        if (cameraId == null || live.containsKey(cameraId)) {
            return null;
        }
        CameraRound round = new CameraRound(++issued, cameraId, owner, ownerKey, now);
        live.put(cameraId, round);
        return round;
    }

    /**
     * 主线程：这一轮结束了（相机线程判的那一下 post 过来的）：从账上划掉，这一台又能开了；是一次关做完才结束的，记下这一刻
     * （{@link #closedAt}）。不是账上那一轮（重复的那一声）什么都不做，返回 false。
     *
     * @param now 调度的钟
     */
    boolean end(CameraRound round, long now) {
        if (round == null || !live.remove(round.cameraId, round)) {
            return false;
        }
        if (round.closedOut()) {
            closedAt.put(round.cameraId, now);
        }
        return true;
    }

    /** 这一台此刻还没结束的那一轮；没有是 null。哪个线程都能读。 */
    public CameraRound live(String cameraId) {
        return cameraId == null ? null : live.get(cameraId);
    }

    /** 这一台我们最近一次关完的时刻（{@link #end} 记的，调度的钟）；进程里还没有过是 0。哪个线程都能读。 */
    public long closedAt(String cameraId) {
        Long at = cameraId == null ? null : closedAt.get(cameraId);
        return at == null ? 0 : at;
    }

    /** 这一轮还在账上（还没结束）。 */
    public boolean isLive(CameraRound round) {
        return round != null && live.get(round.cameraId) == round;
    }

    /** 还有一轮没结束的那几台，按相机编号排好。哪个线程都能读。 */
    public List<String> liveIds() {
        List<String> ids = new ArrayList<>(live.keySet());
        Collections.sort(ids);
        return ids;
    }

    /**
     * 这一轮里有一次关没返回的那几台（在关、在丢），按相机编号排好：退出的看门线程等它们（MainActivity.finishExit）。
     * 稳稳开着、在开的不在这里 —— 退出时调度会先关它们，那时它们就在这里了；退出途中人又打开了 App，新管线开着的那几台不让退出一直等。
     * 哪个线程都能读。
     */
    public List<String> closingIds() {
        List<String> ids = new ArrayList<>();
        for (CameraRound round : live.values()) {
            if (round.status().closing()) {
                ids.add(round.cameraId);
            }
        }
        Collections.sort(ids);
        return ids;
    }
}
