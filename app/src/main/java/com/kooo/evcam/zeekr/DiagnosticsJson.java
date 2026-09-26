package com.kooo.evcam.zeekr;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.kooo.evcam.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 诊断数据的机器可读导出。
 *
 * <h3>为什么另出一份 JSON</h3>
 *
 * <p>文本报告是给人在车机上翻的，所以每块都有条数上限、长值会截断 ——
 * 不然一屏翻不完。可这些上限对分析是有害的：**被截掉的那部分恰恰可能是要找的东西**。</p>
 *
 * <p>所以导出走 JSON：<b>不设条数上限、不截断值</b>，结构化，
 * 拿回去可以直接按键查、按值筛、和上一份对比。
 * 人看的那份仍然完整地放在 {@code text_report} 字段里，一个文件两用。</p>
 *
 * <h3>用 org.json 而不是自己拼字符串</h3>
 *
 * <p>属性值里有引号、反斜杠、换行、中文的都不少，手拼必然会在某一条上出错，
 * 而 JSON 一旦坏掉是整份都读不了 —— 拿转义这件事赌运气不划算。</p>
 */
public final class DiagnosticsJson {

    private static final String TAG = "DiagnosticsJson";

    /**
     * 输出格式版本。字段结构变了就加一，方便日后对比历史报告。
     *
     * <p>2：去掉 properties / settings / services / vehicle_packages 和
     * meta.ungranted_car_permissions —— 找车辆信号那一轮的原始数据，结论已有（1.37.0）。</p>
     */
    private static final int SCHEMA_VERSION = 2;

    private DiagnosticsJson() {
    }

    /**
     * 把诊断数据组装成 JSON。
     *
     * @param textReport 人看的那份完整文本，一并放进去
     * @return JSON 字符串；组装失败时返回一个带 error 字段的最小对象，
     *         而不是抛异常 —— 报告导不出来比内容不全更糟
     */
    public static String build(Context context, String textReport) {
        JSONObject root = new JSONObject();
        try {
            root.put("schema_version", SCHEMA_VERSION);
            root.put("generated_at", System.currentTimeMillis());
            root.put("meta", meta(context));
            root.put("text_report", textReport != null ? textReport : "");
        } catch (Throwable t) {
            AppLog.e(TAG, "组装 JSON 失败", t);
            try {
                root.put("error", String.valueOf(t));
                root.put("text_report", textReport != null ? textReport : "");
            } catch (Throwable ignored) {
                return "{\"error\":\"failed to build report\"}";
            }
        }
        try {
            return root.toString(2);
        } catch (Throwable t) {
            // 缩进只是为了好读，排版失败也不该让整份报告丢掉
            return root.toString();
        }
    }

    private static JSONObject meta(Context context) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("build_display_id", Build.DISPLAY);
        meta.put("android_release", Build.VERSION.RELEASE);
        meta.put("android_sdk", Build.VERSION.SDK_INT);
        meta.put("manufacturer", Build.MANUFACTURER);
        meta.put("model", Build.MODEL);
        meta.put("device", Build.DEVICE);
        try {
            PackageManager pm = context.getPackageManager();
            meta.put("app_version",
                    pm.getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Throwable t) {
            meta.put("app_version", "unknown");
        }
        return meta;
    }

}
