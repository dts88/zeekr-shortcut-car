package com.kooo.evcam.settings;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 设置界面里那几个自带交互的弹窗。
 *
 * <p>从旧的设置 Fragment 里<b>原样搬过来</b>的，只是把 {@code getContext()} 和
 * {@code appConfig} 换成了参数。没有重写：相机映射这条流程里有不少细节
 * （覆盖项的清除时机），重推一遍容易漏。日志上传那几个对话框 1.44.0 随功能一起删了。</p>
 *
 * <p>放在这里而不是留在 Fragment 里，是因为设置界面换成了 PreferenceScreen，
 * 这些弹窗和界面结构本来也没什么关系 —— 它们只需要 Context 和配置。</p>
 */
public final class SettingsDialogs {

    private SettingsDialogs() {
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
                context.getString(R.string.slot_cabin_front),
                context.getString(R.string.slot_cabin_rear)};
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

        com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
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
                .setNegativeButton(R.string.action_cancel, null));
    }
}
