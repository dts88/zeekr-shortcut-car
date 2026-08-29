package com.kooo.evcam.playback;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;

import com.kooo.evcam.AppLog;

/**
 * 我们自己持有生命周期的视频播放器：{@link MediaPlayer} + {@link TextureView}。
 *
 * <h3>为什么不再用 VideoView</h3>
 *
 * <p>回放的几个毛病改了四轮都没根治，共同原因就是 {@code VideoView}：</p>
 *
 * <table>
 *   <tr><td>小窗发虚/透明</td>
 *       <td>它是 SurfaceView —— 在窗口上挖洞，画面在洞后面合成。
 *           网格里几个窗口就是几个洞，放大成一路就只剩一个，所以「放大后就好了」。</td></tr>
 *   <tr><td>快速切换就绿屏/马赛克</td>
 *       <td>MediaPlayer 的生命周期藏在它内部，每次 setVideoPath 重建一次，
 *           外面插不进手，也没法保证上一个真的收干净了。</td></tr>
 *   <tr><td>拖动几次之后卡顿</td>
 *       <td>没法预加载，每次都是完整的 stop → create → prepare。</td></tr>
 * </table>
 *
 * <p>换成 TextureView 之后：<b>它是普通视图，不挖洞</b>，透明问题从根上消失；
 * 播放器由我们建、我们放，状态明确；还能先 prepare 不播，用于预加载。</p>
 *
 * <h3>状态</h3>
 *
 * <p>只有 {@link #prepared} 为真时才可以 seek / start / 读进度。这是之前那批问题的
 * 直接教训 —— 对没准备好的播放器 seek 会把解码器搞坏，表现是抽搐、卡死、乱码。</p>
 *
 * <p>每次打开都会让 {@link #generation} 自增，回调里对不上就直接丢弃：
 * 释放过的播放器仍可能把回调送达，落到新状态上就会算错。</p>
 */
public class ManagedVideoPlayer {

    private static final String TAG = "ManagedVideoPlayer";

    /** 等「已渲染第一帧」最多等多久；不是所有实现都会发这个事件。 */
    private static final long FIRST_FRAME_TIMEOUT_MS = 1500L;

    public interface Listener {
        /** 已就绪，可以 seek / 播放。 */
        void onPrepared(ManagedVideoPlayer player, int durationMs);

        /** 本文件播完。 */
        void onCompletion(ManagedVideoPlayer player);

        /** 出错；返回后播放器已不可用，需要重新 open。 */
        void onError(ManagedVideoPlayer player, int what, int extra);

        /** 新画面真的显示出来了 —— 用于揭开切换时的遮罩。 */
        void onFirstFrame(ManagedVideoPlayer player);
    }

    /** 空实现，省得每个调用方都写一堆空方法。 */
    public static class SimpleListener implements Listener {
        @Override public void onPrepared(ManagedVideoPlayer player, int durationMs) { }
        @Override public void onCompletion(ManagedVideoPlayer player) { }
        @Override public void onError(ManagedVideoPlayer player, int what, int extra) { }
        @Override public void onFirstFrame(ManagedVideoPlayer player) { }
    }

    private final TextureView textureView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private Surface surface;
    private Listener listener = new SimpleListener();

    private boolean surfaceReady;
    private boolean prepared;
    private boolean released;
    private boolean playWhenReady;
    private int generation;

    /** surface 还没好时先存着，好了再执行。 */
    private String pendingPath;
    private long pendingOffsetMs;

    private String currentPath;
    private Runnable firstFrameFallback;

