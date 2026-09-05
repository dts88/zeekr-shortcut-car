package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.media.MediaMetadataRetriever;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.content.Intent;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.TextureView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import com.kooo.evcam.playback.PlaybackViewport;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.playback.ManagedVideoPlayer;

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
 * <p>播放交给 {@link com.kooo.evcam.playback.ManagedVideoPlayer}：由它保证
 * 「没准备好不 seek」「旧回调丢弃」「打开串行化」，本类只管时间轴上该放哪一段。</p>
 *
 * <p><b>已知限制</b>：切文件仍要重新 prepare，段与段之间有短暂停顿。
 * 要做到无缝需要在当前段播放时预加载下一段，播放器已经具备这个条件
 * （可以先 prepare 不播），但还没接上。</p>
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

    private ManagedVideoPlayer player;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView infoText;
    private Button playPauseButton;
    private Button speedButton;
    private TextureView videoSurface;
    private Button prevSessionButton;
    private Button nextSessionButton;
    private View videoCover;
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
    /** 本次切换的起点时刻，用于统计切换耗时；0 表示没有正在进行的切换。 */
    private long switchStartedAtMs = 0L;
    /** onStop 释放播放器时记下的时间轴位置，回到前台后从这里恢复；-1 表示无需恢复。 */
    private long positionToRestoreMs = -1L;
    /** 用户正在拖动进度条时不要被自动刷新打断。 */
    private boolean userSeeking = false;

    /** 当前放大的是哪一格；{@link PlaybackViewport#NO_CELL} 表示显示完整四宫格。 */
    private int zoomedCell = PlaybackViewport.NO_CELL;

    /** 倍速。每换一段都要重新下发 —— 换的是新的播放器状态，不会自己继承。 */
    private static final float[] SPEED_OPTIONS = {0.5f, 1.0f, 1.5f, 2.0f};
    private int speedIndex = 1;

    /** 判定「点一下」而不是「拖了一下」的阈值。 */
    private static final int TAP_SLOP_PX = 24;
    private static final long TAP_MAX_MS = 400L;

    /** 超时兜底：没等到「已渲染」也把画面放出来。 */
    private final Runnable showVideoFallback = new Runnable() {
        @Override
        public void run() {
            uncoverVideo();
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

        videoSurface = findViewById(R.id.timeline_video);
        player = new ManagedVideoPlayer(videoSurface);
        seekBar = findViewById(R.id.timeline_seek);
        positionText = findViewById(R.id.timeline_position);
        infoText = findViewById(R.id.timeline_info);
        playPauseButton = findViewById(R.id.timeline_play_pause);
        prevSessionButton = findViewById(R.id.timeline_prev_session);
        nextSessionButton = findViewById(R.id.timeline_next_session);
        videoCover = findViewById(R.id.timeline_video_cover);
        sessionListView = findViewById(R.id.timeline_session_list);
        listSummaryText = findViewById(R.id.timeline_list_summary);

        sessionAdapter = new TimelineSessionAdapter(this::switchSession);
        sessionAdapter.setOnSessionLongClickListener(this::showSessionActions);
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
        View sendButton = findViewById(R.id.timeline_send);
        if (sendButton != null) {
            sendButton.setOnClickListener(v -> sendCurrentSegment());
        }

        speedButton = findViewById(R.id.timeline_speed);
        if (speedButton != null) {
            speedButton.setOnClickListener(v -> cycleSpeed());
        }
        setupZoomTaps();

        setupSeekBar();

        // 播放器自己保证「没准备好不 seek」「旧回调丢弃」「打开串行化」，
        // 这里只关心时间轴上该放哪一段。
        player.setListener(new ManagedVideoPlayer.SimpleListener() {
            @Override
            public void onPrepared(ManagedVideoPlayer p, int durationMs) {
                preparedSegmentIndex = preparingSegmentIndex;
                preparingSegmentIndex = -1;
                consecutiveErrors = 0;
                // 换段等于换了一次播放器状态，倍速和取景都要重新下发，
                // 否则连续播放会在每个分段边界上悄悄变回 1.0x
                p.setSpeed(SPEED_OPTIONS[speedIndex]);
                applyViewport();
                if (switchStartedAtMs > 0) {
                    AppLog.d(TAG, "切换耗时 · 准备完成: "
                            + (android.os.SystemClock.elapsedRealtime() - switchStartedAtMs) + "ms");
                }
                updatePlayPauseLabel();
            }

            @Override
            public void onFirstFrame(ManagedVideoPlayer p) {
                if (switchStartedAtMs > 0) {
                    AppLog.i(TAG, "切换耗时 · 出现第一帧: "
                            + (android.os.SystemClock.elapsedRealtime() - switchStartedAtMs) + "ms");
                    switchStartedAtMs = 0L;
                }
                uncoverVideo();
            }

            @Override
            public void onCompletion(ManagedVideoPlayer p) {
                advanceToNextSegment();
            }

            @Override
            public void onError(ManagedVideoPlayer p, int what, int extra) {
                preparedSegmentIndex = -1;
                preparingSegmentIndex = -1;
                uncoverVideo();
                if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    AppLog.e(TAG, "连续 " + consecutiveErrors + " 次播放出错，停止自动续播");
                    Toast.makeText(TimelinePlayerActivity.this,
                            R.string.msg_segment_unplayable, Toast.LENGTH_LONG).show();
                    consecutiveErrors = 0;
                    return;
                }
                advanceToNextSegment();
            }
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
        infoText.setText(R.string.msg_scanning);
        if (listSummaryText != null) {
            listSummaryText.setText(R.string.msg_scanning_short);
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
                    infoText.setText(R.string.msg_no_surround_clips);
                    Toast.makeText(this, R.string.msg_no_surround_clips_long,
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
            listSummaryText.setText(R.string.msg_no_clips);
            return;
        }
        long totalMs = 0L;
        long totalBytes = 0L;
        for (RecordingTimeline.Session session : sessions) {
            totalMs += session.totalDurationMs;
            totalBytes += session.totalSizeBytes;
        }
        listSummaryText.setText(getString(R.string.info_clip_count, sessions.size(),
                TimelineFormat.duration(totalMs) + "　·　"
                        + TimelineFormat.size(totalBytes)));
    }

    /**
     * 点画面放大其中一路，再点回到四宫格。
     *
     * <p>环视录像本身就是一个 2×2 网格文件，所以放大只是换个取景 ——
     * 同一个解码器，不切文件、不新建播放器。旧的回看界面为此开到 5 个播放器，
     * 绿屏和马赛克就出在那些播放器的来回创建上。</p>
     */
    private void setupZoomTaps() {
        if (videoSurface == null) {
            return;
        }
        videoSurface.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private long downAtMs;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downAtMs = android.os.SystemClock.elapsedRealtime();
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean moved = Math.abs(event.getX() - downX) > TAP_SLOP_PX
                                || Math.abs(event.getY() - downY) > TAP_SLOP_PX;
                        boolean quick = android.os.SystemClock.elapsedRealtime() - downAtMs
                                < TAP_MAX_MS;
                        if (!moved && quick) {
                            v.performClick();
                            toggleZoom(downX, downY);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    /** 已经放大了就还原，否则放大点到的那一格。 */
    private void toggleZoom(float x, float y) {
        if (zoomedCell != PlaybackViewport.NO_CELL) {
            zoomedCell = PlaybackViewport.NO_CELL;
        } else {
            zoomedCell = PlaybackViewport.cellAt(x, y,
                    videoSurface.getWidth(), videoSurface.getHeight());
        }
        applyViewport();
        Toast.makeText(this, PlaybackViewport.labelRes(zoomedCell), Toast.LENGTH_SHORT).show();
    }

    /**
     * 把取景下发到 TextureView。
     *
     * <p>顺带把画面按比例摆正：环视录像是 2560×2560 的方形，
     * 而这块视图是宽的 —— 不做这一步就会被横向拉伸。</p>
     */
    private void applyViewport() {
        if (videoSurface == null || player == null) {
            return;
        }
        float[] r = PlaybackViewport.transformRects(zoomedCell,
                player.getVideoWidth(), player.getVideoHeight(),
                videoSurface.getWidth(), videoSurface.getHeight());
        if (r == null) {
            // 视频尺寸还不知道（没准备好），等 onPrepared 再来一次
            return;
        }
        Matrix matrix = new Matrix();
        matrix.setRectToRect(new RectF(r[0], r[1], r[2], r[3]),
                new RectF(r[4], r[5], r[6], r[7]), Matrix.ScaleToFit.FILL);
        videoSurface.setTransform(matrix);
        videoSurface.invalidate();
    }

    /**
     * 把<b>当前正在播的那一段</b>发到手机上。
     *
     * <p>不是整条时间轴：一条时间轴是好几个分段文件接起来的，动辄几个 G，
     * 而人想要的通常就是刚看到的那一段。所以按当前播放位置落在哪一段来取。</p>
     */
    private void sendCurrentSegment() {
        if (sessionIndex < 0 || sessionIndex >= sessions.size()) {
            Toast.makeText(this, R.string.share_phone_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        if (currentSegmentIndex < 0 || currentSegmentIndex >= session.segmentCount()) {
            Toast.makeText(this, R.string.share_phone_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        RecordingTimeline.Segment segment = session.segments.get(currentSegmentIndex);
        // 连续回放只播环视这一路，分段时长就取它的
        int minutes = com.kooo.evcam.profile.RecordSpecs.forRole(this,
                com.kooo.evcam.profile.CameraProfile.ROLE_COMPOSITE).segmentMinutes;
        String note = getString(R.string.share_video_segment_note,
                getString(R.string.share_minutes, minutes));
        com.kooo.evcam.share.PhoneShare.show(this, new java.io.File(segment.path), note);
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_OPTIONS.length;
        float speed = SPEED_OPTIONS[speedIndex];
        player.setSpeed(speed);
        if (speedButton != null) {
            speedButton.setText(String.format(Locale.getDefault(), "%.1fx", speed));
        }
    }

    /**
     * 长按一条时间轴：删除或分享。
     *
     * <p>一条时间轴是一次连续录制，可能有很多个分段文件，
     * 所以删除和分享都是对整组文件操作 —— 只删其中一段会在时间轴上留个洞。</p>
     */
    private void showSessionActions(int index) {
        if (index < 0 || index >= sessions.size()) {
            return;
        }
        RecordingTimeline.Session session = sessions.get(index);
        String title = String.format(Locale.getDefault(), "%s　%d 段　%s",
                new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(session.startEpochMs)),
                session.segmentCount(),
                TimelineFormat.size(session.totalSizeBytes));
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle(title)
                .setItems(new CharSequence[]{getString(R.string.action_share_clip),
                        getString(R.string.action_delete_clip)}, (dialog, which) -> {
                    if (which == 0) {
                        shareSession(session);
                    } else {
                        confirmDeleteSession(index, session);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void shareSession(RecordingTimeline.Session session) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (RecordingTimeline.Segment segment : session.segments) {
            File file = new File(segment.path);
            if (!file.exists()) {
                continue;
            }
            try {
                uris.add(FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file));
            } catch (Exception e) {
                AppLog.w(TAG, "无法分享 " + segment.path + ": " + e);
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.msg_nothing_to_share, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(uris.size() > 1
                ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        intent.setType("video/*");
        if (uris.size() > 1) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享录像"));
    }

    private void confirmDeleteSession(int index, RecordingTimeline.Session session) {
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("确认删除")
                .setMessage(String.format(Locale.getDefault(),
                        "将删除这段录制的全部 %d 个文件，共 %s。删除后无法恢复。",
                        session.segmentCount(), TimelineFormat.size(session.totalSizeBytes)))
                .setPositiveButton("删除", (dialog, which) -> deleteSession(index, session))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deleteSession(int index, RecordingTimeline.Session session) {
        // 正在播这一段就先停下，否则删的是一个还开着的文件
        if (index == sessionIndex) {
            player.stop();
        }
        int deleted = 0;
        for (RecordingTimeline.Segment segment : session.segments) {
            File file = new File(segment.path);
            if (file.exists() && file.delete()) {
                deleted++;
            }
        }
        AppLog.i(TAG, "删除时间轴 " + index + "：" + deleted + "/" + session.segmentCount() + " 个文件");
        Toast.makeText(this, "已删除 " + deleted + " 个文件", Toast.LENGTH_SHORT).show();
        loadTimelines();
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
            player.seekTo(locator.offsetInSegmentMs);
            if (playWhenReady) {
                player.play();
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

        switchStartedAtMs = android.os.SystemClock.elapsedRealtime();

        // 切换期间盖住画面，等新的一段渲染出第一帧再揭开
        coverVideo();
        handler.removeCallbacks(showVideoFallback);
        handler.postDelayed(showVideoFallback, SHOW_VIDEO_TIMEOUT_MS);

        // 释放上一个、跳到偏移、要不要自动播，全部由播放器串行处理
        player.open(session.segments.get(segmentIndex).path, pendingOffsetMs, playWhenReady);
    }

    /** 盖住画面。遮罩画在窗口里，不动 SurfaceView 的 surface。 */
    private void coverVideo() {
        if (videoCover != null) {
            videoCover.setVisibility(View.VISIBLE);
        }
    }

    /** 揭开遮罩。 */
    private void uncoverVideo() {
        if (videoCover != null) {
            videoCover.setVisibility(View.GONE);
        }
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
                + Math.max(0, player.getCurrentPosition());
    }

    private void updateProgress() {
        if (sessions.isEmpty() || preparedSegmentIndex < 0 || !player.isPlaying()) {
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
        if (player.isPlaying()) {
            player.pause();
            playWhenReady = false;
        } else {
            playWhenReady = true;
            player.play();   // 还没就绪的话，播放器会在 prepare 完成后自动开始
        }
        updatePlayPauseLabel();
    }

    private void updatePlayPauseLabel() {
        if (playPauseButton != null) {
            playPauseButton.setText(player.isPlaying()
                    ? R.string.action_pause : R.string.action_play);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 同样的道理：窗口不可见后 surface 会被销毁，只 pause 会让 MediaPlayer
        // 继续持有它并不停超时。
        // 这里彻底释放，位置记下来，回前台时再开回去。
        if (!sessions.isEmpty() && preparedSegmentIndex >= 0) {
            positionToRestoreMs = currentTimelinePosition();
        }
        preparedSegmentIndex = -1;
        preparingSegmentIndex = -1;
        if (player != null) {
            player.pause();
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
        if (player != null) {
            player.release();
        }
        super.onDestroy();
    }
}
