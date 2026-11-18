/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.alarm.media.AlertPicture;
import com.kooo.evcam.alarm.media.PictureBudget;
import com.kooo.evcam.alarm.policy.AlertPolicy;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.settings.Languages;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 哨兵提醒（「设置 → 录制 → 哨兵提醒」，试验性）：哨兵模式布防满 30 秒后，车辆闪烁双闪或车灯自行亮起，推送一条提醒到手机。
 * 设计和逻辑来自 Fab（Fabbbrrr）的 PR #211，这里照他的逻辑只留单向的 ntfy（NOTICE.md §8）。
 *
 * <h3>框架</h3>
 *
 * <ul>
 *   <li><b>看不看</b>（{@link #update}）：「手机订阅模式」不是「关闭」、话题和同意都有（{@link AlertStore#ready}）、用户没退出，
 *       就在 {@link Telemetry} 上听读数；车辆信号的核心进程活着一直在读（熄屏判断登记的），这里只挂一个监听，不另外登记。</li>
 *   <li><b>认</b>（{@link SentryWatch}）：布防满 30 秒，双闪或车灯从不亮变亮。</li>
 *   <li><b>限</b>（{@link AlertPolicy}）：两条之间至少 60 秒、一小时最多 6 条、发失败后 5 分钟不发。</li>
 *   <li><b>再看一眼</b>：亮起之后等 {@link #CONFIRM_MS} 再看一次还布防不布防 —— 解锁那一下车也会闪灯，
 *       哨兵读数回到「开启」和闪灯的读数谁先到没核实过；车主回来了就不发，也不拍。</li>
 *   <li><b>拍</b>：「附带画面」开着就向相机管家要一张（{@link CameraKeeper#takePhoto}，和拍照键同一条路，开不开相机、拍哪几路
 *       都是管家的规矩 —— 熄屏时它不为拍照开相机，那就没有图）。拿环视那一张，掰直（跟着屏幕上的鱼眼校正）、打上时间、
 *       压到 100 KB 以内（{@link AlertPicture}）；拍不到、压不进的只发文字。那张照片也照常存在 U 盘上。</li>
 *   <li><b>发</b>：一条单线程（{@link #sender}）上做图、发送，主线程只排事情。只发，不读任何东西回来。服务器不收这张图
 *       （自建的服务器没开附件、图太大）就退一步只发文字。结果记进 {@link AlertStore}（设置里那一行小字）。</li>
 * </ul>
 *
 * <p>话题、服务器不写黑匣子、不写日志（{@link AlertStore}）；黑匣子只记亮起、发没发、为什么没发。</p>
 *
 * Sentry alerts: when Sentry Mode has been armed for 30 seconds and the hazard lights flash
 * or the lights come on by themselves, push one alert to the phone. Designed by Fab (Fabbbrrr) in PR #211;
 * this keeps his logic for one-way ntfy only. It listens on {@link Telemetry} while set up, recognises the
 * moment with {@link SentryWatch}, rate-limits with {@link AlertPolicy}, asks the camera keeper for a photo
 * when a picture is wanted, checks once more that the car is still armed, then builds the picture and sends
 * on one background thread. Topic and server never reach a log.
 */
public final class SentryAlerts implements Telemetry.Listener {

    private static final String TAG = "SentryAlerts";

    /**
     * 亮起之后等多久再看一眼还布防不布防（解锁时车也闪灯：两个读数谁先到没核实过）。看过了才拍照、发送。
     *
     * How long after a light comes on to look again whether the car is still armed (unlocking flashes the
     * lights too, and which reading arrives first is not verified). Only then the photo and the send.
     */
    static final long CONFIRM_MS = 2_000L;

    /**
     * 拍照最多等多久：拍照自己最多等 10 秒出画面、5 秒存完（{@code MultiCameraManager}），过了还没回话就只发文字。
     *
     * How long to wait for the photo: the photo itself waits up to 10 s for frames and 5 s for saving; no
     * answer by then and the alert goes out as text.
     */
    static final long PHOTO_WAIT_MS = 20_000L;

    /** 做图之前先缩到这么大（长边）：100 KB 装得下的也就这么大。Decode down to this long side first. */
    private static final int MAX_SIDE_PX = 1280;

    private static final SentryAlerts INSTANCE = new SentryAlerts();

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 做图、发送都在这一条线程上，一条接一条。Pictures and sends, one after another, on one thread. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SentryAlerts");
        thread.setDaemon(true);
        return thread;
    });

    // ---- 下面这几个只在主线程上动
    private final SentryWatch watch = new SentryWatch();
    private final AlertPolicy policy = new AlertPolicy(false);
    private Context app;
    private boolean watching;

    private SentryAlerts() {
    }

    // ================================================================= 什么时候看

    /** 进程起来时（{@code ZeekrShortcutApp}）：开着、配好了就开始看。哪个线程都能叫。 */
    public static void install(Context context) {
        settingsChanged(context);
    }

    /** 设置里改了（模式、话题、服务器），用户退出的记号清掉了：马上照新的来。哪个线程都能叫。 */
    public static void settingsChanged(Context context) {
        Context appContext = context.getApplicationContext();
        INSTANCE.main.post(() -> {
            INSTANCE.app = appContext;
            INSTANCE.update();
        });
    }

    /** 该不该看，和现在看没看对一下。主线程。 */
    private void update() {
        AlertStore store = AlertStore.of(app);
        boolean want = store.ready() && !UserExit.isExited(app);
        policy.setEnabled(want);
        policy.setConfigured(want);
        if (want == watching) {
            return;
        }
        watching = want;
        Telemetry telemetry = Telemetry.get();
        if (want) {
            watch.reset();
            telemetry.addListener(this);
            BlackBox.note("哨兵提醒：开始看双闪、车灯（" + store.service() + (store.picture() ? "，附带画面" : "") + "）");
            // 此刻的读数算头一份（车辆信号的核心一直在读）
            onReadingsChanged(telemetry.readings());
        } else {
            telemetry.removeListener(this);
            BlackBox.note("哨兵提醒：不再看（" + (UserExit.isExited(app) ? "用户退出了" : "关了") + "）");
        }
    }

    // ================================================================= 认

    /** 读数变了（主线程）。 */
    @Override
    public void onReadingsChanged(Readings readings) {
        if (!watching) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        SentryWatch.Trigger trigger = watch.update(readings.code(Signal.SENTRY_MODE), readings.code(Signal.INDICATOR),
                readings.bool(Signal.DRL), readings.bool(Signal.FRONT_POSITION_LAMP),
                readings.bool(Signal.REAR_POSITION_LAMP), readings.bool(Signal.LOW_BEAM), now);
        if (trigger == null || !watch.armed()) {
            return;   // 开车、停着没锁时的双闪、车灯不算，也不记
        }
        long waitMs = watch.waitMs(now);
        if (waitMs > 0) {
            BlackBox.note("哨兵提醒：" + (trigger == SentryWatch.Trigger.HAZARD ? "双闪亮起" : "车灯亮起")
                    + "，布防不满 30 秒（还差 " + (waitMs + 999) / 1000 + " 秒），不发");
            return;
        }
        policy.setParked(true);
        policy.setArmed(true);
        AlertPolicy.Decision decision = policy.reserve(now);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("哨兵提醒：" + (trigger == SentryWatch.Trigger.HAZARD ? "双闪亮起" : "车灯亮起")
                    + "，不发（" + decision + "）");
            return;
        }
        new Firing(trigger, System.currentTimeMillis()).start();
    }

    // ================================================================= 拍、再看一眼、发

    /** 一条提醒从亮起到发出去：等 {@link #CONFIRM_MS} 再看一眼 → 拍（要附图的话）→ 发。主线程。 */
    private final class Firing implements MultiCameraManager.PhotoCallback {
        private final SentryWatch.Trigger trigger;
        /** 亮起的那一刻（系统时间）：正文里写它。 */
        private final long atMs;
        private boolean photoDone;
        /** 环视那一张存在哪；没拍到是 null。 */
        private File surround;
        private final Runnable photoTimeout = () -> {
            if (!photoDone) {
                BlackBox.note("哨兵提醒：拍照 " + PHOTO_WAIT_MS / 1000 + " 秒没回话，只发文字");
                photoDone = true;
                send();
            }
        };

        Firing(SentryWatch.Trigger trigger, long atMs) {
            this.trigger = trigger;
            this.atMs = atMs;
        }

        void start() {
            BlackBox.noteImportant("哨兵提醒：" + (trigger == SentryWatch.Trigger.HAZARD ? "双闪亮起" : "车灯亮起")
                    + "，" + CONFIRM_MS / 1000 + " 秒后再看一眼还布防不布防");
            main.postDelayed(this::confirmed, CONFIRM_MS);
        }

        /** 等满了：还布防着才拍、才发。 */
        private void confirmed() {
            if (!watching) {
                BlackBox.note("哨兵提醒：发之前提醒关了，不发");
                return;
            }
            if (!watch.armed()) {
                BlackBox.note("哨兵提醒：哨兵已不在布防（车解锁了），不发");
                return;
            }
            if (!AlertStore.of(app).picture()) {
                photoDone = true;
                send();
                return;
            }
            boolean taken;
            try {
                taken = CameraKeeper.get(app).takePhoto(this);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "photo request failed: " + e);
                taken = false;
            }
            if (taken) {
                if (!photoDone) {
                    main.postDelayed(photoTimeout, PHOTO_WAIT_MS);
                }
            } else if (!photoDone) {
                // 没接、也没回话：上一张还在拍（没接但当场回了话的 —— 管线放掉了、配置里一路都没启用 —— 已经走过 onPhotoResult）
                BlackBox.note("哨兵提醒：拍照没接（上一张还在拍），只发文字");
                photoDone = true;
                send();
            }
        }

        @Override
        public void onPhotoFiles(Map<String, File> files) {
            surround = files.get(CameraSlots.KEY_SURROUND);
        }

        @Override
        public void onPhotoResult(int saved, int pressed, int expected) {
            if (surround == null) {
                BlackBox.note("哨兵提醒：没拍到环视那一张（存下 " + saved + "/" + expected + " 路），只发文字");
            }
            photoFinished();
        }

        @Override
        public void onNoStorage() {
            BlackBox.note("哨兵提醒：没有 U 盘，拍不了照，只发文字");
            photoFinished();
        }

        private void photoFinished() {
            if (photoDone) {
                return;
            }
            photoDone = true;
            main.removeCallbacks(photoTimeout);
            send();
        }

        /** 交给发送线程：做图（有照片的话）、发。只走一次（拍照回话和到点二选一，{@link #photoDone} 管着）。 */
        private void send() {
            AlertStore store = AlertStore.of(app);
            AlertChannel channel = store.channel();
            Context text = Languages.localized(app);
            String title = text.getString(R.string.sentry_alert_title);
            String body = text.getString(trigger == SentryWatch.Trigger.HAZARD
                    ? R.string.sentry_alert_hazard : R.string.sentry_alert_lamps, moment(atMs));
            File photo = surround;
            AppConfig config = new AppConfig(app);
            boolean straighten = config.isFisheyeCorrection();
            float fov = config.getFisheyeFov();
            String projection = config.getFisheyeProjection();
            sender.execute(() -> {
                byte[] jpeg = photo == null ? null : picture(photo, straighten, fov, projection);
                AlertChannel.Result result;
                if (jpeg == null) {
                    if (photo != null) {
                        BlackBox.note("哨兵提醒：环视那一张压不进 " + PictureBudget.size(PictureBudget.ALERT_BYTES)
                                + "，只发文字");
                    }
                    result = channel.sendText(title, body);
                } else {
                    result = channel.sendPhoto(title, body, jpeg);
                    if (!result.ok && result.kind != AlertChannel.Kind.NETWORK
                            && result.kind != AlertChannel.Kind.TIMEOUT) {
                        // 服务器回了话、不收这张图（自建的服务器没开附件、图太大）：退一步只发文字
                        BlackBox.note("哨兵提醒：服务器不收图（" + result.kind + " " + result.httpCode
                                + "），改发文字");
                        result = channel.sendText(title, body);
                    }
                }
                AlertChannel.Result outcome = result;
                int pictureBytes = outcome.ok && jpeg != null ? jpeg.length : 0;
                main.post(() -> sent(outcome, pictureBytes));
            });
        }

        private void sent(AlertChannel.Result result, int pictureBytes) {
            AlertStore.of(app).noteResult(System.currentTimeMillis(), result);
            if (result.ok) {
                BlackBox.noteImportant("哨兵提醒：已发送" + (pictureBytes > 0
                        ? "（附图 " + PictureBudget.size(pictureBytes) + "）" : "（文字）"));
            } else {
                policy.noteFailure(SystemClock.elapsedRealtime());
                BlackBox.noteImportant("哨兵提醒：发送失败 " + result.kind + " " + result.httpCode + " " + result.detail);
            }
        }
    }

    // ================================================================= 发送测试

    /** 「发送测试」的结果（主线程）。The result of a test send, on the main thread. */
    public interface TestReply {
        void done(AlertChannel.Result result);
    }

    /**
     * 「用手机订阅」框里的「发送测试」：照现在的话题、服务器发一条测试提醒（只有文字）。不看布防、不占限额。主线程叫。
     *
     * A test alert from the subscribe dialog: text only, through the current topic and server. Ignores
     * arming and the rate limits. Call on the main thread.
     */
    public static void sendTest(Context context, TestReply reply) {
        Context appContext = context.getApplicationContext();
        AlertChannel channel = AlertStore.of(appContext).channel();
        Context text = Languages.localized(appContext);
        String title = text.getString(R.string.sentry_alert_title);
        String body = text.getString(R.string.sentry_alert_test, moment(System.currentTimeMillis()));
        INSTANCE.sender.execute(() -> {
            AlertChannel.Result result = channel.sendText(title, body);
            BlackBox.note("哨兵提醒：发送测试" + (result.ok ? "，已发送"
                    : "，失败 " + result.kind + " " + result.httpCode + " " + result.detail));
            INSTANCE.main.post(() -> reply.done(result));
        });
    }

    // ================================================================= 文字和图

    /**
     * 发送失败的原因，界面上那半句（「X 失败：原因」）：超时、服务器回了话不收（带 HTTP 码），其余都是连不上服务器。
     *
     * Why a send failed, as the UI says it: timed out, refused with an HTTP code, or could not reach the server.
     */
    public static String reason(Context context, AlertChannel.Kind kind, int httpCode) {
        if (kind == AlertChannel.Kind.TIMEOUT) {
            return context.getString(R.string.sentry_alerts_reason_timeout);
        }
        if (httpCode > 0) {
            return context.getString(R.string.sentry_alerts_reason_refused, httpCode);
        }
        return context.getString(R.string.sentry_alerts_reason_unreachable);
    }

    /** 提醒正文、设置里那一行小字里的时刻：「11-17 21:05」。The moment in the alert text and the status line. */
    public static String moment(long atMs) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(atMs));
    }

    /**
     * 环视那一张做成提醒的图：先缩到长边 {@link #MAX_SIDE_PX}，掰直（开关跟着屏幕上的鱼眼校正），左下角打上拍下的时刻，
     * 压进 100 KB。做不成返回 null（只发文字）。在发送线程上。
     */
    static byte[] picture(File file, boolean straighten, float fov, String projection) {
        Bitmap decoded = null;
        Bitmap straight = null;
        Bitmap stamped = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX);
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (decoded == null) {
                return null;
            }
            straight = AlertPicture.straighten(decoded, straighten, fov, projection);
            stamped = AlertPicture.stamped(straight, shotAt(file));
            return AlertPicture.jpegUnder(stamped != null ? stamped : straight, PictureBudget.ALERT_BYTES);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w(TAG, "could not make the alert picture: " + e);
            return null;
        } finally {
            if (stamped != null && stamped != straight) {
                stamped.recycle();
            }
            if (straight != null && straight != decoded) {
                straight.recycle();
            }
            if (decoded != null) {
                decoded.recycle();
            }
        }
    }

    /** 缩几倍解码（2 的幂），长边不超过 {@code maxSide}。The power-of-two sample size that fits {@code maxSide}. */
    static int sampleFor(int width, int height, int maxSide) {
        int sample = 1;
        while (Math.max(width, height) / sample > maxSide) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * 照片拍下的时刻：文件名里的时间戳（按快门那一刻，{@code yyyyMMdd_HHmmss_…}）；读不出来用文件的修改时间。
     *
     * When the photo was taken: the timestamp in its name (the shutter moment), or the file time.
     */
    static String shotAt(File file) {
        SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        String name = file.getName();
        if (name.length() >= 15) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT);
                in.setLenient(false);
                Date shot = in.parse(name.substring(0, 15));
                if (shot != null) {
                    return out.format(shot);
                }
            } catch (ParseException ignored) {
                // 不是我们起的名字：用文件时间
            }
        }
        return out.format(new Date(file.lastModified()));
    }
}
