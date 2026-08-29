package com.kooo.evcam.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import java.io.File;

/**
 * 多路视频同步播放管理器
 * 支持1-4路视频同时播放，并保持同步
 */
public class MultiVideoPlayerManager {
    private static final String TAG = "MultiVideoPlayerManager";

    /** 支持的倍速 */
    public static final float[] SPEED_OPTIONS = {0.5f, 1.0f, 1.5f, 2.0f};
    
    private final Context context;
    private final Handler handler;
    /** 等「已渲染第一帧」最多等多久，超时就直接显示。 */
    private static final long SHOW_VIDEO_TIMEOUT_MS = 1500L;

    /** 各位置的播放器 */
    private ManagedVideoPlayer videoFront;
    private ManagedVideoPlayer videoBack;
    private ManagedVideoPlayer videoLeft;
    private ManagedVideoPlayer videoRight;
    private ManagedVideoPlayer videoSingle;  // 单路模式用

    /** 各位置的MediaPlayer引用（用于倍速控制） */

    /** 当前加载的视频组 */
    private VideoGroup currentGroup;

    /** 播放状态 */
    private boolean isPlaying = false;
    private boolean isPrepared = false;
    private int preparedCount = 0;
    private int totalVideos = 0;
    private boolean isStopping = false;

    /** 当前倍速 */
    private float currentSpeed = 1.0f;
    private int currentSpeedIndex = 1;  // 默认1.0x

    /** 是否单路模式 */
    private boolean isSingleMode = false;
    private String singleModePosition = VideoGroup.POSITION_FRONT;

    /** 视频时长（毫秒） */
    private int duration = 0;

    /** 播放状态监听器 */
    private OnPlaybackListener playbackListener;

    /** 进度更新任务 */
    private Runnable progressUpdateRunnable;

    public interface OnPlaybackListener {
        void onPrepared(int duration);
        void onProgressUpdate(int currentPosition);
        void onPlaybackStateChanged(boolean isPlaying);
        void onCompletion();
        void onError(String message);
        /** 单路视频准备好时回调（用于控制 UI 显示） */
        default void onSingleVideoPrepared() {}
    }

    public MultiVideoPlayerManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置各路的显示视图
     */
    /**
     * 传入五个 TextureView，本类为每一个建一个自己管的播放器。
     *
     * <p>以前这里收的是 VideoView。改掉的直接原因是透明：VideoView 内部是
     * SurfaceView，会在窗口上挖洞 —— 网格里四个窗口就是四个洞，放大成一路才只剩
     * 一个，所以「放大一路后就没有了」。TextureView 画在窗口里，没有这个行为。</p>
     */
    public void setVideoViews(TextureView front, TextureView back, TextureView left,
                              TextureView right, TextureView single) {
        this.videoFront = front != null ? new ManagedVideoPlayer(front) : null;
        this.videoBack = back != null ? new ManagedVideoPlayer(back) : null;
        this.videoLeft = left != null ? new ManagedVideoPlayer(left) : null;
        this.videoRight = right != null ? new ManagedVideoPlayer(right) : null;
        this.videoSingle = single != null ? new ManagedVideoPlayer(single) : null;
    }

    /**
     * 设置播放监听器
     */
    public void setPlaybackListener(OnPlaybackListener listener) {
        this.playbackListener = listener;
    }

    /**
     * 加载视频组
     */
    public void loadVideoGroup(VideoGroup group) {
        // 停止当前播放
        stopAll();

        this.currentGroup = group;
        this.isPrepared = false;
        this.preparedCount = 0;
        this.totalVideos = 0;
        this.duration = 0;

        if (group == null) {
            return;
        }

        // 统计要加载的视频数量
        if (group.hasVideo(VideoGroup.POSITION_FRONT)) totalVideos++;
        if (group.hasVideo(VideoGroup.POSITION_BACK)) totalVideos++;
        if (group.hasVideo(VideoGroup.POSITION_LEFT)) totalVideos++;
        if (group.hasVideo(VideoGroup.POSITION_RIGHT)) totalVideos++;

        if (totalVideos == 0) {
            if (playbackListener != null) {
                playbackListener.onError("No video files in this group");
            }
            return;
        }

        // 加载各位置视频
        loadVideoIfExists(VideoGroup.POSITION_FRONT, group.getFrontVideo(), videoFront);
        loadVideoIfExists(VideoGroup.POSITION_BACK, group.getBackVideo(), videoBack);
        loadVideoIfExists(VideoGroup.POSITION_LEFT, group.getLeftVideo(), videoLeft);
        loadVideoIfExists(VideoGroup.POSITION_RIGHT, group.getRightVideo(), videoRight);
        
        // 如果是单路模式，也加载单路视频
        if (isSingleMode && videoSingle != null) {
            loadSingleModeVideo(0, false);
        }
    }

