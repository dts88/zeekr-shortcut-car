package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 「关于与致谢」页面。
 *
 * <p>这一页存在的意义就是把来源讲清楚：本应用的两个能力来源分别是哪个开源项目、
 * 各自是什么许可证、我们用了什么、没用什么。信息全部硬编码在这里，不联网。</p>
 */
public class AboutActivity extends Activity {

    private static final String TAG = "AboutActivity";

    public static final String EVCAM_URL = "https://github.com/suyunkai/EVCam";
    public static final String OPENAVM_URL = "https://github.com/Dantenothing/openavm-recorder";
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
        bindLink(R.id.about_link_openavm, OPENAVM_URL);
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
            return getString(R.string.app_name) + "  v" + name;
        } catch (Exception e) {
            return getString(R.string.app_name);
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

    private int safetyTaps;
    private long lastTapAtMs;

    /**
     * 在「安全须知」上连点若干次，再输密码，打开开发者选项。
     *
     * <p>藏在这里而不是给个显眼的入口：后面那些要么没做完、要么是排查用的，
     * 平时不该出现在设置里让人以为是正常功能。</p>
     *
     * <p>连点要够快 —— 隔太久就重新计数，免得平时零星点到几下慢慢攒够。</p>
     */
    private void setUpDeveloperUnlock() {
        View heading = findViewById(R.id.about_safety_heading);
        if (heading == null) {
            return;
        }
        heading.setOnClickListener(v -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastTapAtMs > TAP_RESET_MS) {
                safetyTaps = 0;
            }
            lastTapAtMs = now;
            safetyTaps++;

            if (com.kooo.evcam.settings.DeveloperMode.isUnlocked()) {
                return;
            }
            int remaining = com.kooo.evcam.settings.DeveloperMode.TAPS_REQUIRED - safetyTaps;
            if (remaining <= 0) {
                safetyTaps = 0;
                promptForDeveloperPassword();
            } else if (remaining <= 5) {
                Toast.makeText(this, "还差 " + remaining + " 次", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void promptForDeveloperPassword() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("密码");

        new android.app.AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("开发者选项")
                .setMessage("这里面是没做完的和排查用的功能。重启应用后会自动关闭。")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (com.kooo.evcam.settings.DeveloperMode.unlock(input.getText().toString())) {
                        Toast.makeText(this, "开发者选项已打开（重启后失效）",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "密码不对", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 连点的间隔上限，超过就重新数。 */
    private static final long TAP_RESET_MS = 1500L;
}
