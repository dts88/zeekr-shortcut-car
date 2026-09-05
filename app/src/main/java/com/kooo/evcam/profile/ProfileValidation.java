package com.kooo.evcam.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 保存一份配置之前，先看看它说不说得通。
 *
 * <h3>这一步只验「说得通」，验不了「跑得住」</h3>
 *
 * <p>纯粹从数据上能查出来的问题在这里拦下 —— 比如一路相机都没启用、
 * 或者给合成流选了一个它不声明的尺寸。这些不用开相机就知道是错的。</p>
 *
 * <p>真正的风险是<b>带宽</b>：会话配置成功不等于跑得动。三路各开三条流是系统级
 * 问题，没有任何声明能回答，只能真开一次、采几秒帧率。那一步在
 * {@code ProfileEditorFragment} 里做，因为它需要相机。</p>
 */
public final class ProfileValidation {

    /** 一条问题。{@code blocking} 为 true 时不让保存。 */
    public static final class Issue {
        public final boolean blocking;
        public final String message;

        Issue(boolean blocking, String message) {
            this.blocking = blocking;
            this.message = message;
        }

        @Override
        public String toString() {
            return (blocking ? "✗ " : "⚠ ") + message;
        }
    }

    /** 检查时要用到的设备事实，由调用方查好。 */
    public interface Capabilities {
        /** 这一路声明支持的尺寸，形如 {@code {{宽,高},...}}；不知道时返回 null。 */
        int[][] declaredSizes(String role);

        /** 这一路在这个尺寸下会不会被拆成四格。 */
        boolean splits(String role, int width, int height);
    }

    private ProfileValidation() {
    }

    public static List<Issue> check(Profile profile, Capabilities capabilities) {
        List<Issue> issues = new ArrayList<>();
        if (profile == null || profile.cameras.isEmpty()) {
            issues.add(new Issue(true, "这份配置里一路相机都没有"));
            return issues;
        }

        int enabled = 0;
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled) {
                enabled++;
            }
        }
        if (enabled == 0) {
            issues.add(new Issue(true, "一路相机都没启用，什么都不会显示也不会录"));
        }

        for (CameraProfile camera : profile.cameras) {
            if (!camera.enabled) {
                continue;
            }
            checkStream(issues, capabilities, camera.role, "预览", camera.preview);
            checkStream(issues, capabilities, camera.role, "录制", camera.record);
            checkStream(issues, capabilities, camera.role, "拍照", camera.photo);
            checkSplitConsistency(issues, capabilities, camera);
        }
        return issues;
    }

    private static void checkStream(List<Issue> issues, Capabilities capabilities,
                                    String role, String label, StreamSpec spec) {
        int[] size = ProfileResolution.parse(spec.resolution);
        if (size == null) {
            return;   // auto / max 都由下游解析，这里没什么可查的
        }
        int[][] declared = capabilities.declaredSizes(role);
        if (declared == null) {
            return;   // 查不到就不下结论
        }
        for (int[] candidate : declared) {
            if (candidate[0] == size[0] && candidate[1] == size[1]) {
                return;
            }
        }
        issues.add(new Issue(true, String.format(Locale.US,
                "%s 的%s尺寸 %dx%d 这一路没有声明过", role, label, size[0], size[1])));
    }

    /**
     * 预览拆了、录制没拆（或反过来）时提醒一句。
     *
     * <p>这不是错误 —— 两条流本来就可以有不同分辨率。但它有一个容易被忽略的后果：
     * <b>超级后视镜是从预览流取画面的</b>，预览流不拆就没有「后面那一路」可取。</p>
     */
    private static void checkSplitConsistency(List<Issue> issues, Capabilities capabilities,
                                              CameraProfile camera) {
        Boolean previewSplits = splits(capabilities, camera.role, camera.preview);
        Boolean recordSplits = splits(capabilities, camera.role, camera.record);
        if (previewSplits == null || recordSplits == null) {
            return;
        }
        if (previewSplits && !recordSplits) {
            issues.add(new Issue(false, camera.role + "：预览拆四格但录制不拆，"
                    + "录出来的是一整幅画面"));
        }
        if (!previewSplits && recordSplits) {
            issues.add(new Issue(false, camera.role + "：录制拆四格但预览不拆，"
                    + "超级后视镜取不到单独那一路"));
        }
    }

    private static Boolean splits(Capabilities capabilities, String role, StreamSpec spec) {
        int[] size = ProfileResolution.parse(spec.resolution);
        return size == null ? null : capabilities.splits(role, size[0], size[1]);
    }

    /** 有没有拦下保存的问题。 */
    public static boolean hasBlocking(List<Issue> issues) {
        for (Issue issue : issues) {
            if (issue.blocking) {
                return true;
            }
        }
        return false;
    }
}
