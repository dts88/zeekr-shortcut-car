package com.kooo.evcam.settings;

/**
 * 开发者选项的解锁状态。
 *
 * <h3>刻意不持久化</h3>
 *
 * <p>解锁只存在这个静态字段里，<b>不写任何配置文件</b>。所以：应用更新后失效、
 * 进程冷启动后失效 —— 因为进程一换，这个字段就回到 false。
 * 而从后台切回前台不算，那时进程还是原来那个。</p>
 *
 * <p>这正是想要的行为：这些选项要么是没做完的（三路、自定义），
 * 要么是排查用的（权限设置、日志上传、补盲、超视）。让它们默认藏着，
 * 需要时临时打开，用完随手一关就恢复原状 —— 不会有人在某次调试之后
 * 忘了关，然后一直带着一堆半成品选项开车。</p>
 */
public final class DeveloperMode {

    /** 解锁需要在「安全须知」上点这么多下。 */
    public static final int TAPS_REQUIRED = 20;

    private static final String PASSWORD = "6651";

    /** 不做持久化，见类注释。 */
    private static volatile boolean unlocked;

    private DeveloperMode() {
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    /**
     * 尝试解锁。
     *
     * @return 密码对不对
     */
    public static boolean unlock(String password) {
        if (PASSWORD.equals(password)) {
            unlocked = true;
            return true;
        }
        return false;
    }

    /** 手动关掉（重启应用同样会关）。 */
    public static void lock() {
        unlocked = false;
    }
}
