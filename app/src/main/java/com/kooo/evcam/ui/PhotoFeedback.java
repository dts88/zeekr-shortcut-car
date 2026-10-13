package com.kooo.evcam.ui;

import android.content.Context;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.MultiCameraManager;

/**
 * 拍照之后跟用户说什么 —— 主界面的拍照键和悬浮按钮说同一套话。
 *
 * <p>说的是相机层回报的真实结果（存下了几路，或者没有 U 盘、一路都没拍），不是「按了」。
 * 用应用级 Context：结果到的时候，按键的那个界面可能已经不在了。</p>
 */
public final class PhotoFeedback implements MultiCameraManager.PhotoCallback {

    private final Context context;

    public PhotoFeedback(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onWaitingForCameras() {
        Toast.makeText(context, R.string.msg_photo_waiting, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPhotoResult(int saved, int pressed, int expected) {
        if (saved > 0 && saved >= expected) {
            Toast.makeText(context, R.string.msg_photo_taken, Toast.LENGTH_SHORT).show();
        } else if (saved > 0) {
            Toast.makeText(context, context.getString(R.string.msg_photo_partial, saved, expected),
                    Toast.LENGTH_LONG).show();
        } else if (pressed > 0) {
            Toast.makeText(context, R.string.msg_photo_not_saved, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, R.string.msg_photo_no_picture, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onNoStorage() {
        Toast.makeText(context, R.string.msg_photo_no_drive, Toast.LENGTH_LONG).show();
    }
}
