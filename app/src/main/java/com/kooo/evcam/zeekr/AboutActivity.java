package com.kooo.evcam.zeekr;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 「关于与致谢」页面。
 *
 * <p>这一页存在的意义就是把来源讲清楚：本应用的两个能力来源分别是哪个开源项目、
 * 各自是什么许可证、我们用了什么、没用什么。信息全部硬编码在这里，不联网。</p>
 */
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    private static final String TAG = "AboutActivity";

    public static final String EVCAM_URL = "https://github.com/suyunkai/EVCam";
    public static final String GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html";
    public static final String PROJECT_URL = "https://github.com/dts88/zeekr-shortcut-car";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = findViewById(R.id.about_version);
        if (version != null) {
            version.setText(buildVersionLine());
        }

        TextView body = findViewById(R.id.about_body);
        if (body != null) {
            body.setMovementMethod(LinkMovementMethod.getInstance());
        }

        bindLink(R.id.about_link_project, PROJECT_URL);
        bindLink(R.id.about_link_evcam, EVCAM_URL);
        bindLink(R.id.about_link_gpl, GPL_URL);

        setUpDeveloperUnlock();

        View close = findViewById(R.id.about_close);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }
    }

    private String buildVersionLine() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return getString(R.string.app_name_full) + "  v" + name;
        } catch (Exception e) {
            return getString(R.string.app_name_full);
        }
    }

    private void bindLink(int viewId, final String url) {
        View view = findViewById(viewId);
        if (view == null) {
            return;
        }
        view.setOnClickListener(v -> openUrl(url));
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            // 车机上常常没有浏览器，退而把链接显示出来让用户自己抄
            AppLog.w(TAG, "无法打开链接: " + url, e);
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 开发者选项


    /**
     * 点「安全须知」标题：没打开时输密码打开开发者选项，打开了就在这里关掉。
     *
     * <p>藏在这里而不是给个显眼的入口：后面那些要么没做完、要么是排查用的，
     * 平时不该出现在设置里让人以为是正常功能。</p>
     *
     * <p>点一下就弹密码框。连点二十次那套是从安卓「关于本机」抄来的，
     * 但那是<b>没有密码</b>的场景才需要的门槛 —— 这里既然要输密码，
     * 密码本身就是门槛，再让人数二十下只是折磨自己。</p>
     */
    private void setUpDeveloperUnlock() {
        View heading = findViewById(R.id.about_safety_heading);
        if (heading == null) {
            return;
        }
        heading.setOnClickListener(v -> {
            if (com.kooo.evcam.settings.DeveloperMode.isUnlocked()) {
                promptToLockDeveloperMode();
            } else {
                promptForDeveloperPassword();
            }
        });
    }

    private void promptForDeveloperPassword() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.dev_unlock_hint);

        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dev_unlock_title)
                .setMessage(R.string.dev_unlock_msg)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (com.kooo.evcam.settings.DeveloperMode.unlock(this, input.getText().toString())) {
                        Toast.makeText(this, R.string.dev_unlocked,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.dev_wrong_password, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 开发者选项打开之后一直开着，关只在这里关（见 DeveloperMode）。 */
    private void promptToLockDeveloperMode() {
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dev_lock_title)
                .setMessage(R.string.dev_lock_msg)
                .setPositiveButton(R.string.dev_lock_confirm, (dialog, which) -> {
                    com.kooo.evcam.settings.DeveloperMode.lock(this);
                    Toast.makeText(this, R.string.dev_locked, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }
}
