package com.kooo.evcam.profile;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置的存取。
 *
 * <h3>第 1 步里它只读不用</h3>
 *
 * <p>这一版的配置是从旧设置<b>翻译</b>出来的，翻译完存下来、能看，但录制和预览
 * 仍然走老路子取值。这样第 1 步对用户是零变化，而翻译对不对可以先在
 * 「开发者选项 → 当前配置」里逐行核对 —— 迁移漏一项的代价太大，
 * 不该和「改用新数据源」同一版落地。</p>
 */
public final class ProfileStore {

    private static final String TAG = "ProfileStore";

    /** 配置存在自己的文件里，和旧设置分开，迁移失败也不会污染原来那份。 */
    private static final String FILE = "profiles";

    /** 当前选中的配置 id。 */
    private static final String KEY_CURRENT = "current";

    private final Context context;
    private final SharedPreferences prefs;

    public ProfileStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * 当前配置。
     *
     * <h3>第 1 步：每次都重新翻译</h3>
     *
     * <p>现在配置只是旧设置的一个<b>视图</b>：没有编辑器，没有任何人从它取值。
     * 所以「存下来下次直接用」没有任何好处，只会造成一个真实的错误 ——
     * 切换了车型之后，看到的还是上一次翻译的那份，而核对翻译对不对
     * 正是这一步唯一的目的。</p>
     *
     * <p>第 2 步配置变成取值的源头之后，这里才改成「读存下来的那份」，
     * 而「切换车型」也随之变成「切换配置」。</p>
     */
    public Profile current() {
        Profile migrated = ProfileMigration.migrate(snapshot(context));
        save(migrated);
        prefs.edit().putString(KEY_CURRENT, migrated.id).apply();
        AppLog.i(TAG, "已从旧设置翻译出配置:\n" + migrated);
        return migrated;
    }

    public void save(Profile profile) {
        SharedPreferences.Editor editor = prefs.edit();
        String prefix = profile.id + ".";
        // 先清掉这份配置原有的键：相机变少时，残留的旧键会被 fromMap 读回来
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        for (Map.Entry<String, String> entry : profile.toMap().entrySet()) {
            editor.putString(prefix + entry.getKey(), entry.getValue());
        }
        editor.apply();
    }

    public Profile load(String id) {
        String prefix = id + ".";
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof String) {
                values.put(entry.getKey().substring(prefix.length()), (String) entry.getValue());
            }
        }
        return Profile.fromMap(values);
    }

    /** 从旧设置取一份快照，交给翻译。取值的活儿在这里，翻译本身不认识 Context。 */
    public static ProfileMigration.Snapshot snapshot(Context context) {
        AppConfig config = new AppConfig(context);
        ProfileMigration.Snapshot snapshot = new ProfileMigration.Snapshot();
        snapshot.carModel = config.getCarModel();
        snapshot.targetResolution = config.getTargetResolution();
        snapshot.compositeSizeOverride = config.getCompositeSizeOverride();
        snapshot.recordFps = config.getRecordFps();
        snapshot.bitrateLevel = config.getBitrateLevel();
        snapshot.forceH264 = config.isForceH264Encoding();
        snapshot.segmentMinutes = config.getSegmentDurationMinutes();
        snapshot.enabledRecordingCameras = config.getEnabledRecordingCameras();
        snapshot.rotation = config::getCameraRotation;
        snapshot.mirror = config::getCameraMirror;
        snapshot.previewCorrectionEnabled = config.isPreviewCorrectionEnabled();
        snapshot.scaleX = config::getPreviewCorrectionScaleX;
        snapshot.scaleY = config::getPreviewCorrectionScaleY;
        snapshot.translateX = config::getPreviewCorrectionTranslateX;
        snapshot.translateY = config::getPreviewCorrectionTranslateY;
        snapshot.crop = config::getCameraCrop;
        return snapshot;
    }
}
