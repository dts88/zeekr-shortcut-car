package com.kooo.evcam.sound;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 车外喇叭放一遍礼让语音（悬浮按钮、灵动贴能选的动作，项目所有者 2026-11-01）。
 *
 * <p>语音是 {@code res/raw/outside_voice_go_first.wav}（48 kHz、16 位、单声道）。怎么往车外喇叭放都用模拟声浪现成的：
 * 输出是 {@link SoundOutput}，到喇叭之前过 {@link ExteriorMix}，线路和音量是「模拟声浪」页的「车外线路」「车外音量」
 * （车外只有一个喇叭，不另设一套）。放不了就提示一句、记进黑匣子，不换另一路。</p>
 *
 * <p>不看挡位、车速，按了就放；正在放时再按不算。</p>
 */
public final class OutsideVoice {

    private static final String TAG = "OutsideVoice";

    /** 语音前垫的静音：车外喇叭这一路刚打开时要不要时间没测过，别吃掉第一个字。 */
    static final int LEAD_IN_MS = 200;
    /** 语音后垫的静音：{@link SoundOutput#close} 会丢掉还在缓冲里的，缓冲最多 {@link SoundOutput#MAX_BUFFER_MS}。 */
    static final int TAIL_MS = 300;
    /** 一次写多少帧（10 毫秒）。 */
    private static final int BLOCK = SoundOutput.RATE / 100;
    /** 写多少块看一眼输出（{@link SoundOutput#watch}）。 */
    private static final int WATCH_BLOCKS = 25;

    private static final AtomicBoolean playing = new AtomicBoolean();

    private OutsideVoice() {
    }

    /** 放一遍（主线程上叫，马上返回）。 */
    public static void play(Context context) {
        if (!playing.compareAndSet(false, true)) {
            AppLog.i(TAG, "already playing");
            return;
        }
        Context app = context.getApplicationContext();
        AppConfig config = new AppConfig(app);
        String address = CarSpeakers.exteriorAddress(config.getDriveSoundExteriorRoute());
        float db = DriveSoundRules.exteriorDb(config.getDriveSoundExteriorVolume());
        new Thread(() -> {
            try {
                raisePriority();
                playOnce(app, address, db);
            } catch (IOException | RuntimeException e) {
                AppLog.w(TAG, "can't play: " + e);
                BlackBox.note("车外语音：放不了（" + e + "）");
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, R.string.msg_outside_voice_failed, Toast.LENGTH_SHORT).show());
            } finally {
                playing.set(false);
            }
        }, "OutsideVoice").start();
    }

    private static void playOnce(Context app, String address, float db) throws IOException {
        float[] voice = parseWav(readClip(app));
        SoundOutput out = SoundOutput.open(app, address);
        try {
            BlackBox.note("车外语音：开始（" + out.describe() + "，车外音量 +" + db + " dB）");
            ExteriorMix mix = new ExteriorMix(SoundOutput.RATE);
            float gain = EngineVoice.dbToLin(db);
            float[] in = new float[2 * BLOCK];
            float[] lr = new float[2 * BLOCK];
            int total = totalFrames(voice.length);
            int blocks = 0;
            for (int at = 0; at < total; at += BLOCK) {
                fill(voice, at - framesOf(LEAD_IN_MS), in, BLOCK);
                mix.process(in, lr, BLOCK, gain);
                if (!out.write(lr, BLOCK)) {
                    throw new IllegalStateException("write failed");
                }
                if (++blocks % WATCH_BLOCKS == 0) {
                    out.watch();
                }
            }
            BlackBox.note("车外语音：放完（" + out.describe() + "）");
        } finally {
            out.close();
        }
    }

    private static void raisePriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "audio thread priority refused: " + e);
        }
    }

    private static byte[] readClip(Context app) throws IOException {
        try (InputStream in = app.getResources().openRawResource(R.raw.outside_voice_go_first)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                bytes.write(chunk, 0, n);
            }
            return bytes.toByteArray();
        }
    }

    private static int framesOf(int ms) {
        return SoundOutput.RATE * ms / 1000;
    }

    /** 一遍一共写多少帧：垫的静音加语音。 */
    static int totalFrames(int voiceFrames) {
        return framesOf(LEAD_IN_MS) + voiceFrames + framesOf(TAIL_MS);
    }

    /** 从语音的第 {@code from} 帧起填一块（左右一样）；{@code from} 是负的、或者过了语音末尾的地方是静音。 */
    static void fill(float[] voice, int from, float[] lr, int frames) {
        for (int i = 0; i < frames; i++) {
            int at = from + i;
            float s = at >= 0 && at < voice.length ? voice[at] : 0f;
            lr[2 * i] = s;
            lr[2 * i + 1] = s;
        }
    }

    /**
     * WAV 的样本（−1 到 1）。只认 48 kHz、16 位、单声道的 PCM：别的放出来会变调，不如说放不了。
     *
     * @throws IllegalArgumentException 不是这种格式
     */
    static float[] parseWav(byte[] wav) {
        if (wav.length < 12 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) {
            throw new IllegalArgumentException("not a WAV");
        }
        boolean formatOk = false;
        int at = 12;
        while (at + 8 <= wav.length) {
            int size = (int) Math.min(int32(wav, at + 4) & 0xFFFFFFFFL, wav.length - at - 8);
            int body = at + 8;
            if (tag(wav, at, "fmt ")) {
                formatOk = size >= 16 && int16(wav, body) == 1 && int16(wav, body + 2) == 1
                        && int32(wav, body + 4) == SoundOutput.RATE && int16(wav, body + 14) == 16;
                if (!formatOk) {
                    throw new IllegalArgumentException("not 48 kHz 16-bit mono PCM");
                }
            } else if (tag(wav, at, "data")) {
                if (!formatOk) {
                    throw new IllegalArgumentException("no format before the data");
                }
                float[] samples = new float[size / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) int16(wav, body + 2 * i) / 32768f;
                }
                return samples;
            }
            // 单数大小的块后面补一个字节
            at = body + size + (size & 1);
        }
        throw new IllegalArgumentException("no data");
    }

    private static boolean tag(byte[] b, int at, String name) {
        for (int i = 0; i < name.length(); i++) {
            if (b[at + i] != name.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int int16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int int32(byte[] b, int at) {
        return int16(b, at) | int16(b, at + 2) << 16;
    }
}
