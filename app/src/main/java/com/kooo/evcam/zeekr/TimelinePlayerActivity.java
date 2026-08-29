package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 连续时间轴回放（实验性）。
 *
 * <p>普通回放是一堆文件挨个找、挨个放；这里把连续录制的分段拼成一条时间轴，
 * 拖动进度条可以跨文件定位 —— 行车记录仪的实际用法是「我想看 20 分钟前那段」，
 * 用户不关心它落在第几个文件里。</p>
 *
 * <p>时间轴换算全部交给 {@link RecordingTimeline}（纯逻辑、有单元测试）。
 * 这里只负责：读时长、按定位切文件、跨段自动续播。</p>
 *
 * <p><b>已知限制</b>：用的是 VideoView/MediaPlayer，切文件时需要重新 prepare，
 * 因此段与段之间有零点几秒的停顿。要做到无缝需要换成 ExoPlayer 的
 * ConcatenatingMediaSource，但那会显著增大 APK —— 先看这个形态是否够用。</p>
 */
public class TimelinePlayerActivity extends Activity {

    private static final String TAG = "TimelinePlayer";
    /** 进度刷新间隔。 */
    private static final long TICK_MS = 500L;
    /** 连续出错多少次就停止自动续播。 */
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    /**
     * 等「已渲染第一帧」最多等多久，超时就直接显示。
     *
     * <p>MEDIA_INFO_VIDEO_RENDERING_START 并非所有实现都会发。真要不发，
     * 没有这个兜底画面就永远不显示了 —— 那比它本来要避免的脏帧更糟。</p>
     */
    private static final long SHOW_VIDEO_TIMEOUT_MS = 1500L;
    /** 连续回放只播环视合成流那一路；座舱各路不参与时间轴。 */
    private static final String COMPOSITE_SLOT = "front";

    private VideoView videoView;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView infoText;
    private Button playPauseButton;
    private Button prevSessionButton;
    private Button nextSessionButton;
    private RecyclerView sessionListView;
    private TextView listSummaryText;
    private TimelineSessionAdapter sessionAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<RecordingTimeline.Session> sessions = new ArrayList<>();
    private int sessionIndex = 0;
    private int currentSegmentIndex = -1;
    /**
     * 已经 prepare 完成、可以安全 seek/start 的段；-1 表示当前没有可用的播放器。
     *
     * <p>这个区分是必须的：{@code setVideoPath()} 只是发起异步 prepare，
     * 在 onPrepared 之前对播放器 seek 会让解码器进入坏状态 ——
     * 表现就是画面抽搐、卡死，严重时直接出乱码。</p>
     */
    private int preparedSegmentIndex = -1;
    /** 正在 prepare 的段；-1 表示没有在途的打开操作。 */
    private int preparingSegmentIndex = -1;
    /** prepare 完成后要跳到的段内偏移。 */
    private long pendingOffsetMs = 0L;
    /** prepare 完成后是否自动开始播放（用户暂停过就不该被强制恢复）。 */
    private boolean playWhenReady = true;
    /** 连续出错次数，用来阻止「出错→跳下一段→又出错」无限翻下去。 */
    private int consecutiveErrors = 0;
    /** onStop 释放播放器时记下的时间轴位置，回到前台后从这里恢复；-1 表示无需恢复。 */
    private long positionToRestoreMs = -1L;
    /** 用户正在拖动进度条时不要被自动刷新打断。 */
    private boolean userSeeking = false;

    /** 超时兜底：没等到「已渲染」也把画面放出来。 */
    private final Runnable showVideoFallback = new Runnable() {
        @Override
        public void run() {
            if (videoView != null) {
                videoView.setVisibility(View.VISIBLE);
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!userSeeking) {
                updateProgress();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timeline_player);

        videoView = findViewById(R.id.timeline_video);
        seekBar = findViewById(R.id.timeline_seek);
        positionText = findViewById(R.id.timeline_position);
        infoText = findViewById(R.id.timeline_info);
        playPauseButton = findViewById(R.id.timeline_play_pause);
        prevSessionButton = findViewById(R.id.timeline_prev_session);
        nextSessionButton = findViewById(R.id.timeline_next_session);
        sessionListView = findViewById(R.id.timeline_session_list);
        listSummaryText = findViewById(R.id.timeline_list_summary);

        sessionAdapter = new TimelineSessionAdapter(this::switchSession);
        if (sessionListView != null) {
            sessionListView.setLayoutManager(new LinearLayoutManager(this));
            sessionListView.setAdapter(sessionAdapter);
        }

        View close = findViewById(R.id.timeline_close);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> togglePlayPause());
        }
        if (prevSessionButton != null) {
            prevSessionButton.setOnClickListener(v -> switchSession(sessionIndex - 1));
        }
        if (nextSessionButton != null) {
            nextSessionButton.setOnClickListener(v -> switchSession(sessionIndex + 1));
        }

