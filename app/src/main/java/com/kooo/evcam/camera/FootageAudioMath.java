package com.kooo.evcam.camera;

/**
 * 环视录像里的声音和画面怎么对上时间、声音多响（{@link FootageAudio}、{@link CodecVideoRecorder}）。纯函数，{@code FootageAudioMathTest}。
 *
 * <p>画面的时间戳是相机采集那一刻（SurfaceTexture.getTimestamp），用的钟各家相机不一样：BOOTTIME（含深睡）或 MONOTONIC（不含）。
 * 两个钟只差车机睡过的时间，第一帧时比一比离哪个钟的「此刻」近就知道（{@link #cameraUsesMonotonic}）。
 * 声音的时刻由 AudioRecord.getTimestamp 给（BOOTTIME）：某一帧在某一刻采到，别的帧按采样率往前、往后推（{@link #captureNs}）。</p>
 */
final class FootageAudioMath {

    private FootageAudioMath() {
    }

    /**
     * 第 frameIndex 帧是什么时候采到的。
     *
     * @param anchorNs    anchorFrame 那一帧采到的时刻
     * @param anchorFrame 那一帧是第几帧（从开始录起数）
     * @param sampleRate  每秒多少帧
     */
    static long captureNs(long anchorNs, long anchorFrame, long frameIndex, int sampleRate) {
        return anchorNs + (frameIndex - anchorFrame) * 1_000_000_000L / sampleRate;
    }

    /**
     * 相机的时间戳是不是 MONOTONIC：离哪个钟的此刻近就是哪个。车机开机后一次没睡过时两个钟一样，算哪个都对。
     *
     * @param frameNs   一帧的时间戳（刚到的那一帧）
     * @param bootNowNs 此刻的 BOOTTIME（SystemClock.elapsedRealtimeNanos）
     * @param monoNowNs 此刻的 MONOTONIC（System.nanoTime）
     */
    static boolean cameraUsesMonotonic(long frameNs, long bootNowNs, long monoNowNs) {
        return Math.abs(monoNowNs - frameNs) < Math.abs(bootNowNs - frameNs);
    }

    /** BOOTTIME 上的一个时刻换到相机用的那个钟上（两个钟此刻差多少，按此刻量）。 */
    static long toCameraClock(long bootNs, boolean cameraMonotonic, long bootNowNs, long monoNowNs) {
        return cameraMonotonic ? bootNs - (bootNowNs - monoNowNs) : bootNs;
    }

    /**
     * 一段 16 位 PCM 的电平（dBFS，满幅是 0）。全是 0 的返回 {@link #SILENT_DB}。
     *
     * @param sumSquares 每个采样平方的和
     * @param count      采样个数
     */
    static int dbfs(double sumSquares, int count) {
        if (count <= 0 || sumSquares <= 0) {
            return SILENT_DB;
        }
        double rms = Math.sqrt(sumSquares / count);
        return (int) Math.round(20.0 * Math.log10(rms / 32768.0));
    }

    /** 全是 0 时写的电平：比 16 位能表示的最小声音（约 −90 dB）还低。 */
    static final int SILENT_DB = -120;

    /** 一个 16 位的采样（小端，PCM_16BIT 的字节序）。 */
    static int sample16(byte[] pcm, int at) {
        return (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8));
    }
}
