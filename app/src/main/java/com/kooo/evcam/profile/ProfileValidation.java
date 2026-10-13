package com.kooo.evcam.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份配置说不说得通。
 *
 * <h3>这一步只验「说得通」，验不了「跑得住」</h3>
 *
 * <p>纯粹从数据上能查出来的问题在这里查出来 —— 比如一路相机都没开启、
 * 或者给合成流选了一个它不声明的尺寸。这些不用开相机就知道是错的。
 * 配置编辑把查出来的问题一直摆在页面上。</p>
 *
 * <p>真正的风险是<b>带宽</b>：会话配置成功不等于跑得动。三路各开三条流是系统级
 * 问题，没有任何声明能回答，这一关回答不了。</p>
 */
public final class ProfileValidation {

    /**
     * 一条问题。
     *
     * <p>这里只给事实，不给句子：句子由界面按当前语言拼，
     * 那一路叫什么（「环视」还是「Surround」）也是界面的事。</p>
     */
    public static final class Issue {
        public enum Kind { NONE_ENABLED, UNDECLARED_SIZE }

        public enum Stream { PREVIEW, RECORD }

        public final Kind kind;
        /** 出问题的那一路；整份配置的问题为 null。 */
        public final String role;
        /** 尺寸问题落在哪条流上；其余为 null。 */
        public final Stream stream;
        public final int width;
        public final int height;

        Issue(Kind kind, String role, Stream stream, int width, int height) {
            this.kind = kind;
            this.role = role;
            this.stream = stream;
            this.width = width;
            this.height = height;
        }

        /** 给日志看的。 */
        @Override
        public String toString() {
            return kind
                    + (role == null ? "" : " " + role)
                    + (stream == null ? "" : " " + stream + " " + width + "x" + height);
        }
    }

    /** 检查时要用到的设备事实，由调用方查好。 */
    public interface Capabilities {
        /** 这一路声明支持的尺寸，形如 {@code {{宽,高},...}}；不知道时返回 null。 */
        int[][] declaredSizes(String role);
    }

    private ProfileValidation() {
    }

    /**
     * 查一遍。
     *
     * <p>拍照那条流不查：配置编辑把它固定成 {@link StreamSpec#RESOLUTION_MAX}，
     * 交给下游解析，这里没有可查的尺寸。</p>
     */
    public static List<Issue> check(Profile profile, Capabilities capabilities) {
        List<Issue> issues = new ArrayList<>();
        int enabled = 0;
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled) {
                enabled++;
            }
        }
        if (enabled == 0) {
            issues.add(new Issue(Issue.Kind.NONE_ENABLED, null, null, 0, 0));
        }

        for (CameraProfile camera : profile.cameras) {
            if (!camera.enabled) {
                continue;
            }
            checkStream(issues, capabilities, camera.role, Issue.Stream.PREVIEW, camera.preview);
            checkStream(issues, capabilities, camera.role, Issue.Stream.RECORD, camera.record);
        }
        return issues;
    }

    private static void checkStream(List<Issue> issues, Capabilities capabilities,
                                    String role, Issue.Stream stream, StreamSpec spec) {
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
        issues.add(new Issue(Issue.Kind.UNDECLARED_SIZE, role, stream, size[0], size[1]));
    }
}