        setupSeekBar();

        // 新的一段真正渲染出第一帧之前，别把画面露出来。
        //
        // VideoView 的这块 SurfaceView 在多次 setVideoPath 之间是复用的，
        // 上一个播放器被中途拆掉时，缓冲队列里可能还留着它分配了却没写过的缓冲区 ——
        // 那块内存以 YUV420 解读就是全绿；也可能是上一个文件的残帧，看起来像马赛克。
        // 文件本身没问题（拿到电脑上播是好的），解码器也支持这个尺寸，
        // 所以要挡住的不是解码，而是「把脏缓冲区显示出来」这件事。
        videoView.setOnInfoListener((mp, what, extra) -> {
            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                handler.removeCallbacks(showVideoFallback);
                videoView.setVisibility(View.VISIBLE);
            }
            return false;
        });

        // 三个监听器只在这里注册一次。以前 onPrepared 是每次切文件时重新注册的，
        // 那样闭包捕获的是那一次的偏移量，切换密集时可能把旧偏移套到新文件上。
        videoView.setOnPreparedListener(mp -> {
            preparedSegmentIndex = preparingSegmentIndex;
            preparingSegmentIndex = -1;
            consecutiveErrors = 0;

            // 明确清掉 seekComplete 回调。以前这里挂的是 m -> videoView.start()，
            // 而它对这个播放器的每一次 seek 都会触发 —— 用户暂停了也会被强制播放。
            try {
                mp.setOnSeekCompleteListener(null);
            } catch (Exception ignored) {
                // 某些实现允许传 null，失败也不影响后续
            }

            videoView.seekTo((int) pendingOffsetMs);
            if (playWhenReady) {
                videoView.start();
            }
            updatePlayPauseLabel();
        });

        // 播完一段自动接下一段
        videoView.setOnCompletionListener(mp -> advanceToNextSegment());
        videoView.setOnErrorListener((mp, what, extra) -> {
            AppLog.w(TAG, "播放出错 what=" + what + " extra=" + extra
                    + " 段=" + currentSegmentIndex);
            preparedSegmentIndex = -1;
            preparingSegmentIndex = -1;
            if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                // 连续出错就停下来，不要一路翻到底
                AppLog.e(TAG, "连续 " + consecutiveErrors + " 次播放出错，停止自动续播");
                Toast.makeText(this, "这段录像无法播放，已停止", Toast.LENGTH_LONG).show();
                consecutiveErrors = 0;
                return true;
            }
            return advanceToNextSegment();
        });

        loadTimelines();
    }

    private void setupSeekBar() {
        if (seekBar == null) {
            return;
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    showPosition(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                seekTimelineTo(bar.getProgress());
            }
        });
    }

    /** 扫描录像目录，按时间轴分组。可能有 I/O，放后台线程。 */
    private void loadTimelines() {
        infoText.setText("正在扫描录像...");
        if (listSummaryText != null) {
            listSummaryText.setText("正在扫描…");
        }
        new Thread(() -> {
            List<RecordingTimeline.Source> sources = new ArrayList<>();
            try {
                File dir = StorageHelper.getVideoDir(getApplicationContext());
                File[] files = dir != null ? dir.listFiles() : null;
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                            continue;
                        }
                        // 只要环视那一路。三路录制时同一分段会写出 front/back/left 三个
                        // 文件，时间戳前缀一模一样 —— 全收进来就会被当成前后相接的三段，
                        // 把环视和座舱画面接到同一条时间轴上。
                        if (!RecordingTimeline.isSlot(f.getName(), COMPOSITE_SLOT)) {
                            continue;
                        }
                        long start = RecordingTimeline.parseStartEpochMs(f.getName());
                        if (start < 0) {
                            continue;
                        }
                        long duration = readDurationMs(f);
                        if (duration > 0) {
                            sources.add(new RecordingTimeline.Source(
                                    f.getAbsolutePath(), start, duration, f.length()));
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "扫描录像失败", e);
            }

            final List<RecordingTimeline.Session> built = RecordingTimeline.build(sources);
            runOnUiThread(() -> {
                sessions = built;
                sessionAdapter.setSessions(sessions);
                updateListSummary();
                if (sessions.isEmpty()) {
                    infoText.setText("没有找到环视录像");
                    Toast.makeText(this, "没有找到环视录像（连续回放只播环视这一路）",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // 默认打开最近的一条时间轴
                switchSession(sessions.size() - 1);
                handler.post(ticker);
            });
        }).start();
    }

    /** 读单个文件的时长；读不出来返回 -1，调用方会跳过该文件。 */
    private long readDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value != null ? Long.parseLong(value) : -1L;
        } catch (Exception e) {
            AppLog.w(TAG, "读取时长失败: " + file.getName());
            return -1L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // 释放失败无所谓
            }
        }
    }

    private void switchSession(int index) {
        if (sessions.isEmpty()) {
            return;
        }
        sessionIndex = Math.max(0, Math.min(index, sessions.size() - 1));
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        currentSegmentIndex = -1;
        preparedSegmentIndex = -1;
        preparingSegmentIndex = -1;
        consecutiveErrors = 0;
        playWhenReady = true;

        seekBar.setMax((int) Math.max(1L, session.totalDurationMs));
        seekBar.setProgress(0);
        updateSessionInfo(session);
        seekTimelineTo(0);

        prevSessionButton.setEnabled(sessionIndex > 0);
        nextSessionButton.setEnabled(sessionIndex < sessions.size() - 1);

        // 上一段/下一段按钮也会改变选中项，列表要跟着走
        if (sessionAdapter != null) {
            sessionAdapter.setSelectedIndex(sessionIndex);
        }
        if (sessionListView != null) {
            // 列表里还夹着日期标题行，所以要按行号滚动，不能直接用会话下标
            int row = sessionAdapter.rowOf(sessionIndex);
            if (row >= 0) {
                sessionListView.scrollToPosition(row);
            }
        }
    }

    /** 左栏顶部的一行汇总：共几条、合计多长多大。 */
    private void updateListSummary() {
        if (listSummaryText == null) {
            return;
        }
        if (sessions.isEmpty()) {
            listSummaryText.setText("没有找到可用的录像");
            return;
        }
        long totalMs = 0L;
        long totalBytes = 0L;
        for (RecordingTimeline.Session session : sessions) {
            totalMs += session.totalDurationMs;
            totalBytes += session.totalSizeBytes;
        }
        listSummaryText.setText(sessions.size() + " 条　·　"
                + TimelineFormat.duration(totalMs) + "　·　"
                + TimelineFormat.size(totalBytes));
    }

    private void updateSessionInfo(RecordingTimeline.Session session) {
        String started = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(session.startEpochMs));
        infoText.setText(String.format(Locale.getDefault(),
                "时间轴 %d/%d　起于 %s　共 %d 段　时长 %s",
                sessionIndex + 1, sessions.size(), started,
                session.segmentCount(), TimelineFormat.duration(session.totalDurationMs)));
    }

    /**
     * 跳到时间轴上的某个位置：换算成「哪个文件 + 文件内偏移」，必要时切文件。
     *
     * <p>只有在播放器<b>确实 prepare 完成</b>时才直接 seek。以前这里只比较段号，
     * 而段号在发起异步打开时就被改掉了 —— 于是在 prepare 完成之前再拖一次，
     * 就会对一个还没准备好的播放器 seek。解码器由此进入坏状态：先是几帧几帧地抽搐，
     * 然后卡住，再拖也不会恢复，最后出乱码。</p>
     */
    private void seekTimelineTo(long positionMs) {
        if (sessions.isEmpty()) {
            return;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        RecordingTimeline.Locator locator = session.locate(positionMs);
        if (locator == null) {
            return;
        }

        boolean readyForDirectSeek = locator.segmentIndex == preparedSegmentIndex
                && preparingSegmentIndex < 0;
        if (readyForDirectSeek) {
            videoView.seekTo((int) locator.offsetInSegmentMs);
            if (playWhenReady && !videoView.isPlaying()) {
                videoView.start();
            }
        } else {
            openSegment(locator.segmentIndex, locator.offsetInSegmentMs);
        }
        showPosition(positionMs);
    }

    /**
     * 打开某一段并在 prepare 完成后跳到指定偏移。
     *
     * <p>同一段已经在打开途中时，只更新目标偏移就返回 —— 连续拖动不该叠出多个
     * 打开操作。</p>
     */
    private void openSegment(int segmentIndex, long offsetMs) {
        if (sessions.isEmpty()) {
            return;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        if (segmentIndex < 0 || segmentIndex >= session.segmentCount()) {
            return;
        }

        pendingOffsetMs = Math.max(0L, offsetMs);
        if (segmentIndex == preparingSegmentIndex) {
            return;  // 已经在打开同一个文件，等它 prepare 完成即可
        }

        preparingSegmentIndex = segmentIndex;
        preparedSegmentIndex = -1;
        currentSegmentIndex = segmentIndex;

        // 切换期间先把画面藏起来，等新的一段渲染出第一帧再显示
        // （见 onCreate 里 setOnInfoListener 的说明）
        videoView.setVisibility(View.INVISIBLE);
        handler.removeCallbacks(showVideoFallback);
        handler.postDelayed(showVideoFallback, SHOW_VIDEO_TIMEOUT_MS);

        // 先显式停掉上一个再开下一个。setVideoPath 内部虽然也会释放，
        // 但显式停一次能保证旧解码器不会和新的抢同一个 surface。
        videoView.stopPlayback();
        videoView.setVideoPath(session.segments.get(segmentIndex).path);
    }

    /** 一段播完后接下一段；已经是最后一段就停在末尾。 */
    private boolean advanceToNextSegment() {
        if (sessions.isEmpty()) {
            return false;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        int next = currentSegmentIndex + 1;
        if (next >= session.segmentCount()) {
            preparedSegmentIndex = -1;
            updatePlayPauseLabel();
            return true;
        }
        // 直接打开下一段，不要绕回 seekTimelineTo —— 那会再做一次定位换算，
        // 边界上可能又落回当前段，造成原地打转
        openSegment(next, 0L);
        return true;
    }

    /** 当前时间轴位置 = 本段起始偏移 + 播放器内部进度。 */
    private long currentTimelinePosition() {
        // 未 prepare 时 getCurrentPosition 返回的是无意义的值，别拿它去更新进度条
        if (sessions.isEmpty() || preparedSegmentIndex < 0) {
            return 0L;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        if (preparedSegmentIndex >= session.segmentCount()) {
            return 0L;
        }
        return session.segments.get(preparedSegmentIndex).timelineOffsetMs
                + Math.max(0, videoView.getCurrentPosition());
    }

    private void updateProgress() {
        if (sessions.isEmpty() || preparedSegmentIndex < 0 || !videoView.isPlaying()) {
            return;
        }
        long position = currentTimelinePosition();
        seekBar.setProgress((int) position);
        showPosition(position);
    }

    private void showPosition(long positionMs) {
        if (sessions.isEmpty()) {
            return;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        positionText.setText(TimelineFormat.duration(positionMs) + " / "
                + TimelineFormat.duration(session.totalDurationMs));
    }

    private void togglePlayPause() {
        if (videoView.isPlaying()) {
            videoView.pause();
            playWhenReady = false;
        } else {
            playWhenReady = true;
            if (preparedSegmentIndex >= 0) {
                videoView.start();
            }
            // 还没 prepare 完成的话，onPrepared 会按 playWhenReady 自动开始
        }
        updatePlayPauseLabel();
    }

    private void updatePlayPauseLabel() {
        if (playPauseButton != null) {
            playPauseButton.setText(videoView.isPlaying() ? "暂停" : "播放");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 同样的道理：窗口不可见后 surface 会被销毁，只 pause 会让 MediaPlayer
        // 继续持有它并不停超时（见 PlaybackFragmentNew.onStop 的说明）。
        // 这里彻底释放，位置记下来，回前台时再开回去。
        if (!sessions.isEmpty() && preparedSegmentIndex >= 0) {
            positionToRestoreMs = currentTimelinePosition();
        }
        preparedSegmentIndex = -1;
        preparingSegmentIndex = -1;
        if (videoView != null) {
            videoView.stopPlayback();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (positionToRestoreMs >= 0 && !sessions.isEmpty()) {
            long restore = positionToRestoreMs;
            positionToRestoreMs = -1L;
            // 回来时停在原处，不自动续播 —— 用户离开时未必想让它继续跑
            playWhenReady = false;
            seekTimelineTo(restore);
            updatePlayPauseLabel();
        }
        if (!sessions.isEmpty()) {
            handler.post(ticker);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
