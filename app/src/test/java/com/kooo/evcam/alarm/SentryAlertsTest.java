/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import com.kooo.evcam.R;

import java.io.File;
import java.util.Calendar;

/**
 * 哨兵提醒发送前的几个小函数（{@link SentryAlerts}）：失败原因怎么说、时刻怎么写、图缩几倍、照片的时刻从哪来。
 *
 * The small helpers around a send ({@link SentryAlerts}): how a failure reads, how the moment is written,
 * how far the photo is scaled down, and where the shot time comes from.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SentryAlertsTest {

    @Test
    public void aFailureReadsAsTimeoutRefusedOrUnreachable() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(context.getString(R.string.sentry_alerts_reason_timeout),
                SentryAlerts.reason(context, AlertChannel.Kind.TIMEOUT, 0));
        assertEquals(context.getString(R.string.sentry_alerts_reason_refused, 403),
                SentryAlerts.reason(context, AlertChannel.Kind.REJECTED, 403));
        assertEquals(context.getString(R.string.sentry_alerts_reason_refused, 500),
                SentryAlerts.reason(context, AlertChannel.Kind.REFUSED, 500));
        assertEquals(context.getString(R.string.sentry_alerts_reason_unreachable),
                SentryAlerts.reason(context, AlertChannel.Kind.NETWORK, 0));
    }

    @Test
    public void theMomentIsMonthDayHourMinute() {
        Calendar at = Calendar.getInstance();
        at.set(2026, Calendar.NOVEMBER, 17, 21, 5, 33);
        assertEquals("11-17 21:05", SentryAlerts.moment(at.getTimeInMillis()));
    }

    @Test
    public void thePhotoIsDecodedNoLargerThanNeeded() {
        assertEquals(1, SentryAlerts.sampleFor(1280, 1280, 1280));
        assertEquals(2, SentryAlerts.sampleFor(2560, 2560, 1280));
        assertEquals(4, SentryAlerts.sampleFor(3840, 2160, 1280));
        assertEquals(1, SentryAlerts.sampleFor(800, 600, 1280));
    }

    @Test
    public void theShotTimeComesFromTheFileName() {
        File shot = new File("20261117_210533_surround.jpg");
        assertEquals("2026-11-17 21:05:33", SentryAlerts.shotAt(shot));
    }

    @Test
    public void aStrangeNameFallsBackToTheFileTime() {
        String stamp = SentryAlerts.shotAt(new File("not-a-timestamp.jpg"));
        assertTrue(stamp, stamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
