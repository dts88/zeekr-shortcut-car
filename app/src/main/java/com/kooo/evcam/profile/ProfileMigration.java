package com.kooo.evcam.profile;

import java.util.Set;

/**
 * 把今天那一堆平铺的全局设置，翻译成一份配置。
 *
 * <h3>为什么不直接读 AppConfig</h3>
 *
 * <p>它只吃一个 {@link Snapshot} —— 一堆已经取好的值。这样这段翻译可以在 JVM 上
 * 单独测：迁移<b>漏一项</b>的后果是某个设置悄悄回到默认值，用户下次开车才发现，
 * 而这类错误编译期一点痕迹都没有。要钉住它，就不能让它依赖 Context。</p>
 *
 * <h3>翻译时做的两件事</h3>
 *
 * <ol>
 *   <li><b>把全局值分发到每一路</b>。今天「录制分辨率」是一个全局键，但它其实
 *       只对座舱两路生效（环视那一路的尺寸被钉死）。翻译之后每一路各有一份，
 *       这个事实第一次变得看得见。</li>
 *   <li><b>把算出来的数还原成意图</b>。旧的「原始帧率」存的是 {@code auto}，
 *       含义是不限制，翻成 {@link StreamSpec#FPS_UNLIMITED}；拍照没有旧设置，
 *       直接给 {@link StreamSpec#RESOLUTION_MAX}。</li>
 * </ol>
 */
public final class ProfileMigration {

    /** 迁移要用到的旧设置，取值的活儿在调用方做完。 */
    public static final class Snapshot {
        /** 车型 / 视频流配置：zeekr_7x / zeekr_7x_multi / 其他。 */
        public String carModel = "zeekr_7x";
        /** 全局录制分辨率：default 或 "1920x1080"。 */
        public String targetResolution = "default";
        /** 环视流尺寸覆盖，空表示跟随探测。 */
        public String compositeSizeOverride = "";
        /** 录制帧率：auto 或数字字符串。 */
        public String recordFps = "auto";
        /** 码率等级：low / medium / high。 */
        public String bitrateLevel = "medium";
        /** 强制 H.264。 */
        public boolean forceH264;
        /** 分段时长（分钟）。 */
        public int segmentMinutes = 3;
        /** 参与录制的相机键（front / back / left / right）。 */
        public Set<String> enabledRecordingCameras;
        /** 每一路的旋转角度，按相机键取。 */
        public IntByKey rotation = key -> 0;
        /** 每一路是否镜像。 */
        public BoolByKey mirror = key -> false;
        /** 预览矫正开关。关着时那几个值不生效，不能搬。 */
        public boolean previewCorrectionEnabled;
        /** 预览矫正：缩放与平移，按相机键取。 */
        public FloatByKey scaleX = key -> 1f;
        public FloatByKey scaleY = key -> 1f;
        public FloatByKey translateX = key -> 0f;
        public FloatByKey translateY = key -> 0f;
        /** 四边裁切，旧值是像素。 */
        public CropByKey crop = (key, side) -> 0;
    }

    public interface FloatByKey {
        float get(String key);
    }

    public interface CropByKey {
        int get(String key, String side);
    }

    public interface IntByKey {
        int get(String key);
    }

    public interface BoolByKey {
        boolean get(String key);
    }

    private ProfileMigration() {
    }

    /**
     * 合成流那一路在主界面上的四格摆位。
     *
     * <p>今天这四个位置是 {@code FourLaneContainer} 算出来的，没有存过 ——
     * 2×2 等分，格号 {@code (i%2, i/2)}。迁移时把它<b>提取</b>成配置里的初始值，
     * 从此它就是一份普通的、可编辑的数据，不再是写死在容器里的排版。</p>
     */
    static void addCompositeGrid(CameraProfile camera) {
        for (int lane = 0; lane < 4; lane++) {
            camera.lanes.add(LaneLayout.cell(lane,
                    (lane % 2) * 0.5f, (lane / 2) * 0.5f, 0.5f, 0.5f));
        }
    }

    /** 普通相机：一格铺满。 */
    static void addFullFrame(CameraProfile camera, Snapshot snapshot, String cameraKey) {
        LaneLayout layout = LaneLayout.cell(-1, 0f, 0f, 1f, 1f);
        layout.rotation = snapshot.rotation.get(cameraKey);
        layout.mirrored = snapshot.mirror.get(cameraKey);
        applyCorrection(layout, snapshot, cameraKey);
        camera.lanes.add(layout);
    }

