package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
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

        bindLink(R.id.about_link_evcam, EVCAM_URL);
        bindLink(R.id.about_link_openavm, OPENAVM_URL);
        bindLink(R.id.about_link_gpl, GPL_URL);

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
}
