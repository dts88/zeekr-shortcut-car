package com.kooo.evcam.settings;

import android.content.Context;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 设置界面里那几个自带交互的弹窗。
 *
 * <p>从旧的设置 Fragment 里<b>原样搬过来</b>的，只是把 {@code getContext()} 和
 * {@code appConfig} 换成了参数。没有重写：相机映射和日志上传这两条流程里
 * 有不少细节（覆盖项的清除时机、上次会话日志的判断），重推一遍容易漏。</p>
 *
 * <p>放在这里而不是留在 Fragment 里，是因为设置界面换成了 PreferenceScreen，
 * 这些弹窗和界面结构本来也没什么关系 —— 它们只需要 Context 和配置。</p>
 */
public final class SettingsDialogs {

    private SettingsDialogs() {
    }

    static void showNicknameConfirmDialog(Context context, AppConfig config, String nickname) {
        if (context == null) return;
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认设备名称")
                .setMessage("您输入的设备名称是：\n\n「" + nickname + "」\n\n确认使用此名称吗？")
                .setPositiveButton("确认", (dialog, which) -> {
                    // 保存名称，然后显示上传确认框
                    if (config != null) {
                        config.setDeviceNickname(nickname);
                    }
                    showUploadConfirmDialog(context, config, nickname);
                })
                .setNegativeButton("重新输入", (dialog, which) -> {
                    // 重新显示输入框
                    showDeviceNicknameInputDialog(context, config);
                })
                .show();
    }

