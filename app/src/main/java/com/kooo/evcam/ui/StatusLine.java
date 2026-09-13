package com.kooo.evcam.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.profile.StreamSpec;

import java.io.File;

/**
 * 填 ⑤ 状态条。
 *
 * <h3>为什么要传一棵树进来</h3>
 *
 * <p>状态条是 {@code <include>} 进各界面的，所以同一个界面里可能同时存在两份
 * （主界面那份被设置界面盖住时两份都在树上）。从 Activity 上 findViewById 会
 * 拿到遍历顺序里的第一份 —— 可能正是看不见的那一份。所以调用方给出自己那棵树。</p>
 */
public final class StatusLine {

    private StatusLine() {
    }

    /**
     * 把这一条摆成压在画面上的那一条：半透明渐变底、固定浅色字。
     *
     * <h3>为什么不写在布局里</h3>
     *
     * <p>同一份 {@code layout_status_bar} 也用在设置界面，那里它贴在页面底部、
     * 下面是界面底色 —— 给它蒙一层深色渐变就成了一条莫名其妙的黑边。压在画面上
     * 和贴在页面底部是两种处境，字色也就不能是同一套：画面上必须固定浅色，
     * 页面上必须跟日夜走。</p>
     *
     * <p>只需要在界面建好时调一次，之后 {@link #fill} 只换文字。</p>
     */
    public static void overlay(View root) {
        if (root == null) {
            return;
        }
        View bar = root.findViewById(R.id.status_bar);
        if (bar == null) {
            return;
        }
        bar.setBackgroundResource(R.drawable.bg_status_scrim);
        Context context = bar.getContext();
        int bright = ContextCompat.getColor(context, R.color.status_overlay_text);
        int dim = ContextCompat.getColor(context, R.color.status_overlay_text_dim);
        tint(root, R.id.tv_status_stream, bright);
        tint(root, R.id.tv_status_storage, bright);
        tint(root, R.id.tv_composite_info, dim);
        tint(root, R.id.tv_segment_percent, dim);
    }

    private static void tint(View root, int id, int colour) {
        TextView view = root.findViewById(id);
        if (view != null) {
            view.setTextColor(colour);
        }
    }

    /** 把「这次按什么录」和「还剩多少空间」两格填好；其余两格是录制状态，由主界面自己管。 */
    public static void fill(View root) {
        if (root == null) {
            return;
        }
        Context context = root.getContext();
        TextView stream = root.findViewById(R.id.tv_status_stream);
        if (stream != null) {
            StreamSpec spec = RecordSpecs.forCameraKey(context, "front");
            String fps = spec.fps == null || spec.fps.isEmpty()
                    || StreamSpec.FPS_UNLIMITED.equals(spec.fps)
                    ? context.getString(R.string.opt_fps_auto_unknown)
                    : context.getString(R.string.status_fps, spec.fps);
            int level = RecordSpecs.qualityLevel(spec.bitrate);
            int bitrate = level == 0 ? R.string.status_bitrate_very_low
                    : level == 1 ? R.string.status_bitrate_low
                    : level == 3 ? R.string.status_bitrate_high
                    : R.string.status_bitrate_medium;
            NumberRoll.set(stream, fps + " · " + context.getString(bitrate));
            stream.setVisibility(View.VISIBLE);
        }
        TextView storage = root.findViewById(R.id.tv_status_storage);
        if (storage != null) {
            File sdCard = StorageHelper.getExternalSdCardRoot(context);
            long free = sdCard != null ? StorageHelper.getAvailableSpace(sdCard) : -1;
            NumberRoll.set(storage, free >= 0
                    ? context.getString(R.string.status_storage_free, StorageHelper.formatSize(free))
                    : context.getString(R.string.status_storage_none));
            storage.setVisibility(View.VISIBLE);
        }
    }
}
