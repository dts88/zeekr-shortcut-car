package com.kooo.evcam.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import java.util.Locale;

/**
 * 一路输出：一个 {@link AudioTrack}（48 kHz、单声道、浮点、MODE_STREAM），指定到车机的某个 BUS。只在声音线程上用。
 *
 * <ul>
 *   <li>一定指定设备（容器里不指定就没声）；找不到那个 BUS 就不开，不交给系统随便放。</li>
 *   <li>用途按媒体（车内 BUS00_MEDIA 按媒体放听得到）；不请求音频焦点 —— 请求了会把音乐停掉（车机的焦点只是建议，系统不强制），
 *       导航播报、电话时车机压低媒体，声浪跟着压低。不改系统音量。</li>
 *   <li>先要低延迟模式；缓冲起步约 {@link #START_BUFFER_MS}，出现欠载（{@code getUnderrunCount} 涨了）就加 {@link #GROW_MS}，
 *       最多 {@link #MAX_BUFFER_MS}。输出延迟按 {@code getTimestamp} 估（诊断报告用）。</li>
 * </ul>
 */
final class SoundOutput {

    static final int RATE = 48_000;
    static final int START_BUFFER_MS = 30;
    static final int GROW_MS = 10;
    static final int MAX_BUFFER_MS = 120;

    private final AudioTrack track;
    private final String address;
    private final int capacityFrames;
    private final AudioTimestamp stamp = new AudioTimestamp();
    private int bufferFrames;
    private int underruns;
    private long written;
    private volatile float latencyMs = -1f;
    private volatile String grew = "";

    private SoundOutput(AudioTrack track, String address, int capacityFrames) {
        this.track = track;
        this.address = address;
        this.capacityFrames = capacityFrames;
    }

    /**
     * 开一路：找到 BUS、建 AudioTrack、指定设备、开始播放。开不起来抛 {@link IllegalStateException}，原因在消息里（英文，进黑匣子）。
     */
    static SoundOutput open(Context context, String address) {
        AudioDeviceInfo device = CarSpeakers.find(context, address);
        if (device == null) {
            throw new IllegalStateException("no output " + address);
        }
        int maxFrames = RATE * MAX_BUFFER_MS / 1000;
        int minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        int bytes = Math.max(minBytes > 0 ? minBytes : 0, maxFrames * 4);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(bytes)
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalStateException("AudioTrack build failed: " + e, e);
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack not initialized");
        }
        if (!track.setPreferredDevice(device)) {
            track.release();
            throw new IllegalStateException("setPreferredDevice refused " + address);
        }
        SoundOutput out = new SoundOutput(track, address, bytes / 4);
        int start = Math.min(out.capacityFrames, Math.max(track.getBufferSizeInFrames(), RATE * START_BUFFER_MS / 1000));
        out.bufferFrames = track.setBufferSizeInFrames(start);
        try {
            track.play();
        } catch (IllegalStateException e) {
            track.release();
            throw new IllegalStateException("play failed: " + e, e);
        }
        return out;
    }

    /** 写一块（阻塞到写进去）。写不进（设备没了之类）返回 false。 */
    boolean write(float[] buffer, int frames) {
        int done = track.write(buffer, 0, frames, AudioTrack.WRITE_BLOCKING);
        if (done < 0) {
            return false;
        }
        written += done;
        return true;
    }

    /**
     * 看一眼（声音线程上大约每秒一次）：欠载涨了就把缓冲加 {@link #GROW_MS}（到 {@link #MAX_BUFFER_MS} 为止）；估一次输出延迟。
     *
     * @return 这一次加了缓冲（叫的人记一行黑匣子）
     */
    boolean watch() {
        boolean grown = false;
        int count = track.getUnderrunCount();
        if (count > underruns) {
            underruns = count;
            int limit = Math.min(capacityFrames, RATE * MAX_BUFFER_MS / 1000);
            if (bufferFrames < limit) {
                int before = bufferFrames;
                bufferFrames = track.setBufferSizeInFrames(Math.min(limit, bufferFrames + RATE * GROW_MS / 1000));
                grown = bufferFrames > before;
                grew = before + "->" + bufferFrames;
            }
        }
        if (track.getTimestamp(stamp)) {
            long ageNs = System.nanoTime() - stamp.nanoTime;
            latencyMs = (written - stamp.framePosition) * 1000f / RATE - ageNs / 1_000_000f;
        }
        return grown;
    }

    /** 停掉、放掉（淡出由叫的人先做完）。 */
    void close() {
        try {
            track.pause();
            track.flush();
            track.stop();
        } catch (IllegalStateException ignored) {
            // 已经停了
        }
        track.release();
    }

    String address() {
        return address;
    }

    int underruns() {
        return underruns;
    }

    /** 一句话（英文，诊断报告 / 黑匣子用）。 */
    String describe() {
        return String.format(Locale.US, "%s %d Hz mono float, buffer %d frames (%.0f ms, cap %d), underruns %d%s, latency %s",
                address, RATE, bufferFrames, bufferFrames * 1000f / RATE, capacityFrames, underruns,
                grew.isEmpty() ? "" : " (grew " + grew + ")",
                latencyMs < 0f ? "?" : String.format(Locale.US, "%.0f ms", latencyMs));
    }
}
