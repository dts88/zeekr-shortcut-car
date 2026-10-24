package com.kooo.evcam.camera;

import android.annotation.SuppressLint;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * 环视录像里的声音（设置 → 录制「录制声音」，项目所有者 2026-10-20 定先做框架，2026-10-24 定为正式功能、默认开）：
 * 一个麦克风 → AAC → 交给环视那一路的录制器，
 * 和画面写进同一个文件（{@link CodecVideoRecorder#setFootageAudio}）。
 *
 * <p>一次开录一个、一条线程：录制器开录时起，叫停时停（{@link #stop} 不等）。读一块 PCM（{@link #CHUNK_FRAMES} 帧）
 * 就交给 AAC 编码器，把编码好的取出来交给录制器，都在这条线程上。</p>
 *
 * <p>出任何错都只停声音、记下来，不碰画面：权限没有、来源打不开、编码器起不来。后台被系统挡住时 AudioRecord 不报错、
 * 读到的全是 0 —— 连着全是 0 多久记在 {@link #status} 里（诊断报告）和黑匣子里，那就是被挡住的迹象。</p>
 *
 * <p>来源两种，上车比（Lab 2026-10-20，findings §2.43）：原始（VOICE_RECOGNITION，不指定设备时系统走 bottom 那一路，
 * 声音小、不经处理）和通话（VOICE_COMMUNICATION，走 id 80，最响，经回声消除 / 降噪；这个来源指定设备不管用）。
 * MIC / 系统默认在这台车上走的是蓝牙输入，多半是手机传来的声音，不用。都不指定设备，实际走了哪个记下来（{@link #status}）。</p>
 */
public final class FootageAudio {

    private static final String TAG = "FootageAudio";

    /** 来源：原始（不经降噪、回声消除）。存在设置里的值，见 {@link com.kooo.evcam.AppConfig#getFootageAudioSource}。 */
    static final String SOURCE_RAW = com.kooo.evcam.AppConfig.FOOTAGE_AUDIO_RAW;
    /** 来源：通话那一路（经回声消除、降噪）。 */
    static final String SOURCE_CALL = com.kooo.evcam.AppConfig.FOOTAGE_AUDIO_CALL;

    /** AAC 单声道 64 kbps：每小时约 29 MB（存储估算算上它，{@code ProfileEditorFragment}）。 */
    public static final int BIT_RATE = 64_000;
    /** 采样率按顺序试，第一个开得起来的用。 */
    private static final int[] RATES = {48_000, 44_100, 16_000};
    /** 一次读多少帧（单声道 16 位）：1024 帧，48 kHz 下约 21 毫秒，正好是 AAC 一帧的量。 */
    private static final int CHUNK_FRAMES = 1024;
    /** 连着全是 0 这么久，记一行黑匣子（多半是系统在后台挡住了麦克风）。 */
    private static final long ZERO_NOTE_MS = 3_000L;
    /** 停的时候等编码器交出最后几块最多多久。 */
    private static final long DRAIN_MS = 300L;
    /** 放掉录制器时等这条线程退出最多多久（{@link #stopAndWait}）。 */
    private static final long JOIN_MS = 1_000L;

    /** 收编码好的声音（{@link CodecVideoRecorder}）。都在声音线程上调，不能卡。 */
    interface Sink {
        /** 声音编码器报了输出格式：写入线程开轨时带上声音轨。 */
        void onAudioFormat(MediaFormat format);

        /**
         * 一块编码好的声音。
         *
         * @param captureBootUs 这一块第一帧的采集时刻（BOOTTIME，微秒）
         */
        void onAudioSample(long captureBootUs, byte[] data);
    }

    /** 设置里存的来源 → AudioSource。认不出的按原始。 */
    static int audioSourceFor(String source) {
        return SOURCE_CALL.equals(source) ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    /** 最近一次开录的那一个（这一次或者上一次）：诊断报告读它的 {@link #status}。 */
    private static volatile FootageAudio latest;

    /**
     * 环视录像此刻会不会带声音、用哪个来源：「录制声音」（设置 → 录制）开着、有录音权限、安卓 11 起。
     * 定录法（{@code MultiCameraManager}）、前台服务带不带麦克风类型、存储估算、诊断都问这一处；null = 不带。
     */
    public static String sourceFor(android.content.Context context, com.kooo.evcam.AppConfig config) {
        if (!config.isFootageAudioEnabled() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        return config.getFootageAudioSource();
    }

    /** 最近一次录像里的声音怎么样（诊断报告用）；这次开进程还没录过是 null。 */
    public static String latestStatus() {
        FootageAudio audio = latest;
        return audio == null ? null : audio.status();
    }

    private final String source;
    private final String cameraId;
    private final Sink sink;
    private volatile boolean running;
    private Thread thread;

    // ---- 诊断：声音线程写，随时读
    private volatile String phase = "starting";
    private volatile int sampleRate;
    private volatile String routed = "";
    private volatile String timing = "";
    private volatile long chunks;
    private volatile long encoded;
    private volatile long droppedChunks;
    private volatile int levelDb = FootageAudioMath.SILENT_DB;
    private volatile int peakDb = FootageAudioMath.SILENT_DB;
    private volatile long zeroRunMs;
    private volatile long longestZeroMs;
    private volatile String trouble = "";
    /** 这一阵全是 0 的已经记过黑匣子了：声音回来时再记一行。 */
    private boolean zeroNoted;

    /**
     * @param source   来源（{@link #SOURCE_RAW} / {@link #SOURCE_CALL}）
     * @param cameraId 哪一路的录像（日志用）
     */
    FootageAudio(String source, String cameraId, Sink sink) {
        this.source = source;
        this.cameraId = cameraId;
        this.sink = sink;
    }

    /** 起声音线程（开录时，不等）。 */
    void start() {
        if (running) {
            return;
        }
        running = true;
        latest = this;
        thread = new Thread(this::loop, "FootageAudio-" + cameraId);
        thread.start();
    }

    /** 叫停，不等：声音线程读完手上这一块（约 21 毫秒）就收尾、放掉麦克风和编码器、退出。 */
    void stop() {
        running = false;
    }

    /** 叫停并等它退出，最多 {@link #JOIN_MS}（放掉录制器时，后台线程上）。 */
    void stopAndWait() {
        stop();
        Thread t = thread;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord record = null;
        MediaCodec codec = null;
        try {
            record = openRecord();
            if (record == null) {
                return;
            }
            codec = openEncoder(record.getSampleRate());
            if (codec == null) {
                return;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                fail("start", null);
                return;
            }
            routed = describe(record.getRoutedDevice());
            phase = "recording";
            AppLog.i(TAG, "录音开始（相机 " + cameraId + "）：来源 " + sourceName() + "，" + sampleRate
                    + " Hz，走 " + routed);
            pump(record, codec);
        } catch (RuntimeException e) {
            fail("loop", e);
        } finally {
            release(record, codec);
            if (!"failed".equals(phase)) {
                phase = "stopped";
            }
            if (zeroRunMs > 0) {
                longestZeroMs = Math.max(longestZeroMs, zeroRunMs);
            }
            AppLog.i(TAG, "录音结束（相机 " + cameraId + "）：" + status());
        }
    }

    /**
     * 开麦克风：采样率按 {@link #RATES} 的顺序试。权限由宿主开录前查过（{@code MultiCameraManager} 定这一路带不带声音时），
     * 这里开不起来（包括权限）都当作这一次没有声音。
     */
    @SuppressLint("MissingPermission")
    private AudioRecord openRecord() {
        int audioSource = audioSourceFor(source);
        for (int rate : RATES) {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                continue;
            }
            AudioRecord record;
            try {
                AudioRecord.Builder builder = new AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(min, CHUNK_FRAMES * 2 * 4));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 通话那一路默认算「隐私敏感」：开着时语音助手收不到声音。行车记录仪不该挡它（Lab / CarPiano）
                    builder.setPrivacySensitive(false);
                }
                record = builder.build();
            } catch (RuntimeException e) {
                trouble = "open " + rate + ": " + e;
                continue;
            }
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                sampleRate = rate;
                return record;
            }
            record.release();
        }
        fail("open", null);
        return null;
    }

    /** AAC-LC 单声道编码器。 */
    private MediaCodec openEncoder(int rate) {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2);
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            return codec;
        } catch (IOException | RuntimeException e) {
            if (codec != null) {
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                    // 起都没起来，放不掉也没什么可做的
                }
            }
            fail("encoder", e);
            return null;
        }
    }

    /** 读一块、编一块，一直到叫停。时刻按 AudioRecord.getTimestamp 推；拿不到就按读到的那一刻往回推一块的长度。 */
    private void pump(AudioRecord record, MediaCodec codec) {
        byte[] pcm = new byte[CHUNK_FRAMES * 2];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        AudioTimestamp stamp = new AudioTimestamp();
        long framesRead = 0;
        long lastPtsUs = -1L;
        while (running) {
            int n = record.read(pcm, 0, pcm.length);
            if (n < 0) {
                fail("read " + n, null);
                return;
            }
            if (n < 2) {
                continue;
            }
            int frames = n / 2;
            long captureNs;
            if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                    && stamp.nanoTime > 0) {
                captureNs = FootageAudioMath.captureNs(stamp.nanoTime, stamp.framePosition, framesRead, sampleRate);
                timing = "timestamp";
            } else {
                captureNs = SystemClock.elapsedRealtimeNanos() - frames * 1_000_000_000L / sampleRate;
                timing = "estimated";
            }
            framesRead += frames;
            long ptsUs = captureNs / 1000L;
            if (ptsUs <= lastPtsUs) {
                ptsUs = lastPtsUs + 1;  // 编码器要单调递增的时间戳
            }
            lastPtsUs = ptsUs;
            chunks++;
            noteLevels(pcm, n, frames);
            feed(codec, pcm, n, ptsUs);
            drain(codec, info, 0L);
        }
        // 编码器里还在路上的几块交出去（停录收文件之后才到的，录制器丢掉，不碍事）
        try {
            int index = codec.dequeueInputBuffer(10_000);
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(codec, info, SystemClock.uptimeMillis() + DRAIN_MS);
            }
        } catch (RuntimeException e) {
            trouble = "end: " + e;
        }
    }

    /** 一块 PCM 交给编码器；输入缓冲区比一块小的话分几次交。编码器一时没空就丢这一块（数在 droppedChunks 里）。 */
    private void feed(MediaCodec codec, byte[] pcm, int length, long ptsUs) {
        int offset = 0;
        while (offset < length) {
            int index = codec.dequeueInputBuffer(10_000);
            if (index < 0) {
                droppedChunks++;
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) {
                droppedChunks++;
                return;
            }
            buffer.clear();
            int part = Math.min(buffer.remaining(), length - offset);
            buffer.put(pcm, offset, part);
            long partPtsUs = ptsUs + (offset / 2) * 1_000_000L / sampleRate;
            codec.queueInputBuffer(index, 0, part, partPtsUs, 0);
            offset += part;
        }
    }

    /**
     * 把编码好的取出来交给录制器。
     *
     * @param untilMs 0 = 有多少取多少、不等；否则一直取到结束标记，最晚到这个时刻（开机时长，不含深睡）
     */
    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, long untilMs) {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, untilMs > 0 ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (untilMs <= 0 || SystemClock.uptimeMillis() >= untilMs) {
                    return;
                }
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                AppLog.i(TAG, "录音编码器输出格式（相机 " + cameraId + "）：" + format);
                sink.onAudioFormat(format);
                continue;
            }
            if (index < 0) {
                continue;
            }
            ByteBuffer out = codec.getOutputBuffer(index);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (out != null && info.size > 0 && !config) {
                byte[] data = new byte[info.size];
                out.position(info.offset);
                out.limit(info.offset + info.size);
                out.get(data);
                encoded++;
                // 解码参数已经随输出格式交出去了（开轨时 muxer 拿到），只有参数集的那一块不当样本
                sink.onAudioSample(info.presentationTimeUs, data);
            }
            codec.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
        }
    }

    /** 这一块的电平、是不是全是 0；全是 0 连着超过 {@link #ZERO_NOTE_MS} 记一行黑匣子，声音回来时再记一行。 */
    private void noteLevels(byte[] pcm, int length, int frames) {
        double sum = 0;
        int peak = 0;
        boolean silent = true;
        for (int i = 0; i + 1 < length; i += 2) {
            int v = FootageAudioMath.sample16(pcm, i);
            if (v != 0) {
                silent = false;
            }
            int a = Math.abs(v);
            if (a > peak) {
                peak = a;
            }
            sum += (double) v * v;
        }
        if (silent) {
            zeroRunMs += frames * 1000L / sampleRate;
            if (!zeroNoted && zeroRunMs >= ZERO_NOTE_MS) {
                zeroNoted = true;
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录音（相机 " + cameraId + "，来源 " + sourceName()
                        + "）连着 " + (zeroRunMs / 1000) + " 秒全是 0：多半是系统在后台挡住了麦克风");
            }
            return;
        }
        if (zeroRunMs > 0) {
            longestZeroMs = Math.max(longestZeroMs, zeroRunMs);
            if (zeroNoted) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录音（相机 " + cameraId + "）声音回来了：之前连着 "
                        + String.format(Locale.US, "%.1f", zeroRunMs / 1000f) + " 秒全是 0");
            }
            zeroRunMs = 0;
            zeroNoted = false;
        }
        levelDb = FootageAudioMath.dbfs(sum, length / 2);
        peakDb = FootageAudioMath.dbfs((double) peak * peak, 1);
    }

    private void release(AudioRecord record, MediaCodec codec) {
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (RuntimeException e) {
                trouble = "stop: " + e;
            }
            record.release();
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException e) {
                trouble = "codec stop: " + e;
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
                // 放不掉也没什么可做的
            }
        }
    }

    private void fail(String stage, Throwable cause) {
        phase = "failed";
        trouble = stage + (cause != null ? ": " + cause : (trouble.isEmpty() ? "" : " (" + trouble + ")"));
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录音没开起来（相机 " + cameraId + "，来源 " + sourceName()
                + "）：" + trouble + "；录像照常，只是没有声音");
    }

    private String sourceName() {
        return SOURCE_CALL.equals(source) ? "VOICE_COMMUNICATION" : "VOICE_RECOGNITION";
    }

    /** 一个设备写成一行：类型 # id、地址（Lab 用的那种写法，对得上它的记录）。 */
    private static String describe(AudioDeviceInfo device) {
        if (device == null) {
            return "unknown";
        }
        String address;
        try {
            address = device.getAddress();
        } catch (RuntimeException e) {
            address = "";
        }
        return "type " + device.getType() + " #" + device.getId()
                + (address == null || address.isEmpty() ? "" : " " + address);
    }

    /** 给诊断报告：一行英文（只给维护者看）。 */
    String status() {
        return String.format(Locale.US,
                "%s · source %s · %d Hz · routed %s · timing %s · chunks %d · encoded %d · dropped %d"
                        + " · level %d dBFS (peak %d) · zero now %.1f s, longest %.1f s%s",
                phase, sourceName(), sampleRate, routed.isEmpty() ? "-" : routed, timing.isEmpty() ? "-" : timing,
                chunks, encoded, droppedChunks, levelDb, peakDb, zeroRunMs / 1000f,
                Math.max(longestZeroMs, zeroRunMs) / 1000f,
                trouble.isEmpty() ? "" : " · trouble " + trouble);
    }
}