    /**
     * 加载单个视频到对应的播放器
     */
    private void loadVideoIfExists(String position, File videoFile, ManagedVideoPlayer player) {
        if (videoFile == null || !videoFile.exists() || player == null) {
            return;
        }

        player.setListener(new ManagedVideoPlayer.Listener() {
            @Override
            public void onPrepared(ManagedVideoPlayer p, int durationMs) {
                Log.d(TAG, "Video prepared: " + position + ", duration=" + durationMs);
                if (durationMs > duration) {
                    duration = durationMs;
                }
                p.setSpeed(currentSpeed);
                preparedCount++;
                checkAllPrepared();
            }

            @Override
            public void onCompletion(ManagedVideoPlayer p) {
                isPlaying = false;
                if (playbackListener != null) {
                    playbackListener.onPlaybackStateChanged(false);
                    playbackListener.onCompletion();
                }
            }

            @Override
            public void onError(ManagedVideoPlayer p, int what, int extra) {
                if (!isStopping) {
                    Log.w(TAG, "Video error: " + position + ", what=" + what + ", extra=" + extra);
                    if (playbackListener != null) {
                        playbackListener.onError("Video error: " + position
                                + ", what=" + what + ", extra=" + extra);
                    }
                }
            }

            @Override
            public void onFirstFrame(ManagedVideoPlayer p) {
                // 有画面了才显示，避免露出上一个文件的残帧
                p.getView().setVisibility(View.VISIBLE);
            }
        });

        // 新画面出来之前先藏着
        player.getView().setVisibility(View.INVISIBLE);
        player.open(videoFile.getAbsolutePath(), 0, false);
    }

    /**
     * 检查是否所有视频都准备好了
     */
    private void checkAllPrepared() {
        if (preparedCount >= totalVideos) {
            isPrepared = true;
            Log.d(TAG, "All videos prepared, duration=" + duration);
            
            // 放弃音频焦点，让其他应用（如音乐播放器）继续播放
            abandonAudioFocus();
            
            if (playbackListener != null) {
                playbackListener.onPrepared(duration);
            }
            // 自动开始播放
            play();
        }
    }

