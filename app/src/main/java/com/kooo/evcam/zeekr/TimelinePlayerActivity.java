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
    /** 用户正在拖动进度条时不要被自动刷新打断。 */
    private boolean userSeeking = false;

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

        // 播完一段自动接下一段
        videoView.setOnCompletionListener(mp -> advanceToNextSegment());
        videoView.setOnErrorListener((mp, what, extra) -> {
            AppLog.w(TAG, "播放出错 what=" + what + " extra=" + extra);
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
                    infoText.setText("没有找到可用的录像");
                    Toast.makeText(this, "没有找到可用的录像", Toast.LENGTH_LONG).show();
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
            sessionListView.scrollToPosition(sessionIndex);
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

        if (locator.segmentIndex != currentSegmentIndex) {
            currentSegmentIndex = locator.segmentIndex;
            videoView.setVideoPath(locator.segment.path);
            final long offset = locator.offsetInSegmentMs;
            videoView.setOnPreparedListener(mp -> {
                mp.setOnSeekCompleteListener(m -> videoView.start());
                videoView.seekTo((int) offset);
                updatePlayPauseLabel();
            });
        } else {
            videoView.seekTo((int) locator.offsetInSegmentMs);
            if (!videoView.isPlaying()) {
                videoView.start();
            }
        }
        showPosition(positionMs);
    }

    /** 一段播完后接下一段；已经是最后一段就停在末尾。 */
    private boolean advanceToNextSegment() {
        if (sessions.isEmpty()) {
            return false;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        int next = currentSegmentIndex + 1;
        if (next >= session.segmentCount()) {
            updatePlayPauseLabel();
            return true;
        }
        seekTimelineTo(session.segments.get(next).timelineOffsetMs);
        return true;
    }

    /** 当前时间轴位置 = 本段起始偏移 + 播放器内部进度。 */
    private long currentTimelinePosition() {
        if (sessions.isEmpty() || currentSegmentIndex < 0) {
            return 0L;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        if (currentSegmentIndex >= session.segmentCount()) {
            return 0L;
        }
        return session.segments.get(currentSegmentIndex).timelineOffsetMs
                + Math.max(0, videoView.getCurrentPosition());
    }

    private void updateProgress() {
        if (sessions.isEmpty() || !videoView.isPlaying()) {
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
        } else {
            videoView.start();
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
    protected void onResume() {
        super.onResume();
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