    public ManagedVideoPlayer(TextureView textureView) {
        this.textureView = textureView;
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                surfaceReady = true;
                if (player != null) {
                    player.setSurface(surface);
                } else if (pendingPath != null) {
                    String path = pendingPath;
                    long offset = pendingOffsetMs;
                    pendingPath = null;
                    open(path, offset, playWhenReady);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                // surface 没了就不能再往上画。释放播放器，而不是让它继续往一个
                // 已经消失的目标推帧 —— 那正是之前 BufferQueue 一直超时的原因。
                releasePlayer();
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener != null ? listener : new SimpleListener();
    }

    public TextureView getView() {
        return textureView;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    /** 只有它为真时才可以 seek / start / 读进度。 */
    public boolean isPrepared() {
        return prepared && player != null;
    }

    /**
     * 打开一个文件。
     *
     * @param path      文件路径
     * @param offsetMs  就绪后跳到的位置
     * @param autoPlay  就绪后是否自动播放
     */
    public void open(String path, long offsetMs, boolean autoPlay) {
        if (released || path == null) {
            return;
        }
        playWhenReady = autoPlay;

        if (!surfaceReady) {
            // surface 还没好，记下来等它
            pendingPath = path;
            pendingOffsetMs = offsetMs;
            return;
        }

        // 上一个必须完全收掉再开新的，不能让两个解码器抢同一个 surface
        releasePlayer();

        final int myGeneration = ++generation;
        currentPath = path;
        prepared = false;

        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setSurface(surface);
            mp.setDataSource(path);
            mp.setVolume(0f, 0f);   // 行车记录仪没有声音

            mp.setOnPreparedListener(p -> {
                if (myGeneration != generation) {
                    return;   // 上一次打开的回调，丢弃
                }
                prepared = true;
                if (offsetMs > 0) {
                    seekTo(offsetMs);
                }
                if (playWhenReady) {
                    p.start();
                }
                armFirstFrameFallback();
                listener.onPrepared(ManagedVideoPlayer.this, p.getDuration());
            });

            mp.setOnCompletionListener(p -> {
                if (myGeneration != generation) {
                    return;
                }
                listener.onCompletion(ManagedVideoPlayer.this);
            });

            mp.setOnErrorListener((p, what, extra) -> {
                if (myGeneration != generation) {
                    return true;
                }
                AppLog.w(TAG, "播放出错 what=" + what + " extra=" + extra + " path=" + path);
                prepared = false;
                listener.onError(ManagedVideoPlayer.this, what, extra);
                return true;
            });

            mp.setOnInfoListener((p, what, extra) -> {
                if (myGeneration == generation
                        && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    cancelFirstFrameFallback();
                    listener.onFirstFrame(ManagedVideoPlayer.this);
                }
                return false;
            });

            mp.prepareAsync();
        } catch (Exception e) {
            AppLog.e(TAG, "打开失败: " + path, e);
            prepared = false;
            releasePlayer();
            listener.onError(this, -1, 0);
        }
    }

    /**
     * 跳转。
     *
     * <p>用 {@code SEEK_CLOSEST_SYNC} 只跳到关键帧：精确跳转要从前一个关键帧一路解到
     * 目标位置，2560×2560 的帧解起来不便宜，连续拖动时那点代价会累积成明显的卡顿。
     * 回放行车记录仪时，落在最近的关键帧上完全够用。</p>
     */
    public void seekTo(long positionMs) {
        if (!isPrepared()) {
            return;   // 没准备好就 seek 会把解码器搞坏
        }
        try {
            player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST_SYNC);
        } catch (Exception e) {
            AppLog.w(TAG, "seek 失败: " + e);
        }
    }

    public void play() {
        playWhenReady = true;
        if (isPrepared() && !player.isPlaying()) {
            try {
                player.start();
            } catch (Exception e) {
                AppLog.w(TAG, "start 失败: " + e);
            }
        }
    }

    public void pause() {
        playWhenReady = false;
        if (isPrepared() && player.isPlaying()) {
            try {
                player.pause();
            } catch (Exception e) {
                AppLog.w(TAG, "pause 失败: " + e);
            }
        }
    }

    public boolean isPlaying() {
        try {
            return isPrepared() && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int getCurrentPosition() {
        try {
            return isPrepared() ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return isPrepared() ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 倍速播放；失败不影响正常播放。 */
    public void setSpeed(float speed) {
        if (!isPrepared() || speed <= 0f) {
            return;
        }
        try {
            boolean wasPlaying = player.isPlaying();
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            if (!wasPlaying) {
                player.pause();   // setPlaybackParams 会自动开始播放
            }
        } catch (Exception e) {
            AppLog.w(TAG, "设置倍速失败: " + e);
        }
    }

    /**
     * 停止当前文件，但保留这个播放器对象。
     *
     * <p>与 {@link #release()} 的区别：release 之后本对象报废，而 stop 之后还可以
     * 再 {@link #open} 别的文件 —— 视频回看切换分组用的就是这个。</p>
     */
    public void stop() {
        cancelFirstFrameFallback();
        releasePlayer();
        currentPath = null;
        pendingPath = null;
    }

    /** 彻底释放；释放后本对象不可再用。 */
    public void release() {
        released = true;
        cancelFirstFrameFallback();
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    /** 只放播放器，surface 留着 —— 下一次 open 还要用同一个。 */
    private void releasePlayer() {
        generation++;   // 让在途回调全部失效
        prepared = false;
        cancelFirstFrameFallback();
        MediaPlayer mp = player;
        player = null;
        if (mp == null) {
            return;
        }
        try {
            mp.setOnPreparedListener(null);
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.setOnInfoListener(null);
            mp.reset();
            mp.release();
        } catch (Exception e) {
            AppLog.w(TAG, "释放播放器时出错: " + e);
        }
    }

    /**
     * MEDIA_INFO_VIDEO_RENDERING_START 不是所有实现都会发。
     * 不兜底的话，等着它来揭遮罩的调用方会一直等下去，画面永远不出现。
     */
    private void armFirstFrameFallback() {
        cancelFirstFrameFallback();
        firstFrameFallback = () -> listener.onFirstFrame(this);
        handler.postDelayed(firstFrameFallback, FIRST_FRAME_TIMEOUT_MS);
    }

    private void cancelFirstFrameFallback() {
        if (firstFrameFallback != null) {
            handler.removeCallbacks(firstFrameFallback);
            firstFrameFallback = null;
        }
    }
}
