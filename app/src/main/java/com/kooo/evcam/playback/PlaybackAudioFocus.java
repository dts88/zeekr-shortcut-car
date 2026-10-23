package com.kooo.evcam.playback;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.blackbox.BlackBox;

/**
 * 录像回放出声时的音频焦点（项目所有者 2026-10-23）：和别的视频应用一样，放有声音的录像之前要媒体焦点
 * （收音机、CarPlay 这类让开），离开回放界面时放掉。
 *
 * <p>为什么要：以前回放不申请焦点、只指定车内媒体喇叭。车机放别的视频（自带播放器、App Lab 里的 VLC）都正常，
 * 我们放有声音的录像却几十秒后画面卡住、声音照放；声浪也要先开收音机或 CarPlay 才正常出声。先试车机是不是按焦点认音源
 * （docs/local/car-test-2.17.0-alpha.md 组 9）。</p>
 *
 * <p>焦点被别的应用长期拿走（开了收音机、CarPlay 放歌）：暂停，再按播放时重新申请。临时拿走（电话之类）：暂停，还回来时接着放。
 * 可以压低的（导航播报）：照常放，系统自己压低。没拿到焦点也照常放 —— 和以前一样，只是记一行。</p>
 *
 * <p>都在主线程上。</p>
 */
public final class PlaybackAudioFocus {

    /** 焦点被拿走、还回来（回放界面据此暂停、接着放）。 */
    public interface Listener {
        /**
         * @param transientLoss 临时拿走（电话之类），还回来时会有 {@link #onFocusRegained}
         */
        void onFocusLost(boolean transientLoss);

        void onFocusRegained();
    }

    private final AudioManager audioManager;
    private final AudioFocusRequest request;
    private final Listener listener;
    /** 拿着焦点（申请到了、还没被长期拿走、还没放掉）。 */
    private boolean held;

    public PlaybackAudioFocus(Context context, Listener listener) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
        this.request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(ManagedVideoPlayer.SOUND_ATTRIBUTES)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::onChange, new Handler(Looper.getMainLooper()))
                .build();
    }

    /** 要出声了：还没拿着就申请。 */
    public void request() {
        if (held || audioManager == null) {
            return;
        }
        int result = audioManager.requestAudioFocus(request);
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (held) {
            BlackBox.note("录像回放：申请音频焦点，拿到了");
        } else {
            BlackBox.note("录像回放：申请音频焦点没拿到（结果 " + result + "），照常放");
        }
    }

    /** 离开回放界面：拿着就放掉。 */
    public void abandon() {
        if (!held || audioManager == null) {
            return;
        }
        held = false;
        audioManager.abandonAudioFocusRequest(request);
        BlackBox.note("录像回放：放掉音频焦点");
    }

    private void onChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                held = false;
                BlackBox.note("录像回放：音频焦点被别的应用拿走，暂停");
                listener.onFocusLost(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                BlackBox.note("录像回放：音频焦点被临时拿走，暂停，还回来再接着放");
                listener.onFocusLost(true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                BlackBox.note("录像回放：音频焦点被临时拿走、可以压低，照常放");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                held = true;
                BlackBox.note("录像回放：音频焦点还回来了");
                listener.onFocusRegained();
                break;
            default:
                break;
        }
    }
}
