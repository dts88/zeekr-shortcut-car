package com.kooo.evcam.camera;

/**
 * 一路信号的心跳：最后一次是什么时候、一共几次、此刻在忙什么。
 *
 * <p>给 {@link StallWatch} 用。{@link #beat} 在出帧回调里调用，一秒几十次，
 * 所以只写几个字段：不加锁、不分配。每个实例只有一个线程在写心跳
 * （出帧回调所在的那个线程），监测线程只读 —— 读到的值差一帧无所谓。</p>
 *
 * <p>时间由调用方传进来（{@link StallWatch#now()}），单元测试可以喂一段确定的时间轴。</p>
 */
public final class Heartbeat {

    private volatile long lastMs;
    private volatile long count;
    private volatile boolean armed;

    private volatile String task;
    private volatile long taskSinceMs;

    private volatile String slowestOp;
    private volatile long slowestOpMs = -1L;

    // 只有监测线程读写：上次算速率时的基准
    private long markMs = -1L;
    private long markCount;

    /** 来了一次。 */
    public void beat(long nowMs) {
        lastMs = nowMs;
        count++;
    }

    /**
     * 开始盯这一路。
     *
     * @param graceMs 给它多久来第一次：相机刚接上、会话还在建的时候没有帧是正常的
     */
    public void arm(long nowMs, long graceMs) {
        rebase(nowMs, graceMs);
        armed = true;
    }

    /**
     * 重新计时，但不改变盯不盯。
     *
     * <p>亮屏、窗口重新可见的时候用：之前没画面是正常的，从现在起重新算。
     * 不能顺手把 armed 置上 —— 监测线程和解绑窗口的主线程会抢，抢输了就会去盯一个已经解绑的窗口。</p>
     */
    public void rebase(long nowMs, long graceMs) {
        lastMs = nowMs + Math.max(0L, graceMs);
    }

    public void disarm() {
        armed = false;
    }

    public boolean isArmed() {
        return armed;
    }

    /** 距上一次心跳多久；还在宽限期里时是负数。 */
    public long ageMs(long nowMs) {
        return nowMs - lastMs;
    }

    public long count() {
        return count;
    }

    /** 开始一件会占住这个线程一阵子的事（例如分段切换）。 */
    public void beginTask(String name, long nowMs) {
        taskSinceMs = nowMs;
        task = name;
    }

    public void endTask() {
        task = null;
    }

    /** 正在做的事；没有时为 null。 */
    public String task() {
        return task;
    }

    public long taskAgeMs(long nowMs) {
        return nowMs - taskSinceMs;
    }

    /** 记一次操作的用时，只留最慢的那次。 */
    public void noteOp(String name, long tookMs) {
        if (tookMs > slowestOpMs) {
            slowestOpMs = tookMs;
            slowestOp = name;
        }
    }

    /** 取出上次取之后最慢的那次操作，形如 {@code "write 85ms"}；没有记录时返回 null。 */
    public String takeSlowestOp() {
        String name = slowestOp;
        long took = slowestOpMs;
        slowestOp = null;
        slowestOpMs = -1L;
        return name == null ? null : name + " " + took + "ms";
    }

    /** 从上次调用到现在平均每秒几次。第一次调用只立基准，返回 -1。 */
    public float takeRate(long nowMs) {
        long c = count;
        if (markMs < 0 || nowMs <= markMs) {
            markMs = nowMs;
            markCount = c;
            return -1f;
        }
        float rate = (c - markCount) * 1000f / (nowMs - markMs);
        markMs = nowMs;
        markCount = c;
        return rate;
    }
}
