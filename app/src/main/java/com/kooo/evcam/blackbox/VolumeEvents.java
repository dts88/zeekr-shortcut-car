package com.kooo.evcam.blackbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

/**
 * U 盘挂上、卸下、异常掉线，每一次都进黑匣子。
 *
 * <p>2026-09-26 21:55：录像写的那块盘（一块装在 USB 盒里的固态盘）先报写入错误，
 * 5 秒后连录像文件夹都不存在了，过了一阵又出现在系统里。系统那一侧到底有没有把它卸下、
 * 几点几分卸下、几点几分又挂上，app 这边一行记录都没有 —— 只能从报错去猜。</p>
 *
 * <p>清单里的保活接收器也收这几个广播，但只写进日志、不进黑匣子。这里在进程里动态注册，
 * 只做一件事：记下来。顺手清掉 U 盘路径的缓存，免得下一次录像还拿着已经不在的盘。</p>
 */
public final class VolumeEvents {

    private static final String TAG = "VolumeEvents";

    private static boolean registered;

    private VolumeEvents() {
    }

    public static synchronized void register(Context context) {
        if (registered || context == null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_CHECKING);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        filter.addAction(Intent.ACTION_MEDIA_NOFS);
        // 这几个广播的数据是卷的路径（file:///storage/XXXX-XXXX），不加这一条收不到
        filter.addDataScheme("file");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String path = intent.getData() != null ? intent.getData().getPath() : "?";
                StorageHelper.clearCache();
                BlackBox.noteImportant("U 盘事件：" + BlackBox.shortAction(intent.getAction())
                        + " " + path + "；此刻挂着的盘：" + StorageHelper.describeMounts());
            }
        };
        try {
            Context app = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            registered = true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "register failed: " + e);
        }
    }
}