    /**
     * 开始播放
     */
    public void play() {
        if (!isPrepared) {
            return;
        }

        isPlaying = true;

        if (isSingleMode) {
            // 单路模式播放 videoSingle（用户看到的视频）
            if (videoSingle != null) {
                videoSingle.play();
            }
        } else {
            // 多路模式播放所有
            if (videoFront != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_FRONT)) {
                videoFront.play();
            }
            if (videoBack != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_BACK)) {
                videoBack.play();
            }
            if (videoLeft != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_LEFT)) {
                videoLeft.play();
            }
            if (videoRight != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_RIGHT)) {
                videoRight.play();
            }
        }

        if (playbackListener != null) {
            playbackListener.onPlaybackStateChanged(true);
        }

        // 开始更新进度
        startProgressUpdate();
    }

    /**
     * 暂停播放
     */
    public void pause() {
        isPlaying = false;

        if (videoFront != null) videoFront.pause();
        if (videoBack != null) videoBack.pause();
        if (videoLeft != null) videoLeft.pause();
        if (videoRight != null) videoRight.pause();
        if (videoSingle != null) videoSingle.pause();

        if (playbackListener != null) {
            playbackListener.onPlaybackStateChanged(false);
        }
    }

    /**
     * 切换播放/暂停
     */
    public void togglePlayPause() {
        if (isPlaying) {
            pause();
        } else {
            play();
        }
    }

    /**
     * 停止所有播放
     */
    public void stopAll() {
        isPlaying = false;
        isPrepared = false;
        stopProgressUpdate();
        isStopping = true;

        try {
            detachAndStop(videoFront);
            detachAndStop(videoBack);
            detachAndStop(videoLeft);
            detachAndStop(videoRight);
            detachAndStop(videoSingle);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping playback", e);
        }

        isStopping = false;
    }

    /**
     * 停掉一路。
     *
     * <p>回调的丢弃、解码器的释放都由 {@link ManagedVideoPlayer} 自己负责 ——
     * 以前要在这里手动摘回调，是因为 VideoView 把播放器藏在内部，外面只能这么补。</p>
     */
    private void detachAndStop(ManagedVideoPlayer player) {
        if (player != null) {
            player.stop();
        }
    }

    /**
     * 跳转到指定位置
     */
    public void seekTo(int position) {
        if (!isPrepared) return;

        if (isSingleMode) {
            // 单路模式：操作 videoSingle（用户看到的视频）
            if (videoSingle != null) {
                videoSingle.seekTo(position);
            }
        } else {
            if (videoFront != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_FRONT)) {
                videoFront.seekTo(position);
            }
            if (videoBack != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_BACK)) {
                videoBack.seekTo(position);
            }
            if (videoLeft != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_LEFT)) {
                videoLeft.seekTo(position);
            }
            if (videoRight != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_RIGHT)) {
                videoRight.seekTo(position);
            }
        }
    }

    /**
     * 获取当前播放位置
     */
    public int getCurrentPosition() {
        // 返回当前播放视频的位置
        if (isSingleMode && videoSingle != null) {
            // 单路模式下优先从 videoSingle 获取位置
            try {
                int pos = videoSingle.getCurrentPosition();
                if (pos > 0) {
                    return pos;
                }
            } catch (Exception e) {
                // videoSingle 可能未准备好，尝试从四宫格获取
            }
            // 后备：从四宫格中对应位置的视频获取
            ManagedVideoPlayer sourceVideo = getSingleModeVideoView();
            if (sourceVideo != null) {
                try {
                    return sourceVideo.getCurrentPosition();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        
        // 多路模式或后备：从四宫格获取
        if (videoFront != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_FRONT)) {
            try {
                return videoFront.getCurrentPosition();
            } catch (Exception e) { /* ignore */ }
        }
        if (videoBack != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_BACK)) {
            try {
                return videoBack.getCurrentPosition();
            } catch (Exception e) { /* ignore */ }
        }
        if (videoLeft != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_LEFT)) {
            try {
                return videoLeft.getCurrentPosition();
            } catch (Exception e) { /* ignore */ }
        }
        if (videoRight != null && currentGroup != null && currentGroup.hasVideo(VideoGroup.POSITION_RIGHT)) {
            try {
                return videoRight.getCurrentPosition();
            } catch (Exception e) { /* ignore */ }
        }
        return 0;
    }

    /**
     * 获取视频总时长
     */
    public int getDuration() {
        return duration;
    }

    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * 循环切换倍速
     */
    public float cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % SPEED_OPTIONS.length;
        currentSpeed = SPEED_OPTIONS[currentSpeedIndex];
        
        // 应用新倍速到所有播放器
        applySpeedToAll();
        
        return currentSpeed;
    }

    /**
     * 设置倍速
     */
    public void setSpeed(float speed) {
        currentSpeed = speed;
        for (int i = 0; i < SPEED_OPTIONS.length; i++) {
            if (Math.abs(SPEED_OPTIONS[i] - speed) < 0.01) {
                currentSpeedIndex = i;
                break;
            }
        }
        
        applySpeedToAll();
    }

    /**
     * 获取当前倍速
     */
    public float getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * 设置MediaPlayer的播放速度
     */
    /** 把当前倍速下发给所有已就绪的播放器。 */
    private void applySpeedToAll() {
        for (ManagedVideoPlayer p : new ManagedVideoPlayer[]{
                videoFront, videoBack, videoLeft, videoRight, videoSingle}) {
            if (p != null) {
                p.setSpeed(currentSpeed);
            }
        }
    }

    /**
     * 放弃音频焦点，让其他应用（如音乐播放器）继续播放
     * 行车记录仪视频没有声音，不需要抢占音频焦点
     */
    private void abandonAudioFocus() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用新的 AudioFocusRequest API
                AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .build();
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                // 旧版本 API
                audioManager.abandonAudioFocus(null);
            }
        }
    }

    /**
     * 设置单路/多路模式
     */
    public void setSingleMode(boolean singleMode, String position) {
        // 先保存当前播放位置和状态
        int savedPosition = 0;
        boolean wasPlaying = isPlaying;
        
        if (isPrepared && currentGroup != null) {
            savedPosition = getCurrentPosition();
        }
        
        this.isSingleMode = singleMode;
        if (position != null) {
            this.singleModePosition = position;
        }

        // 如果已准备好，需要重新同步
        if (isPrepared && currentGroup != null) {
            if (singleMode) {
                // 切换到单路：先暂停多路视频
                if (videoFront != null) videoFront.pause();
                if (videoBack != null) videoBack.pause();
                if (videoLeft != null) videoLeft.pause();
                if (videoRight != null) videoRight.pause();
                // 将源视频的内容显示到单路播放器
                loadSingleModeVideo(savedPosition, wasPlaying);
            } else {
                // 切换回多路：暂停单路视频
                if (videoSingle != null) {
                    videoSingle.pause();
                }
                // 直接 seek 到保存的位置
                seekTo(savedPosition);
                if (wasPlaying) {
                    play();
                } else {
                    // 确保所有视频都暂停
                    if (videoFront != null) videoFront.pause();
                    if (videoBack != null) videoBack.pause();
                    if (videoLeft != null) videoLeft.pause();
                    if (videoRight != null) videoRight.pause();
                    isPlaying = false;
                    if (playbackListener != null) {
                        playbackListener.onPlaybackStateChanged(false);
                    }
                }
            }
        }
    }

    /**
     * 加载单路模式视频
     */
    private void loadSingleModeVideo(int seekPosition, boolean autoPlay) {
        if (currentGroup == null || videoSingle == null) return;

        File videoFile = currentGroup.getVideoFile(singleModePosition);
        if (videoFile == null || !videoFile.exists()) {
            return;
        }

        final int startAt = seekPosition;
        final boolean play = autoPlay;
        videoSingle.setListener(new ManagedVideoPlayer.SimpleListener() {
            @Override
            public void onPrepared(ManagedVideoPlayer p, int durationMs) {
                p.setSpeed(currentSpeed);
                abandonAudioFocus();
                isPlaying = play;
                if (playbackListener != null) {
                    playbackListener.onSingleVideoPrepared();
                    playbackListener.onPlaybackStateChanged(play);
                }
                if (play) {
                    startProgressUpdate();
                }
            }

            @Override
            public void onFirstFrame(ManagedVideoPlayer p) {
                p.getView().setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(ManagedVideoPlayer p, int what, int extra) {
                Log.w(TAG, "单路播放出错 what=" + what + " extra=" + extra);
                if (playbackListener != null) {
                    playbackListener.onError("单路播放出错: what=" + what);
                }
            }
        });

        // seek 与「是否自动播放」都交给播放器在 prepare 完成后处理 ——
        // 以前这里要自己 seek 再 pause（「某些设备 seek 后会自动播放」），
        // 那是因为 VideoView 的状态不在我们手里，只能事后纠正。
        videoSingle.getView().setVisibility(View.INVISIBLE);
        videoSingle.open(videoFile.getAbsolutePath(), startAt, play);
    }

    /**
     * 获取单路模式对应的播放器
     */
    private ManagedVideoPlayer getSingleModeVideoView() {
        switch (singleModePosition) {
            case VideoGroup.POSITION_FRONT:
                return videoFront;
            case VideoGroup.POSITION_BACK:
                return videoBack;
            case VideoGroup.POSITION_LEFT:
                return videoLeft;
            case VideoGroup.POSITION_RIGHT:
                return videoRight;
            default:
                return videoFront;
        }
    }

    /**
     * 是否是单路模式
     */
    public boolean isSingleMode() {
        return isSingleMode;
    }

    /**
     * 获取单路模式的位置
     */
    public String getSingleModePosition() {
        return singleModePosition;
    }

    /**
     * 更新单路模式的位置（不触发视频加载，仅更新状态）
     */
    public void updateSingleModePosition(boolean singleMode, String position) {
        this.isSingleMode = singleMode;
        if (position != null) {
            this.singleModePosition = position;
        }
    }

    /**
     * 开始进度更新
     */
    private void startProgressUpdate() {
        // 移除之前的更新任务，避免重复
        if (progressUpdateRunnable != null) {
            handler.removeCallbacks(progressUpdateRunnable);
        }

        progressUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && playbackListener != null) {
                    int position = getCurrentPosition();
                    playbackListener.onProgressUpdate(position);
                }
                if (isPlaying) {
                    handler.postDelayed(this, 200);
                }
            }
        };

        handler.postDelayed(progressUpdateRunnable, 200);
    }

    /**
     * 停止进度更新
     */
    private void stopProgressUpdate() {
        if (progressUpdateRunnable != null) {
            handler.removeCallbacks(progressUpdateRunnable);
            progressUpdateRunnable = null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stopAll();
        playbackListener = null;
    }

    /**
     * 检查指定位置是否有视频
     */
    public boolean hasVideo(String position) {
        return currentGroup != null && currentGroup.hasVideo(position);
    }

    /**
     * 获取当前视频组
     */
    public VideoGroup getCurrentGroup() {
        return currentGroup;
    }
}
