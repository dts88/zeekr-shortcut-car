/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;

import com.kooo.evcam.R;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.share.FileShareServer;
import com.kooo.evcam.share.LocalNetwork;
import com.kooo.evcam.share.PhoneShare;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 「发送到手机」对话框（项目所有者 2026-11-15，wiki 的 Send to phone 页配图）：录像回放里点「发送到手机」的样子，
 * 带「只发当前片段」那句说明（{@code share_video_segment_note}）。中英各一张，只画对话框。
 *
 * <p>地址是假的：两个局域网地址（所以有「换一个地址」），二维码编的就是第一个假地址；不起真的服务、不读这台电脑的网卡。
 * 端口、那段随机串、文件大小都是示例；文件名照环视录像的起名。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SendToPhoneShotsTest {

    private static final String PORT_AND_TOKEN = ":41837/s/7c2e91a4f05b";

    @Test
    public void videoClipInChinese() throws Exception {
        shoot(Shots.DAY, "send-to-phone");
    }

    @Test
    public void videoClipInEnglish() throws Exception {
        shoot(Shots.DAY_EN, "send-to-phone-en");
    }

    private void shoot(String qualifiers, String name) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        List<LocalNetwork.Endpoint> endpoints = Arrays.asList(
                new LocalNetwork.Endpoint("wlan0", "192.168.43.10", LocalNetwork.Kind.WIFI),
                new LocalNetwork.Endpoint("ap0", "192.168.5.20", LocalNetwork.Kind.HOTSPOT));
        File clip = new File(activity.getCacheDir(), "20261115_143000_surround.mp4") {
            @Override
            public long length() {
                return 63_700_000L;
            }
        };
        String note = activity.getString(R.string.share_video_segment_note,
                activity.getString(R.string.share_minutes, RecordSpecs.DEFAULT_SEGMENT_MINUTES));

        present(activity, new FakeServer(activity), endpoints, clip, note);
        Shots.settle();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("对话框没弹出来", dialog);
        Button another = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEUTRAL);
        assertEquals(activity.getString(R.string.share_phone_switch_address), another.getText().toString());
        assertEquals("两个地址时有「换一个地址」", View.VISIBLE, another.getVisibility());
        File file = Shots.save(dialog, name);
        dialog.dismiss();
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /** 对话框只该由 {@link PhoneShare#show} 弹出来，不对外开放；截图跳过读网卡、起服务那两步，直接把假地址交给它。 */
    private static void present(Activity activity, FileShareServer server,
                                List<LocalNetwork.Endpoint> endpoints, File file, String note) throws Exception {
        Class<?> presenter = Class.forName(PhoneShare.class.getName() + "$Presenter");
        Constructor<?> make = presenter.getDeclaredConstructor(
                Activity.class, FileShareServer.class, List.class, File.class, String.class);
        make.setAccessible(true);
        Object dialog = make.newInstance(activity, server, endpoints, file, note);
        Method show = presenter.getDeclaredMethod("show");
        show.setAccessible(true);
        show.invoke(dialog);
    }

    /** 不起服务，只给地址。 */
    private static final class FakeServer extends FileShareServer {
        FakeServer(Activity activity) {
            super(activity);
        }

        @Override
        public synchronized String urlFor(String host) {
            return "http://" + host + PORT_AND_TOKEN;
        }
    }
}
