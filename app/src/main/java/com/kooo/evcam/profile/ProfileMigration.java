package com.kooo.evcam.profile;

/**
 * 建一份配置：按预设摆好相机，把还在设置里的那几项（车型、旋转、镜像、预览矫正）搬过来。
 *
 * <h3>为什么不直接读 AppConfig</h3>
 *
 * <p>它只吃一个 {@link Snapshot} —— 一堆已经取好的值。这样这段翻译可以在 JVM 上
 * 单独测：迁移<b>漏一项</b>的后果是某个设置悄悄回到默认值，用户下次开车才发现，
 * 而这类错误编译期一点痕迹都没有。要钉住它，就不能让它依赖 Context。</p>
 *
 * <h3>录制那几项为什么是默认值而不是搬过来的</h3>
 *
 * <p>帧率、码率、编码、分段、录哪几路原本都是设置里的全局键，这一版起<b>只存在于配置里</b>
 * ——「配置编辑」是它们唯一的入口。既然设置里已经没有这些项，翻译也就无从搬起：
 * 新建一份配置时给的是默认值，要改在配置编辑里改。</p>
 *
 * <p>存的仍然是意图而不是算出来的数：帧率默认 {@link StreamSpec#FPS_UNLIMITED}（不限制），
 * 拍照默认 {@link StreamSpec#RESOLUTION_MAX}（这一路声明的最大）。</p>
 */
public final class ProfileMigration {

    /** 迁移要用到的旧设置，取值的活儿在调用方做完。 */
    public static final class Snapshot {
        /** 车型 / 视频流配置：zeekr_7x / zeekr_7x_multi / 其他。 */
        public String carModel = "zeekr_7x";
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

    /** 车型对应哪份内置预设。 */
    public static String presetIdFor(String carModel) {
        if ("zeekr_7x_multi".equals(carModel)) {
            return Profile.PRESET_COMPOSITE_MULTI;
        }
        if ("custom".equals(carModel)) {
            return Profile.PRESET_CUSTOM;
        }
        return Profile.PRESET_COMPOSITE;
    }

    /** 上一条的反向：这份预设对应哪个车型，用来给翻译喂对的输入。 */
    public static String carModelFor(String profileId) {
        if (Profile.PRESET_COMPOSITE_MULTI.equals(profileId)) {
            return "zeekr_7x_multi";
        }
        if (Profile.PRESET_CUSTOM.equals(profileId)) {
            return "custom";
        }
        return "zeekr_7x";
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
        // 环视的尺寸从来不读全局「录制分辨率」——它由探测结果决定。
        // 翻译之后这件事写在这一路自己身上；auto 就是「跟随探测」。
        String compositeSize = StreamSpec.RESOLUTION_AUTO;
        composite.preview = StreamSpec.preview(compositeSize);
        composite.record = defaultRecord(compositeSize);
        composite.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
        addCompositeGrid(composite);
        profile.cameras.add(composite);

        if (!multi) {
            return profile;
        }

        // ---- 两路座舱 ----
        // 尺寸给 auto：具体多大由相机声明决定，要钉死就去配置编辑里钉
        String cabinSize = StreamSpec.RESOLUTION_AUTO;
        String[] roles = {CameraProfile.ROLE_CABIN_1, CameraProfile.ROLE_CABIN_2};
        String[] keys = {"back", "left"};
        for (int i = 0; i < roles.length; i++) {
            CameraProfile cabin = new CameraProfile(roles[i]);
            cabin.enabled = true;
            cabin.preview = StreamSpec.preview(cabinSize);
            cabin.record = defaultRecord(cabinSize);
            cabin.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
            addFullFrame(cabin, snapshot, keys[i]);
            profile.cameras.add(cabin);
        }
        return profile;
    }

    /**
     * 新建配置时录制流的默认值。
     *
     * <p>不限帧率、中等码率、编码交给编码器挑、1 分钟一段 —— 和这些设置还在
     * 设置界面里时的默认值一致，所以「什么都没改过」的行为没有变。</p>
     */
    private static StreamSpec defaultRecord(String resolution) {
        StreamSpec spec = StreamSpec.record(resolution, StreamSpec.FPS_UNLIMITED, "medium",
                "auto", RecordSpecs.DEFAULT_SEGMENT_MINUTES);
        spec.grid = true;   // 和这一项还在设置里时的默认值一致
        return spec;
    }
}
