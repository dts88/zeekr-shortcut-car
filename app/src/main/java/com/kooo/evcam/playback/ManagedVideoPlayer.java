package com.kooo.evcam.playback;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Handler;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.MediaClock;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;

import com.kooo.evcam.AppLog;

import java.io.File;

/**
 * 我们自己持有生命周期的视频播放器：ExoPlayer（Media3）+ {@link TextureView}。
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
 * <h3>为什么不再用 MediaPlayer（2026-10-24）</h3>
 *
 * <p>MediaPlayer 拿声音的进度当时钟：车机报「声音放到哪了」，画面就跟到哪。车内媒体喇叭那一路报的
 * 比实际慢约 5 秒（模拟声浪的输出统计量到 5.1 秒，实际听感约半秒），MediaPlayer 照单全收，带声音的录像
 * 就画面停住、声音照放；它的同步方式也改不了。ExoPlayer 自己会检查这个报数，但门槛正好是 5 秒，
 * 这台车就在门槛边上。所以这里干脆不让声音领时间：画面按系统时钟走，声音只管交给系统放
 * （{@link FollowingAudioRenderer}）。代价是声音比画面晚一点，就是这一路实际的输出延迟。</p>
 *
 * <h3>状态</h3>
 *
 * <p>只有 {@link #prepared} 为真时才可以 seek / start / 读进度：每打开一个文件就新建一个播放器，
 * 第一次就绪才算准备好。这是 MediaPlayer 时期的直接教训 —— 对没准备好的播放器 seek 会把解码器搞坏，
 * 表现是抽搐、卡死、乱码；规矩留着，调用方照它写的。</p>
 *
 * <p>每次打开都会让 {@link #generation} 自增，回调里对不上就直接丢弃：
 * 释放过的播放器不该再有回调落到新状态上。</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class ManagedVideoPlayer {

    private static final String TAG = "ManagedVideoPlayer";

    /**
     * 预读多少：本地文件读得快，读够 4 秒就停、不到 2 秒再接着读，读到半秒就能开始放。
     * ExoPlayer 默认最多读 50 秒、视频能攒一百多 MB，几路一起开内存吃不消。
     */
    private static final int BUFFER_MIN_MS = 2000;
    private static final int BUFFER_MAX_MS = 4000;
    private static final int BUFFER_TO_START_MS = 500;
    private static final int BUFFER_TO_RESUME_MS = 1000;

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

    /**
     * 解码器往哪画。平时直接画到 TextureView 上；给了它，就先画进它给的 Surface，
     * 由它再画到 TextureView 上（视频回看的 GPU 逐像素鱼眼校正就是这么接进来的）。
     */
    public interface SurfaceRoute {
        /**
         * TextureView 的画布好了。
         *
         * @return 给解码器的 Surface；返回 null 表示不转，直接画到 {@code display} 上
         */
        Surface open(SurfaceTexture display, int width, int height);

        /** TextureView 的画布没了，或者播放器报废了：转接那一头也该收了。 */
        void close();
    }

    /** 空实现，省得每个调用方都写一堆空方法。 */
    public static class SimpleListener implements Listener {
        @Override public void onPrepared(ManagedVideoPlayer player, int durationMs) { }
        @Override public void onCompletion(ManagedVideoPlayer player) { }
        @Override public void onError(ManagedVideoPlayer player, int what, int extra) { }
        @Override public void onFirstFrame(ManagedVideoPlayer player) { }
    }

    /** 播放器出声用的属性。 */
    private static final AudioAttributes PLAYER_SOUND = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build();

    /** 回放出声用的属性：播放器和音频焦点（{@link PlaybackAudioFocus}）用同一份。 */
    public static final android.media.AudioAttributes SOUND_ATTRIBUTES =
            PLAYER_SOUND.getAudioAttributesV21().audioAttributes;

    private final TextureView textureView;
    /** 这一路放不放声音（只有环视那一路的录像带声音，{@link #setAudible}）。 */
    private boolean audible;
    /** 出声之前先申请的音频焦点（{@link #setSoundFocus}）；null = 不申请。 */
    private PlaybackAudioFocus soundFocus;
    /** 打开的这个文件放出声音：这一路放声音，文件里也有声音轨。准备好时看。 */
    private boolean fileHasSound;

    private ExoPlayer player;
    private Surface surface;
    /** 转接；null 表示直接画到 TextureView 上。 */
    private SurfaceRoute route;
    private Listener listener = new SimpleListener();

    private boolean surfaceReady;
    private boolean prepared;
    /** 到了这次打开要去的位置、那一帧画出来了（{@link Listener#onFirstFrame} 报过了）。 */
    private boolean shown;
    private boolean released;
    private boolean playWhenReady;
    private int generation;

    /** 这次打开就绪后跳到哪、怎么跳。 */
    private long startOffsetMs;
    private boolean startExact;

    /** surface 还没好时先存着，好了再执行。 */
    private String pendingPath;
    private long pendingOffsetMs;
    private boolean pendingExact;

    private String currentPath;

    /**
     * 这一路放不放声音：环视那一路放（它的录像带声音，设置「录制声音」），别的路不出声。打开下一个文件时生效。
     */
    public void setAudible(boolean audible) {
        this.audible = audible;
    }

    /** 放有声音的文件之前先申请音频焦点（回放界面交给环视那一路）。 */
    public void setSoundFocus(PlaybackAudioFocus focus) {
        this.soundFocus = focus;
    }

    public ManagedVideoPlayer(TextureView textureView) {
        this.textureView = textureView;
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = route != null ? route.open(st, w, h) : null;
                if (surface == null) {
                    surface = new Surface(st);
                }
                surfaceReady = true;
                if (player != null) {
                    player.setVideoSurface(surface);
                } else if (pendingPath != null) {
                    String path = pendingPath;
                    long offset = pendingOffsetMs;
                    pendingPath = null;
                    open(path, offset, playWhenReady, pendingExact);
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
                if (route != null) {
                    route.close();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    /**
     * 解码器改画进转接那一头。要在 TextureView 的画布好之前设（界面 onCreate 里），
     * 之后设的要等下一次画布重建才生效。
     */
    public void setSurfaceRoute(SurfaceRoute route) {
        this.route = route;
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
        open(path, offsetMs, autoPlay, false);
    }

    /**
     * 打开一个文件。
     *
     * @param exact 就绪后按 {@link #seekToExact} 跳，而不是只跳到关键帧
     */
    public void open(String path, long offsetMs, boolean autoPlay, boolean exact) {
        if (released || path == null) {
            return;
        }
        playWhenReady = autoPlay;

        if (!surfaceReady) {
            // surface 还没好，记下来等它
            pendingPath = path;
            pendingOffsetMs = offsetMs;
            pendingExact = exact;
            return;
        }

        // 上一个必须完全收掉再开新的，不能让两个解码器抢同一个 surface
        releasePlayer();

        final int myGeneration = ++generation;
        currentPath = path;
        prepared = false;
        shown = false;
        fileHasSound = false;
        startOffsetMs = offsetMs;
        startExact = exact;

        try {
            Context context = textureView.getContext();
            ExoPlayer exo = new ExoPlayer.Builder(context, renderers(context.getApplicationContext()))
                    .setLoadControl(new DefaultLoadControl.Builder()
                            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_TO_START_MS, BUFFER_TO_RESUME_MS)
                            .build())
                    .build();
            player = exo;
            exo.setVideoSurface(surface);
            if (audible) {
                // 焦点不交给它：回放界面的 PlaybackAudioFocus 管（只有放有声音的文件才申请）
                exo.setAudioAttributes(PLAYER_SOUND, false);
                // 车内的媒体喇叭（容器里不指定输出就没声）；找不到（不是这台车）交给系统选
                AudioDeviceInfo speaker = com.kooo.evcam.sound.CarSpeakers.cabin(context);
                if (speaker != null) {
                    exo.setPreferredAudioDevice(speaker);
                }
            }

            exo.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (myGeneration != generation) {
                        return;   // 上一次打开的回调，丢弃
                    }
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        settled(myGeneration);
                    }
                    if (state == Player.STATE_ENDED && myGeneration == generation) {
                        listener.onCompletion(ManagedVideoPlayer.this);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (myGeneration != generation) {
                        return;
                    }
                    AppLog.w(TAG, "播放出错 " + error.getErrorCodeName() + " path=" + path + "：" + error);
                    prepared = false;
                    listener.onError(ManagedVideoPlayer.this, error.errorCode, 0);
                }
            });

            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));
            exo.prepare();
        } catch (Exception e) {
            AppLog.e(TAG, "打开失败: " + path, e);
            prepared = false;
            releasePlayer();
            listener.onError(this, -1, 0);
        }
    }

    /**
     * 停下来了（就绪，或者放到了头）。
     *
     * <p>第一次是「准备好了」：跳到这次要去的位置、要放就放、告诉调用方。到了那个位置、那一帧画出来，
     * 才算新画面出来了 —— 就绪本身就意味着那一帧已经交给画面（不播时也会画第一帧）。要跳的，
     * 就等跳过去之后的那一次就绪；ExoPlayer 一开始画的是文件开头那一帧，揭开早了会闪一下。</p>
     */
    private void settled(int myGeneration) {
        if (!prepared) {
            prepared = true;
            fileHasSound = audible && player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO);
            boolean moving = startOffsetMs > 0;
            if (moving) {
                if (startExact) {
                    seekToExact(startOffsetMs);
                } else {
                    seekTo(startOffsetMs);
                }
            }
            if (playWhenReady) {
                claimSoundFocus();
                player.play();
            }
            listener.onPrepared(this, getDuration());
            if (moving || myGeneration != generation) {
                return;   // 跳过去之后再停下来才是要的那一帧；回调里换了文件的，这一个不管了
            }
        } else if (shown) {
            return;
        }
        shown = true;
        listener.onFirstFrame(this);
    }

    /**
     * 跳转。
     *
     * <p>只跳到关键帧（{@code CLOSEST_SYNC}）：精确跳转要从前一个关键帧一路解到
     * 目标位置，2560×2560 的帧解起来不便宜，连续拖动时那点代价会累积成明显的卡顿。
     * 回放行车记录仪时，落在最近的关键帧上完全够用。</p>
     */
    public void seekTo(long positionMs) {
        seek(positionMs, SeekParameters.CLOSEST_SYNC);
    }

    /**
     * 精确跳转（{@code EXACT}）：从前一个关键帧一路解到目标位置。
     *
     * <p>座舱那几路跟着环视走时用。关键帧最多隔 3 秒，只跳到关键帧的话，
     * 几路画面之间能差出好几秒；座舱画面小，解那几秒的帧不贵。</p>
     */
    public void seekToExact(long positionMs) {
        seek(positionMs, SeekParameters.EXACT);
    }

    private void seek(long positionMs, SeekParameters how) {
        if (!isPrepared()) {
            return;   // 没准备好不 seek（见类说明「状态」）
        }
        player.setSeekParameters(how);
        player.seekTo(positionMs);
    }

    public void play() {
        playWhenReady = true;
        if (isPrepared() && !player.getPlayWhenReady()) {
            claimSoundFocus();
            player.play();
        }
    }

    /** 要开始放了：这个文件放出声音的话，先申请音频焦点（拿着的就不再申请）。 */
    private void claimSoundFocus() {
        if (fileHasSound && soundFocus != null) {
            soundFocus.request();
        }
    }

    public void pause() {
        playWhenReady = false;
        if (isPrepared()) {
            player.pause();
        }
    }

    /** 在放：要放、还没放到头。跳转后正在读的那一下也算在放。 */
    public boolean isPlaying() {
        return isPrepared() && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED;
    }

    public int getCurrentPosition() {
        return isPrepared() ? (int) player.getCurrentPosition() : 0;
    }

    /**
     * 视频自身的宽高（像素）。未就绪时返回 0。
     *
     * <p>取景需要它：TextureView 默认把画面拉伸铺满自己的边框，
     * 不知道原始比例就没法判断该留多少黑边。</p>
     */
    public int getVideoWidth() {
        return isPrepared() ? player.getVideoSize().width : 0;
    }

    public int getVideoHeight() {
        return isPrepared() ? player.getVideoSize().height : 0;
    }

    public int getDuration() {
        return isPrepared() ? (int) Math.max(0L, player.getDuration()) : 0;
    }

    /** 倍速播放；声音跟着变速，音调不变。不改变放 / 停。 */
    public void setSpeed(float speed) {
        if (!isPrepared() || speed <= 0f) {
            return;
        }
        player.setPlaybackParameters(new PlaybackParameters(speed));
    }

    /**
     * 停止当前文件，但保留这个播放器对象。
     *
     * <p>与 {@link #release()} 的区别：release 之后本对象报废，而 stop 之后还可以
     * 再 {@link #open} 别的文件 —— 视频回看切换分组用的就是这个。</p>
     */
    public void stop() {
        releasePlayer();
        currentPath = null;
        pendingPath = null;
    }

    /** 彻底释放；释放后本对象不可再用。 */
    public void release() {
        released = true;
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (route != null) {
            route.close();
        }
    }

    /** 只放播放器，surface 留着 —— 下一次 open 还要用同一个。 */
    private void releasePlayer() {
        generation++;   // 让在途回调全部失效
        prepared = false;
        ExoPlayer exo = player;
        player = null;
        if (exo != null) {
            exo.release();
        }
    }

    /** 画面一路；放声音的那一路再加一路声音（{@link FollowingAudioRenderer}），别的路没有声音这一路，自然不出声。 */
    private RenderersFactory renderers(Context context) {
        boolean withSound = audible;
        return (eventHandler, videoListener, audioListener, textOutput, metadataOutput) -> {
            Renderer video = new MediaCodecVideoRenderer.Builder(context)
                    .setEventHandler(eventHandler)
                    .setEventListener(videoListener)
                    .build();
            if (!withSound) {
                return new Renderer[]{video};
            }
            return new Renderer[]{video, new FollowingAudioRenderer(context, eventHandler, audioListener)};
        };
    }

    /**
     * 声音只跟着放，不领时间（见类说明）。
     *
     * <p>ExoPlayer 的时钟本来由声音这一路给：它报车机的「放到哪了」。这里不给时钟，ExoPlayer 就按系统时钟走画面；
     * 声音照样一块块交给系统放，只是不再管着画面。有两件本来顺着时钟办的事要自己办：倍速、放没放完。</p>
     */
    private static final class FollowingAudioRenderer extends MediaCodecAudioRenderer {

        /** 时钟走到哪了（每次 render 传进来的，和声音块的时间戳同一个刻度）。 */
        private long positionUs;

        FollowingAudioRenderer(Context context, Handler eventHandler, AudioRendererEventListener eventListener) {
            super(context, MediaCodecSelector.DEFAULT, eventHandler, eventListener,
                    new DefaultAudioSink.Builder(context).build());
        }

        @Override
        public MediaClock getMediaClock() {
            return null;
        }

        @Override
        public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
            this.positionUs = positionUs;
            super.render(positionUs, elapsedRealtimeUs);
        }

        /**
         * 声音读到了头、时钟也走过了最后一块，就算放完。本来要等车机报「都放出去了」—— 它慢 5 秒，
         * 等它的话每一段末尾画面都要停几秒才接下一段。
         */
        @Override
        public boolean isEnded() {
            long last = getLastBufferInStreamPresentationTimeUs();
            return super.isEnded() || (last != C.TIME_UNSET && positionUs >= last);
        }

        /** 倍速本来随时钟交给声音这一路；不领时间就收不到，自己交给声音输出。 */
        @Override
        public void setPlaybackSpeed(float currentPlaybackSpeed, float targetPlaybackSpeed)
                throws ExoPlaybackException {
            super.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed);
            setPlaybackParameters(new PlaybackParameters(targetPlaybackSpeed));
        }
    }
}