    /** 这台车上到底有哪些相机 id —— 手动指定映射时要从真实存在的里面挑。 */
    private static String[] listCameraIds(Context context) {
        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager)
                            context.getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                return manager.getCameraIdList();
            }
        } catch (Exception e) {
            AppLog.w("SettingsDialogs", "读取相机列表失败: " + e.getMessage());
        }
        return new String[0];
    }

    /**
     * 真正把日志发出去。
     *
     * <p>原来这段会把「一键上传」按钮置灰再改文字，作为「正在上传」的反馈。
     * 现在设置界面是 PreferenceScreen，点完对话框就关了，没有那个按钮可改，
     * 所以改成用 Toast 报开始和结果 —— 反馈还在，只是换了地方。</p>
     */
    private static void performLogUpload(Context context, String deviceNickname,
                                         String problemDescription,
                                         boolean uploadPreviousSession) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final String logType = uploadPreviousSession ? "上次运行" : "本次运行";
        Toast.makeText(app, "正在上传" + logType + "日志...", Toast.LENGTH_SHORT).show();

        AppLog.uploadLogsToServer(app, deviceNickname, problemDescription,
                uploadPreviousSession, new AppLog.UploadCallback() {
                    @Override
                    public void onSuccess() {
                        main.post(() -> Toast.makeText(
                                app, "作者已收到" + logType + "日志", Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void onError(String error) {
                        main.post(() -> Toast.makeText(
                                app, "上传失败: " + error, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    static void showCameraMappingDialog(Context context, AppConfig config,
                                       Runnable onChanged) {
        if (context == null) {
            return;
        }
        final String[] cameraIds = listCameraIds(context);
        if (cameraIds.length == 0) {
            Toast.makeText(context, R.string.msg_camera_list_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 选项 = 自动 + 每个相机 id
        final String[] options = new String[cameraIds.length + 1];
        options[0] = context.getString(R.string.opt_camera_auto);
        for (int i = 0; i < cameraIds.length; i++) {
            options[i + 1] = context.getString(R.string.opt_camera_id, cameraIds[i]);
        }

        final String[] slots = {"front", "back", "left"};
        final String[] slotLabels = {
                context.getString(R.string.slot_surround),
                context.getString(R.string.slot_cabin_1),
                context.getString(R.string.slot_cabin_2)};
        final Spinner[] spinners = new Spinner[slots.length];

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < slots.length; i++) {
            TextView label = new TextView(context);
            label.setText(slotLabels[i]);
            label.setTextSize(16f);
            root.addView(label);

            Spinner spinner = new Spinner(context);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    context, android.R.layout.simple_spinner_item, options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);

            String current = config.getCameraOverride(slots[i]);
            int selected = 0;
            if (current != null) {
                for (int k = 0; k < cameraIds.length; k++) {
                    if (cameraIds[k].equals(current)) {
                        selected = k + 1;
                        break;
                    }
                }
            }
            spinner.setSelection(selected);
            root.addView(spinner);
            spinners[i] = spinner;
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_camera_mapping_title)
                .setView(root)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    for (int i = 0; i < slots.length; i++) {
                        int pos = spinners[i].getSelectedItemPosition();
                        config.setCameraOverride(slots[i],
                                pos <= 0 ? null : cameraIds[pos - 1]);
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    Toast.makeText(context, R.string.msg_mapping_saved,
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton(R.string.action_all_auto, (d, w) -> {
                    config.clearCameraOverrides();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    Toast.makeText(context, R.string.msg_mapping_cleared,
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    static void showDeviceNicknameInputDialog(Context context, AppConfig config) {
        if (context == null) return;
        
        EditText inputEditText = new EditText(context);
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        inputEditText.setHint("例如：张三的银河E5");
        inputEditText.setPadding(48, 32, 48, 32);
        // 适配夜间模式
        inputEditText.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("设置设备识别名称")
                .setMessage("请输入一个便于识别的名称，用于区分不同用户的日志：")
                .setView(inputEditText)
                .setPositiveButton("确认", (dialog, which) -> {
                    String nickname = inputEditText.getText().toString().trim();
                    if (nickname.isEmpty()) {
                        Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 显示二次确认
                    showNicknameConfirmDialog(context, config, nickname);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    static void showUploadConfirmDialog(Context context, AppConfig config, String nickname) {
        if (context == null) return;
        
        // 创建包含名称显示和问题描述输入的布局
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);
        
        // 名称显示 - 适配夜间模式
        TextView nicknameLabel = new TextView(context);
        nicknameLabel.setText("上传身份：「" + nickname + "」");
        nicknameLabel.setTextSize(16);
        nicknameLabel.setPadding(0, 0, 0, 24);
        nicknameLabel.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        layout.addView(nicknameLabel);
        
        // 日志选择标签
        TextView logTypeLabel = new TextView(context);
        logTypeLabel.setText("选择日志：");
        logTypeLabel.setTextSize(14);
        logTypeLabel.setPadding(0, 0, 0, 8);
        logTypeLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        layout.addView(logTypeLabel);
        
        // 日志选择 RadioGroup
        RadioGroup logTypeGroup = new RadioGroup(context);
        logTypeGroup.setOrientation(RadioGroup.VERTICAL);
        logTypeGroup.setPadding(0, 0, 0, 16);
        
        // 本次运行日志选项
        RadioButton currentLogRadio = new RadioButton(context);
        currentLogRadio.setId(View.generateViewId());
        currentLogRadio.setText("本次运行日志");
        currentLogRadio.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        currentLogRadio.setChecked(true);
        logTypeGroup.addView(currentLogRadio);
        
        // 上次运行日志选项
        RadioButton previousLogRadio = new RadioButton(context);
        previousLogRadio.setId(View.generateViewId());
        boolean hasPrevious = AppLog.hasPreviousSessionLogs(context);
        if (hasPrevious) {
            String prevInfo = AppLog.getPreviousSessionLogInfo(context);
            previousLogRadio.setText("上次运行日志" + (prevInfo != null ? "\n  " + prevInfo : ""));
            previousLogRadio.setEnabled(true);
        } else {
            previousLogRadio.setText("上次运行日志（无可用日志）");
            previousLogRadio.setEnabled(false);
        }
        previousLogRadio.setTextColor(ContextCompat.getColor(context, 
                hasPrevious ? R.color.text_primary : R.color.text_secondary));
        logTypeGroup.addView(previousLogRadio);
        
        layout.addView(logTypeGroup);
        
        // 问题描述标签 - 适配夜间模式
        TextView descLabel = new TextView(context);
        descLabel.setText("问题描述：");
        descLabel.setTextSize(14);
        descLabel.setPadding(0, 0, 0, 8);
        descLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        layout.addView(descLabel);
        
        // 问题描述输入框 - 适配夜间模式
        EditText inputEditText = new EditText(context);
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputEditText.setMinLines(3);
        inputEditText.setMaxLines(6);
        inputEditText.setHint("请描述遇到的问题...");
        inputEditText.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        layout.addView(inputEditText);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("上传日志")
                .setView(layout)
                .setPositiveButton("上传", (dialog, which) -> {
                    String problemDesc = inputEditText.getText().toString().trim();
                    if (problemDesc.isEmpty()) {
                        problemDesc = "（用户未填写问题描述）";
                    }
                    // 判断选择了哪个日志
                    boolean uploadPreviousSession = previousLogRadio.isChecked();
                    performLogUpload(context, nickname, problemDesc, uploadPreviousSession);
                })
                .setNeutralButton("修改名称", (dialog, which) -> {
                    showDeviceNicknameInputDialog(context, config);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
