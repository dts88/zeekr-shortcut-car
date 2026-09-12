package com.kooo.evcam.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

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
            int bitrate = level == 1 ? R.string.status_bitrate_low
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
