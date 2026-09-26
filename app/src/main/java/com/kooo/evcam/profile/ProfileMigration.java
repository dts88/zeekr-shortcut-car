package com.kooo.evcam.profile;

/**
 * 建一份配置：按预设摆好相机。旧设置里只有车型这一项还要看。
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
 * <p>存的仍然是意图而不是算出来的数：分辨率存 auto / max，
 * 拍照默认 {@link StreamSpec#RESOLUTION_MAX}（这一路声明的最大）。</p>
 */
public final class ProfileMigration {

    /** 迁移要用到的旧设置，取值的活儿在调用方做完。 */
    public static final class Snapshot {
        /** 车型 / 视频流配置：zeekr_7x / zeekr_7x_multi / 其他。 */
        public String carModel = "zeekr_7x";
    }

    private ProfileMigration() {
    }

    /** 车型对应哪份内置预设。 */
    public static String presetIdFor(String carModel) {
        if ("zeekr_7x_multi".equals(carModel)) {
            return Profile.PRESET_COMPOSITE_MULTI;
        }
        return Profile.PRESET_COMPOSITE;
    }

    /** 上一条的反向：这份预设对应哪个车型，用来给翻译喂对的输入。 */
    public static String carModelFor(String profileId) {
        if (Profile.PRESET_COMPOSITE_MULTI.equals(profileId)) {
            return "zeekr_7x_multi";
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

    /** 普通相机：一格铺满。旋转、镜像、裁切都是默认值，要改去配置编辑里改。 */
    static void addFullFrame(CameraProfile camera) {
        camera.lanes.add(LaneLayout.cell(-1, 0f, 0f, 1f, 1f));
    }

    public static Profile migrate(Snapshot snapshot) {
        boolean multi = "zeekr_7x_multi".equals(snapshot.carModel);

        Profile profile = new Profile();
        profile.id = multi ? Profile.PRESET_COMPOSITE_MULTI : Profile.PRESET_COMPOSITE;
        profile.name = multi ? "环视 + 两路座舱" : "极氪7X（环视合成流）";

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

        // ---- 两路座舱 ----
        //
        // 两路一直都在配置里，只是「环视流」那一份默认关着。以前不在，
        // 于是编辑器里多了一个「加一路相机」的步骤 —— 而这台车就那三路，
        // “加”不是一件真实发生的事，开和关才是。
        //
        // 尺寸给 auto：具体多大由相机声明决定，要钉死就去配置编辑里钉
        String cabinSize = StreamSpec.RESOLUTION_AUTO;
        String[] roles = {CameraProfile.ROLE_CABIN_1, CameraProfile.ROLE_CABIN_2};
        for (int i = 0; i < roles.length; i++) {
            CameraProfile cabin = new CameraProfile(roles[i]);
            cabin.enabled = multi;
            cabin.preview = StreamSpec.preview(cabinSize);
            cabin.record = defaultRecord(cabinSize);
            cabin.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
            addFullFrame(cabin);
            cabin.lanes.get(0).mirrored = CameraProfile.CABIN_MIRRORED_BY_DEFAULT;
            profile.cameras.add(cabin);
        }
        return profile;
    }

    /**
     * 新建配置时录制流的默认值。
     *
     * <p>帧率和码率跟着「均衡」那一档走（{@link QualityPreset#BALANCED}），
     * 编码交给编码器挑，1 分钟一段。初值必须正好是某一档 ——
     * 否则刚建的配置一打开，三张卡一张都没选中，每一路还都标着「已细调」。</p>
     */
    private static StreamSpec defaultRecord(String resolution) {
        StreamSpec spec = StreamSpec.record(resolution, QualityPreset.BALANCED.fps,
                QualityPreset.BALANCED.bitrate, "auto", RecordSpecs.DEFAULT_SEGMENT_MINUTES);
        spec.grid = true;   // 和这一项还在设置里时的默认值一致
        return spec;
    }
}
