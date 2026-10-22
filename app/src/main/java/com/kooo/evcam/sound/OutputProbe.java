package com.kooo.evcam.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * 试验（项目所有者 2026-10-22：想办法降低延迟，别先认定是车机的事）：车内喇叭那一路用哪种格式能拿到系统的低延迟通道。
 * 声浪用 48 kHz 浮点立体声，车上给的是普通混音；这里用几种格式（浮点 / 16 位 × 立体声 / 喇叭报的全部声道）各开一个试探输出，
 * 放一小段静音，看系统给的性能模式、缓冲、实际路由到哪。进程里只试一次（声浪开始以后、在自己的线程上），结果进诊断报告。
 * 不出声，不碰声浪正在用的那一路。
 */
final class OutputProbe {

    private static volatile String result = "";
    private static boolean started;

    private OutputProbe() {
    }

    /** 试一次（进程里只一次），不等。 */
    static synchronized void runOnce(Context context, String address) {
        if (started) {
            return;
        }
        started = true;
        result = "probing";
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> result = probe(app, address), "DriveSound-OutputProbe");
        thread.start();
    }

    /** 结果（诊断报告用）；没试过是空的。 */
    static String result() {
        return result;
    }

    private static String probe(Context context, String address) {
        AudioDeviceInfo device = CarSpeakers.find(context, address);
        if (device == null) {
            return "no " + address;
        }
        int channels = 2;
        for (int c : device.getChannelCounts()) {
            channels = Math.max(channels, c);
        }
        int[] counts = channels > 2 ? new int[] {2, channels} : new int[] {2};
        StringBuilder sb = new StringBuilder();
        for (int encoding : new int[] {AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT}) {
            for (int n : counts) {
                sb.append(sb.length() == 0 ? "" : "; ")
                        .append(encoding == AudioFormat.ENCODING_PCM_FLOAT ? "float" : "pcm16").append('x').append(n).append(' ')
                        .append(tryOne(device, encoding, n));
            }
        }
        return sb.toString();
    }

    private static String tryOne(AudioDeviceInfo device, int encoding, int channels) {
        AudioTrack track = null;
        try {
            AudioFormat.Builder format = new AudioFormat.Builder().setEncoding(encoding).setSampleRate(SoundOutput.RATE);
            if (channels == 2) {
                format.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO);
            } else {
                format.setChannelIndexMask((1 << channels) - 1);
            }
            boolean isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            int frames = SoundOutput.RATE * 40 / 1000;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(format.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(frames * channels * (isFloat ? 4 : 2))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return "not initialized";
            }
            track.setPreferredDevice(device);
            track.play();
            // 一小段静音，让路由落定
            int samples = frames / 2 * channels;
            if (isFloat) {
                track.write(new float[samples], 0, samples, AudioTrack.WRITE_NON_BLOCKING);
            } else {
                track.write(new short[samples], 0, samples, AudioTrack.WRITE_NON_BLOCKING);
            }
            Thread.sleep(60);
            AudioDeviceInfo routed = track.getRoutedDevice();
            return mode(track.getPerformanceMode()) + ", buffer " + track.getBufferSizeInFrames() + " of "
                    + track.getBufferCapacityInFrames() + " frames" + (routed == null ? "" : ", on " + routed.getAddress());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (RuntimeException e) {
            return "failed (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // 已经停了
                }
                track.release();
            }
        }
    }

    private static String mode(int mode) {
        switch (mode) {
            case AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:
                return "low-latency";
            case AudioTrack.PERFORMANCE_MODE_POWER_SAVING:
                return "power-saving";
            default:
                return "normal";
        }
    }
}