    /**
     * 把「预览矫正」和裁切搬到这一格上。
     *
     * <p>矫正开关关着时那几个值不生效 —— 搬过来就等于悄悄给用户开了一个
     * 他从没开过的功能。所以只在开着时搬。</p>
     *
     * <p>裁切旧值是<b>像素</b>，而这里存的是比例。迁移时留 0：像素值换算成比例
     * 需要知道当时的画面尺寸，而那个尺寸现在拿不到，硬换算出来的数是假的。
     * 这一项在第 4 步做布局编辑时由用户重设，届时单位本来就变了。</p>
     */
    private static void applyCorrection(LaneLayout layout, Snapshot snapshot, String cameraKey) {
        if (!snapshot.previewCorrectionEnabled) {
            return;
        }
        layout.scaleX = snapshot.scaleX.get(cameraKey);
        layout.scaleY = snapshot.scaleY.get(cameraKey);
        layout.translateX = snapshot.translateX.get(cameraKey);
        layout.translateY = snapshot.translateY.get(cameraKey);
    }

    public static Profile migrate(Snapshot snapshot) {
        boolean multi = "zeekr_7x_multi".equals(snapshot.carModel);
        boolean custom = "custom".equals(snapshot.carModel);

        Profile profile = new Profile();
        profile.id = multi ? Profile.PRESET_COMPOSITE_MULTI
                : custom ? Profile.PRESET_CUSTOM : Profile.PRESET_COMPOSITE;
        // 「自定义」的相机映射是另一套数据（CustomLayoutManager 那边），第 1 步没有翻译它。
        // 名字必须说实话 —— 顶着「极氪7X」的名头给出一份不是它的配置，
        // 比不给更糟：核对的人会以为翻译对了。
        profile.name = multi ? "环视 + 两路座舱"
                : custom ? "自定义（相机映射尚未翻译）" : "极氪7X（环视合成流）";

        // ---- 环视那一路 ----
        CameraProfile composite = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        composite.enabled = true;
        // 环视的尺寸从来不读全局「录制分辨率」：它由探测结果决定，
        // 开发者选项里的覆盖值优先。翻译之后这件事写在这一路自己身上。
        String compositeSize = snapshot.compositeSizeOverride == null
                || snapshot.compositeSizeOverride.isEmpty()
                ? StreamSpec.RESOLUTION_AUTO : snapshot.compositeSizeOverride;
        composite.preview = StreamSpec.preview(compositeSize);
        composite.record = StreamSpec.record(compositeSize, fps(snapshot.recordFps),
                snapshot.bitrateLevel, codec(snapshot.forceH264), snapshot.segmentMinutes);
        composite.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
        addCompositeGrid(composite);
        profile.cameras.add(composite);

        if (!multi) {
            return profile;
        }

        // ---- 两路座舱。它们才是全局「录制分辨率」真正作用的地方 ----
        String cabinSize = "default".equals(snapshot.targetResolution)
                ? StreamSpec.RESOLUTION_AUTO : snapshot.targetResolution;
        String[] roles = {CameraProfile.ROLE_CABIN_1, CameraProfile.ROLE_CABIN_2};
        String[] keys = {"back", "left"};
        for (int i = 0; i < roles.length; i++) {
            CameraProfile cabin = new CameraProfile(roles[i]);
            cabin.enabled = snapshot.enabledRecordingCameras == null
                    || snapshot.enabledRecordingCameras.contains(keys[i]);
            cabin.preview = StreamSpec.preview(cabinSize);
            cabin.record = StreamSpec.record(cabinSize, fps(snapshot.recordFps),
                    snapshot.bitrateLevel, codec(snapshot.forceH264), snapshot.segmentMinutes);
            cabin.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
            addFullFrame(cabin, snapshot, keys[i]);
            profile.cameras.add(cabin);
        }
        return profile;
    }

    /** 旧的 {@code auto} 就是「不限制」，翻成意图而不是当时算出来的那个数。 */
    private static String fps(String recordFps) {
        return recordFps == null || recordFps.isEmpty() || "auto".equals(recordFps)
                ? StreamSpec.FPS_UNLIMITED : recordFps;
    }

    private static String codec(boolean forceH264) {
        return forceH264 ? "h264" : "auto";
    }
}
