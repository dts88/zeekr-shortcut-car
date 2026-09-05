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
     * <h3>配置现在是取值的源头</h3>
     *
     * <p>读存下来的那份。某份配置<b>第一次</b>被用到时，从旧设置翻译一份出来做初值 ——
     * 之后用户在编辑器里改的东西就是它自己的了，不会再被翻译覆盖。</p>
     *
     * <p>第 1 步里这里是每次重新翻译的，因为那时没有编辑器、没人从它取值，
     * 「存下来」只会让人看到过期的内容。现在反过来：翻译只是初值。</p>
     */
    public Profile current() {
        return byId(currentId());
    }

    /** 当前选中的配置 id；没选过时按旧设置里的车型推断。 */
    public String currentId() {
        String id = prefs.getString(KEY_CURRENT, null);
        return id != null ? id : ProfileMigration.presetIdFor(new AppConfig(context).getCarModel());
    }

    /**
     * 按 id 取配置，没有就从旧设置翻译一份做初值。
     *
     * <p>翻译出来的那份会被<b>改成这个 id</b>：用户切到「三路」时，要的是一份三路的
     * 配置，而不是把当前车型翻译出来的那份贴上三路的标签。</p>
     */
    public Profile byId(String id) {
        Profile stored = load(id);
        if (!stored.cameras.isEmpty()) {
            return stored;
        }
        ProfileMigration.Snapshot snapshot = snapshot(context);
        snapshot.carModel = ProfileMigration.carModelFor(id);
        Profile seeded = ProfileMigration.migrate(snapshot);
        save(seeded);
        AppLog.i(TAG, "配置 " + id + " 第一次使用，从旧设置翻译出初值:\n" + seeded);
        return seeded;
    }

    /** 切换到另一份配置。 */
    public void select(String id) {
        prefs.edit().putString(KEY_CURRENT, id).apply();
        AppLog.i(TAG, "当前配置切换为 " + id);
    }

    /** 把这份配置恢复成从旧设置翻译出来的初值。校验不通过时回滚用得上。 */
    public Profile reset(String id) {
        String prefix = id + ".";
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
        return byId(id);
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
