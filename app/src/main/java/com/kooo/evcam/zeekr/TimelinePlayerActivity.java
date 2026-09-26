package com.kooo.evcam.zeekr;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.kooo.evcam.playback.FisheyeVideoFrame;
import com.kooo.evcam.playback.PlaybackViewport;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.playback.ManagedVideoPlayer;
import com.kooo.evcam.profile.RecordSpecs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 连续时间轴回放（实验性）。
 *
 * <p>普通回放是一堆文件挨个找、挨个放；这里把连续录制的分段拼成一条时间轴，
 * 拖动进度条可以跨文件定位 —— 行车记录仪的实际用法是「我想看 20 分钟前那段」，
 * 用户不关心它落在第几个文件里。</p>
 *
 * <p>时间轴换算全部交给 {@link RecordingTimeline} 和 {@link LaneTrack}（纯逻辑、有单元测试）。
 * 这里只负责：读时长、按定位切文件、跨段自动续播、几路之间对齐。</p>
 *
 * <h3>几路一起放</h3>
 *
 * <p>画面区和图片回看是同一套：环视在左，座舱竖排在右，这一条录制里没有文件的那一路
 * 整格收起来。点一格让它占满整块；环视还顺带放大点到的那一路；再点回到网格。</p>
 *
 * <p>时间轴按环视排，<b>环视领着时间走</b>，座舱各路按真实时刻跟着它 —— 几路的分段
 * 各切各的，开录的时刻也差一两秒，按「第几段」对不上。环视被收起来（放大了某一路座舱）
 * 的时候，由放大的那一路领。</p>
 *
 * <p><b>收起来的那几路一律暂停</b>：TextureView 看不见的时候没人取它的画面，
 * 解码器往里推帧推不动，会被堵住。重新露出来时按当时的时刻再对一次。</p>
 *
 * <p>播放交给 {@link com.kooo.evcam.playback.ManagedVideoPlayer}：由它保证
 * 「没准备好不 seek」「旧回调丢弃」「打开串行化」，本类只管时间轴上该放哪一段。</p>
 *
 * <p><b>已知限制</b>：切文件仍要重新 prepare，段与段之间有短暂停顿。
 * 要做到无缝需要在当前段播放时预加载下一段，播放器已经具备这个条件
 * （可以先 prepare 不播），但还没接上。</p>
 */
public class TimelinePlayerActivity extends AppCompatActivity {

    private static final String TAG = "TimelinePlayer";
    /** 进度刷新、几路对齐的间隔。 */
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
    /** 跟着的那一路和领头的差出多少，才去拉一把。 */
    private static final long DRIFT_MS = 600L;
    /** 拉过一把之后等它站稳再看：精确跳转要从关键帧一路解过去，要一点时间。 */
    private static final long SETTLE_MS = 2000L;
    /** 往前多跳的量最多多少（见 {@link Lane#seekLeadMs}）。 */
    private static final long MAX_SEEK_LEAD_MS = 2000L;
    /**
     * 同一路相邻两段之间，空多少以内算「接着的」。
     *
     * <p>文件名里的时刻只精确到秒，相邻两段在边界上会叠一点或空一点。
     * 这一两秒不能当成「没有录像」—— 否则每换一段，座舱那一格就闪一下字。</p>
     */
    private static final long GAP_GRACE_MS = 2000L;
    /** 时间轴按哪一路排。 */
    private static final String COMPOSITE_SLOT = CameraSlots.SURROUND;

    private SeekBar seekBar;
    private TextView positionText;
    private TextView infoText;
    private Button playPauseButton;
    private Button speedButton;
    private Button viewModeButton;
    private Button prevSessionButton;
    private Button nextSessionButton;
    private RecyclerView sessionListView;
    private TextView listSummaryText;
    private TimelineSessionAdapter sessionAdapter;
    private View actionGroup;
    private View selectionGroup;
    private TextView selectedCountText;
    /** 界面重建（切黑白模式这类）之前看的是哪一条；-1 表示没有，开最新的那条。 */
    private int pendingSessionIndex = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<RecordingTimeline.Session> sessions = new ArrayList<>();
    private int sessionIndex = 0;
    /** 扫描出来的其余几路的文件，按槽位（归一之后的名字）分。 */
    private final Map<String, List<RecordingTimeline.Source>> laneSources = new HashMap<>();
    /** 每一条录制所有文件的大小（字节），和 sessions 一一对应。见 {@link #filesOf}。 */
    private long[] sessionBytes = new long[0];

    /** 四格，[0] 是环视，其余按右边那一列从上到下。 */
    private Lane[] lanes;
    private Lane surround;
    /** 环视那一格 TextureView 外面那一层：取景和鱼眼校正都交给它。 */
    private FisheyeVideoFrame surroundFrame;
    /** 座舱那一列。这一条录制里座舱都没有文件时整列让开，环视独占整块。 */
    private View cabinColumn;
    /** 现在是哪一路占满整块；null 表示摆成网格。 */
    private Lane expanded;
    /** 放大到环视的哪一格；{@link PlaybackViewport#NO_CELL} 表示整幅。 */
    private int zoomedCell = PlaybackViewport.NO_CELL;
    /** 最近一次按在哪 —— 点击回调不带坐标，而「点的是哪一路」全看这个。 */
    private float lastTouchX;
    private float lastTouchY;

    /**
     * 现在要看的真实时刻。领头的那一路就绪之后，由它的进度推着走；
     * 还没就绪（刚拖过、刚换段）时就是要去的那一刻。
     */
    private long targetEpochMs;
    /** 要不要在放（用户暂停过就不该被强制恢复）。 */
    private boolean playWhenReady = true;
    /** 领头那一路连续出错次数，用来阻止「出错→跳下一段→又出错」无限翻下去。 */
    private int consecutiveErrors = 0;
    /** 本次切换的起点时刻，用于统计切换耗时；0 表示没有正在进行的切换。 */
    private long switchStartedAtMs = 0L;
    /** onStop 时记下的真实时刻，回到前台后从这里恢复；-1 表示无需恢复。 */
    private long epochToRestore = -1L;
    /** 用户正在拖动进度条时不要被自动刷新打断。 */
    private boolean userSeeking = false;

    /** 倍速。每换一段都要重新下发 —— 换的是新的播放器状态，不会自己继承。 */
    private static final float[] SPEED_OPTIONS = {0.5f, 1.0f, 1.5f, 2.0f};
    private int speedIndex = 1;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * 一格：框、画面、遮罩、播放器，以及这一条录制里这一路的文件。
     */
    private final class Lane {
        final String slot;
        final View frame;
        final TextureView view;
        final TextView cover;
        final ManagedVideoPlayer player;
        LaneTrack track = LaneTrack.EMPTY;
        /** 打开的是 track 里第几个文件（打开途中也算）；-1 表示没开。 */
        int openIndex = -1;
        /** 打开时交给播放器的偏移。 */
        long openOffsetMs;
        /** 现在要去的偏移。打开途中又被拖过的话，和 openOffsetMs 就不一样了，就绪后补跳。 */
        long wantOffsetMs;
        /** openIndex 那个文件 prepare 完了，可以 seek、读进度。 */
        boolean ready;
        /** 放完了的那个文件。放完的播放器再 play() 会从头放，所以要记着。 */
        int completedIndex = -1;
        /** 放不出来的那个文件：别每半秒再开一次。 */
        int failedIndex = -1;
        /** 刚跳过，这之前不再校准。 */
        long settleUntilMs;
        /** 上一次校准是不是刚发生过 —— 是的话，这次还差的就是跳转本身花掉的时间。 */
        boolean justCorrected;
        /**
         * 边放边校准时往前多跳多少。精确跳转要从关键帧解过去，解完的时候领头的已经
         * 又往前走了一截；不补上这一截，每次校准都落后同样多，于是每两秒拉一次。
         * 按实际落后的量自己调。
         */
        long seekLeadMs;
        final Runnable uncoverFallback = () -> uncover(this);

        Lane(String slot, int frameId, int videoId, int coverId, int labelId) {
            this.slot = slot;
            frame = findViewById(frameId);
            view = findViewById(videoId);
            cover = findViewById(coverId);
            // 画面按比例居中，剩下的地方露出框的底色 —— 和图片回看一个样子
            view.setOpaque(false);
            player = new ManagedVideoPlayer(view);
            TextView label = findViewById(labelId);
            label.setText(new AppConfig(TimelinePlayerActivity.this).getCameraName(
                    TimelinePlayerActivity.this, CameraSlots.keyForSuffix(slot)));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timeline_player);
        if (savedInstanceState != null) {
            pendingSessionIndex = savedInstanceState.getInt(STATE_SESSION_INDEX, -1);
        }

        seekBar = findViewById(R.id.timeline_seek);
        positionText = findViewById(R.id.timeline_position);
        infoText = findViewById(R.id.timeline_info);
        playPauseButton = findViewById(R.id.timeline_play_pause);
        prevSessionButton = findViewById(R.id.timeline_prev_session);
        nextSessionButton = findViewById(R.id.timeline_next_session);
        sessionListView = findViewById(R.id.timeline_session_list);
        listSummaryText = findViewById(R.id.timeline_list_summary);
        actionGroup = findViewById(R.id.pb_actions);
        cabinColumn = findViewById(R.id.cabin_column);

        lanes = new Lane[]{
                new Lane(COMPOSITE_SLOT, R.id.frame_surround, R.id.video_surround,
                        R.id.cover_surround, R.id.label_surround),
                new Lane(CameraSlots.CABIN_FRONT, R.id.frame_cabin_front, R.id.video_cabin_front,
                        R.id.cover_cabin_front, R.id.label_cabin_front),
                new Lane(CameraSlots.CABIN_REAR, R.id.frame_cabin_rear, R.id.video_cabin_rear,
                        R.id.cover_cabin_rear, R.id.label_cabin_rear),
                new Lane(CameraSlots.KEY_FOURTH, R.id.frame_fourth, R.id.video_fourth,
                        R.id.cover_fourth, R.id.label_fourth),
        };
        surround = lanes[0];
        surroundFrame = findViewById(R.id.video_surround_frame);
        if (surroundFrame != null) {
            // 开发者选项「视频回看：GPU 逐像素鱼眼校正」开着时，解码器先画进外框的管线。
            // 必须在 TextureView 的画布好之前交给播放器 —— onCreate 里正是时候
            surround.player.setSurfaceRoute(surroundFrame.gpuRoute());
        }
        for (Lane lane : lanes) {
            wire(lane);
        }

        sessionAdapter = new TimelineSessionAdapter(this::switchSession);
        sessionAdapter.setOnSessionLongClickListener(this::showSessionActions);
        if (sessionListView != null) {
            sessionListView.setLayoutManager(new LinearLayoutManager(this));
            sessionListView.setAdapter(sessionAdapter);
        }

        selectionGroup = findViewById(R.id.pb_selection);
        // 视频是按分段发送的，没有整批分享
        View share = findViewById(R.id.pb_share);
        if (share != null) {
            share.setVisibility(View.GONE);
        }
        com.kooo.evcam.ui.StatusLine.overlay(findViewById(android.R.id.content));
        com.kooo.evcam.ui.StatusLine.fill(findViewById(android.R.id.content));
        selectedCountText = findViewById(R.id.pb_selected_count);
        sessionAdapter.setOnSelectionChangedListener(this::updateSelectedCount);

        View menu = findViewById(R.id.timeline_menu);
        if (menu != null) {
            menu.setOnClickListener(v -> openDrawerOnMain());
        }
        View home = findViewById(R.id.pb_home);
        if (home != null) {
            home.setOnClickListener(v -> finish());
        }
        View refresh = findViewById(R.id.pb_refresh);
        if (refresh != null) {
            refresh.setOnClickListener(v -> loadTimelines());
        }
        View multiSelect = findViewById(R.id.pb_multi_select);
        if (multiSelect != null) {
            multiSelect.setOnClickListener(v -> setSelecting(true));
        }
        View selectAll = findViewById(R.id.pb_select_all);
        if (selectAll != null) {
            selectAll.setOnClickListener(v -> sessionAdapter.chooseAll());
        }
        View deleteSelected = findViewById(R.id.pb_delete);
        if (deleteSelected != null) {
            deleteSelected.setOnClickListener(v -> confirmDeleteChosen());
        }
        View cancelSelect = findViewById(R.id.pb_cancel);
        if (cancelSelect != null) {
            cancelSelect.setOnClickListener(v -> setSelecting(false));
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
        viewModeButton = findViewById(R.id.timeline_view_mode);
        if (viewModeButton != null) {
            viewModeButton.setOnClickListener(v -> cycleViewMode());
        }

        speedButton = findViewById(R.id.timeline_speed);
        if (speedButton != null) {
            speedButton.setOnClickListener(v -> cycleSpeed());
        }

        setupSeekBar();
        loadTimelines();
    }

    /**
     * 一格的播放器回调、点击、布局变化。
     *
     * <p>点击和图片回看同一套：按下时记坐标，点击走 {@code setOnClickListener} ——
     * 按下就算数，不等双击判定。</p>
     */
    private void wire(Lane lane) {
        // 播放器自己保证「没准备好不 seek」「旧回调丢弃」「打开串行化」，
        // 这里只关心时间轴上该放哪一段、几路怎么对齐。
        lane.player.setListener(new ManagedVideoPlayer.SimpleListener() {
            @Override
            public void onPrepared(ManagedVideoPlayer p, int durationMs) {
                lanePrepared(lane);
            }

            @Override
            public void onFirstFrame(ManagedVideoPlayer p) {
                if (lane == leader() && switchStartedAtMs > 0) {
                    AppLog.i(TAG, "切换耗时 · 出现第一帧: "
                            + (SystemClock.elapsedRealtime() - switchStartedAtMs) + "ms");
                    switchStartedAtMs = 0L;
                }
                uncover(lane);
            }

            @Override
            public void onCompletion(ManagedVideoPlayer p) {
                laneCompleted(lane);
            }

            @Override
            public void onError(ManagedVideoPlayer p, int what, int extra) {
                laneFailed(lane);
            }
        });

        lane.frame.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
            }
            return false;   // 不拦，点击照常走 —— 按压反馈和无障碍都在那条路上
        });
        lane.frame.setOnClickListener(v -> tapped(lane));

        // 展开、收起都会改变这一格的大小，而取景矩阵是按当时的尺寸算的：布局一变就得重算
        lane.view.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, orr, ob) -> {
            if (l != ol || top != ot || r != orr || b != ob) {
                applyViewport(lane);
            }
        });
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
            Map<String, List<RecordingTimeline.Source>> others = new HashMap<>();
            try {
                File dir = StorageHelper.getVideoDir(getApplicationContext());
                File[] files = dir != null ? dir.listFiles() : null;
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                            continue;
                        }
                        String slot = RecordingTimeline.parseCameraSlot(f.getName());
                        long start = RecordingTimeline.parseStartEpochMs(f.getName());
                        if (slot == null || start < 0) {
                            continue;
                        }
                        long duration = ClipDurations.of(f);
                        if (duration <= 0) {
                            continue;
                        }
                        RecordingTimeline.Source source = new RecordingTimeline.Source(
                                f.getAbsolutePath(), start, duration, f.length());
                        // 时间轴只按环视那一路排。三路录制时同一分段会写出三个文件，
                        // 时间戳前缀一模一样 —— 全收进时间轴就会被当成前后相接的三段。
                        // 其余几路另外收着，播放时按真实时刻跟着环视走
                        if (RecordingTimeline.isSlot(f.getName(), COMPOSITE_SLOT)) {
                            sources.add(source);
                        } else {
                            String key = CameraSlots.canonical(slot.toLowerCase(Locale.US));
                            List<RecordingTimeline.Source> list = others.get(key);
                            if (list == null) {
                                list = new ArrayList<>();
                                others.put(key, list);
                            }
                            list.add(source);
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "扫描录像失败", e);
            }

            final List<RecordingTimeline.Session> built = RecordingTimeline.build(sources);
            runOnUiThread(() -> {
                sessions = built;
                laneSources.clear();
                laneSources.putAll(others);
                sessionBytes = new long[sessions.size()];
                for (int i = 0; i < sessions.size(); i++) {
                    sessionBytes[i] = bytesOf(filesOf(sessions.get(i)));
                }
                sessionAdapter.setSessions(sessions, sessionBytes);
                updateListSummary();
                if (sessions.isEmpty()) {
                    // 全删光了：画面区也清掉，别留着已经不存在的文件
                    for (Lane lane : lanes) {
                        resetLane(lane);
                        lane.track = LaneTrack.EMPTY;
                    }
                    expanded = null;
                    zoomedCell = PlaybackViewport.NO_CELL;
                    applyViewMode(0L);
                    positionText.setText("");
                    infoText.setText(R.string.msg_no_surround_clips);
                    Toast.makeText(this, R.string.msg_no_surround_clips_long,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // 默认打开最近的一条；界面刚重建过就回到原来那一条
                int target = pendingSessionIndex >= 0 && pendingSessionIndex < sessions.size()
                        ? pendingSessionIndex : sessions.size() - 1;
                pendingSessionIndex = -1;
                switchSession(target);
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            });
        }).start();
    }

    private void switchSession(int index) {
        if (sessions.isEmpty()) {
            return;
        }
        sessionIndex = Math.max(0, Math.min(index, sessions.size() - 1));
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        consecutiveErrors = 0;
        playWhenReady = true;

        surround.track = LaneTrack.of(session);
        for (Lane lane : lanes) {
            if (lane != surround) {
                List<RecordingTimeline.Source> sources = laneSources.get(lane.slot);
                lane.track = sources == null ? LaneTrack.EMPTY
                        : LaneTrack.of(sources).within(session);
            }
            resetLane(lane);
        }
        // 正占着整块的那一路，这一条录制里没有的话就收回网格；
        // 取景回到整幅 —— 留着上一条的放大格子，会把新画面按别人的格子切
        if (expanded != null && expanded.track.isEmpty()) {
            expanded = null;
        }
        zoomedCell = PlaybackViewport.NO_CELL;

        seekBar.setMax((int) Math.max(1L, session.totalDurationMs));
        seekBar.setProgress(0);
        updateSessionInfo(session);
        showPosition(0);
        applyViewMode(session.startEpochMs);

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

    /** 这一格回到什么都没开的样子。 */
    private void resetLane(Lane lane) {
        lane.player.stop();
        handler.removeCallbacks(lane.uncoverFallback);
        lane.openIndex = -1;
        lane.ready = false;
        lane.completedIndex = -1;
        lane.failedIndex = -1;
        lane.settleUntilMs = 0L;
        lane.justCorrected = false;
        lane.seekLeadMs = 0L;
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
        for (int i = 0; i < sessions.size(); i++) {
            totalMs += sessions.get(i).totalDurationMs;
            totalBytes += sessionBytes[i];
        }
        listSummaryText.setText(getString(R.string.info_clip_count, sessions.size(),
                TimelineFormat.duration(totalMs) + "　·　"
                        + TimelineFormat.size(totalBytes)));
    }

    // ================================================================ 看哪几路

    /**
     * 点了某一格之后 —— 和图片回看同一套。
     *
     * <ul>
     *   <li>网格里点一格 → 这一格占满整块。环视还顺带把<b>点到的那一路</b>放大。</li>
     *   <li>占满的环视上再点 → 放大那一路。</li>
     *   <li>已经放大了的话，点哪儿都是收回网格；黑边上也没有画面可点。</li>
     *   <li>占满的座舱上再点 → 一整幅画面，没有格子可分，直接收回网格。</li>
     * </ul>
     *
     * <p>环视录像本身就是一个 2×2 网格文件，所以放大只是换个取景 ——
     * 同一个解码器，不切文件、不新建播放器。</p>
     */
    private void tapped(Lane lane) {
        if (sessions.isEmpty() || lane.track.isEmpty()) {
            return;
        }
        if (expanded == null) {
            long epoch = clockEpoch();
            expanded = lane;
            zoomedCell = gridColumns(lane) >= 2 ? cellUnderTouch(lane) : PlaybackViewport.NO_CELL;
            applyViewMode(epoch);
            return;
        }
        if (gridColumns(lane) < 2) {
            collapse();
            return;
        }
        int cell = cellUnderTouch(lane);
        if (zoomedCell != PlaybackViewport.NO_CELL || cell == PlaybackViewport.NO_CELL) {
            collapse();
            return;
        }
        zoomedCell = cell;
        applyViewport(lane);
    }

    /** 手指落在这一格画面的哪一路上；落在留出的黑边上、或者画面还没出来，返回 NO_CELL。 */
    private int cellUnderTouch(Lane lane) {
        return PlaybackViewport.cellAtInPicture(lastTouchX, lastTouchY,
                lane.player.getVideoWidth(), lane.player.getVideoHeight(),
                lane.view.getWidth(), lane.view.getHeight());
    }

    /** 收回网格。 */
    private void collapse() {
        long epoch = clockEpoch();
        expanded = null;
        zoomedCell = PlaybackViewport.NO_CELL;
        applyViewMode(epoch);
    }

    /**
     * 底部那个按钮：网格 → 有录像的每一路 → 回到网格。
     *
     * <p>点画面已经能到任何一路了，这个按钮留着是因为它同时是<b>现在在看哪一路</b>
     * 的标签 —— 图片回看也是这么做的。</p>
     */
    private void cycleViewMode() {
        if (sessions.isEmpty()) {
            return;
        }
        List<Lane> order = new ArrayList<>();
        order.add(null);            // 网格
        for (Lane lane : lanes) {
            if (!lane.track.isEmpty()) {
                order.add(lane);
            }
        }
        long epoch = clockEpoch();
        int at = order.indexOf(expanded);
        expanded = order.get((Math.max(at, 0) + 1) % order.size());
        zoomedCell = PlaybackViewport.NO_CELL;
        applyViewMode(epoch);
    }

    /**
     * 现在该看什么：摆成网格，还是某一路占满整块。
     *
     * <p>让一路占满，做的只是<b>把别的几格收起来</b> —— 没有第二套布局、没有第二个播放器。</p>
     *
     * <p>收起来的那几路暂停（没人取画面，解码器会被堵住）；露出来的按 {@code epochMs}
     * 对齐。{@code epochMs} 要在改状态<b>之前</b>取：领头的那一路可能正要换人。</p>
     */
    private void applyViewMode(long epochMs) {
        boolean grid = expanded == null;
        boolean[] wasShown = new boolean[lanes.length];
        boolean anyCabin = false;
        for (int i = 0; i < lanes.length; i++) {
            Lane lane = lanes[i];
            wasShown[i] = isShown(lane);
            boolean show = !lane.track.isEmpty() && (grid || lane == expanded);
            lane.frame.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && lane != surround) {
                anyCabin = true;
            }
            if (!show) {
                lane.player.pause();
            }
        }
        if (cabinColumn != null) {
            cabinColumn.setVisibility(anyCabin ? View.VISIBLE : View.GONE);
        }
        targetEpochMs = epochMs;
        for (int i = 0; i < lanes.length; i++) {
            if (isShown(lanes[i])) {
                // 刚露出来的：暂停期间领头的走远了，一定要跳；一直在的：差得多才拉
                place(lanes[i], epochMs, !wasShown[i], true);
            }
        }
        for (Lane lane : lanes) {
            applyViewport(lane);
        }
        if (viewModeButton != null) {
            viewModeButton.setText(grid
                    ? getString(R.string.photo_mode_multi)
                    : getString(R.string.photo_mode_single, cameraName(expanded)));
        }
        updatePlayPauseLabel();
    }

    private boolean isShown(Lane lane) {
        return lane.frame.getVisibility() == View.VISIBLE;
    }

    /** 这一路相机叫什么，和主界面同一个来源。 */
    private String cameraName(Lane lane) {
        return new AppConfig(this).getCameraName(this, CameraSlots.keyForSuffix(lane.slot));
    }

    /**
     * 这一路的录像横竖各排了几路：环视合成流是 2×2，座舱那种普通相机是一整幅。
     */
    private int gridColumns(Lane lane) {
        try {
            return RecordSpecs.storedAsGrid(this, CameraSlots.keyForSuffix(lane.slot)) ? 2 : 1;
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + lane.slot + " 的排列，按不拆处理: " + e);
            return 1;
        }
    }

    /**
     * 把取景下发到这一格的 TextureView。
     *
     * <p>顺带把画面按比例摆正：环视录像是 2560×2560 的方形，
     * 而格子是宽的 —— 不做这一步就会被横向拉伸。</p>
     */
    private void applyViewport(Lane lane) {
        int cell = lane == expanded ? zoomedCell : PlaybackViewport.NO_CELL;
        if (lane == surround && surroundFrame != null) {
            // 环视：取景连同鱼眼校正交给外框。开关（pb_fisheye）是按钮自己拨的，
            // 外框自己听着，这里不用管
            surroundFrame.show(cell, lane.player.getVideoWidth(), lane.player.getVideoHeight(),
                    gridColumns(lane) >= 2);
            return;
        }
        float[] r = PlaybackViewport.transformRects(cell,
                lane.player.getVideoWidth(), lane.player.getVideoHeight(),
                lane.view.getWidth(), lane.view.getHeight());
        if (r == null) {
            // 视频尺寸还不知道（没准备好），等 onPrepared 再来一次
            return;
        }
        Matrix matrix = new Matrix();
        matrix.setRectToRect(new RectF(r[0], r[1], r[2], r[3]),
                new RectF(r[4], r[5], r[6], r[7]), Matrix.ScaleToFit.FILL);
        lane.view.setTransform(matrix);
        lane.view.invalidate();
    }

    // ================================================================ 几路对齐

    /** 领着时间走的那一路：环视看得见就是环视，否则是占满整块的那一路。 */
    private Lane leader() {
        return expanded == null || expanded == surround ? surround : expanded;
    }

    /** 现在播到的真实时刻：领头的就绪了按它的进度，否则就是要去的那一刻。 */
    private long clockEpoch() {
        Lane lead = leader();
        if (lead.ready && lead.openIndex >= 0 && lead.openIndex < lead.track.size()) {
            return lead.track.clip(lead.openIndex).startEpochMs
                    + Math.max(0, lead.player.getCurrentPosition());
        }
        return targetEpochMs;
    }

    /**
     * 进度条上的位置。
     *
     * <p>环视领头时直接按它的分段算，不绕真实时刻 —— 文件名里的起止只精确到秒，
     * 相邻两段在真实时刻上可能叠着一点，绕一圈可能落回上一段，进度条往回跳。</p>
     */
    private long timelinePosition() {
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        if (leader() == surround && surround.ready && surround.openIndex >= 0
                && surround.openIndex < session.segmentCount()) {
            return session.segments.get(surround.openIndex).timelineOffsetMs
                    + Math.max(0, surround.player.getCurrentPosition());
        }
        return session.positionAt(clockEpoch());
    }

    /**
     * 让一路去到某一刻。
     *
     * @param hard  一定要跳（人拖了进度条、刚露出来）；否则只在差得多的时候才拉一把
     * @param exact 领头的那一路也精确跳。人拖进度条时不要：只跳到关键帧快得多，
     *              跟着的几路会按它实际落到的位置对齐
     */
    private void place(Lane lane, long epochMs, boolean hard, boolean exact) {
        boolean leads = lane == leader();
        // 领头的停在空隙里整个画面就停了，所以它跳到空隙之后的第一段
        LaneTrack.Hit hit = leads ? lane.track.atOrAfter(epochMs) : lane.track.at(epochMs);
        if (hit == null && !leads) {
            // 段与段之间那一两秒不算没有录像：直接接下一段
            LaneTrack.Hit next = lane.track.atOrAfter(epochMs);
            if (next != null && next.clip.startEpochMs - epochMs <= GAP_GRACE_MS) {
                hit = next;
            }
        }
        if (hit == null) {
            // 这一刻这一路没有录像：盖住，别停在一个不相干的画面上
            if (lane.openIndex >= 0) {
                resetLane(lane);
            }
            cover(lane, R.string.player_lane_no_footage);
            return;
        }
        if (!hard && !leads && lane.openIndex >= 0 && hit.index < lane.openIndex) {
            // 已经接到了下一段，领头的还在上一段的时间范围里（两段在边界上叠着一点）：别往回倒
            matchPlayState(lane);
            return;
        }
        if (hit.index == lane.failedIndex) {
            if (!hard) {
                return;   // 这个文件放不出来，遮罩已经写着了；人拖过来的话再试一次
            }
            lane.failedIndex = -1;
            open(lane, hit.index, hit.offsetMs);
            return;
        }
        if (!hard && hit.index == lane.completedIndex) {
            // 这一段放完了，领头的还在它的时间范围里：紧接着有下一段就直接接上，
            // 否则停在最后一帧，等领头的走出去
            int next = hit.index + 1;
            if (next < lane.track.size() && lane.track.clip(next).startEpochMs
                    <= lane.track.clip(hit.index).endEpochMs() + GAP_GRACE_MS) {
                open(lane, next, Math.max(0L, epochMs - lane.track.clip(next).startEpochMs));
            }
            return;
        }
        if (hit.index != lane.openIndex) {
            open(lane, hit.index, hit.offsetMs);
            return;
        }
        if (!lane.ready) {
            lane.wantOffsetMs = hit.offsetMs;   // 正在打开这个文件；就绪后按新的位置补跳
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long lag = hit.offsetMs - lane.player.getCurrentPosition();
        if (hard || (now >= lane.settleUntilMs && Math.abs(lag) > DRIFT_MS)) {
            if (!hard && lane.justCorrected) {
                // 刚拉过一把还差这么多：差的是跳转本身花掉的时间，下次往前多跳这么多
                lane.seekLeadMs = Math.max(0L, Math.min(MAX_SEEK_LEAD_MS, lane.seekLeadMs + lag));
            }
            boolean running = playWhenReady && lane.player.isPlaying();
            long target = hit.offsetMs + (running && !hard ? lane.seekLeadMs : 0L);
            if (leads && !exact) {
                lane.player.seekTo(target);
            } else {
                lane.player.seekToExact(target);
            }
            lane.completedIndex = -1;
            lane.settleUntilMs = now + SETTLE_MS;
            lane.justCorrected = !hard;
        } else if (now >= lane.settleUntilMs) {
            lane.justCorrected = false;
        }
        matchPlayState(lane);
    }

    /** 打开这一路的第 index 个文件，就绪后跳到 offsetMs。 */
    private void open(Lane lane, int index, long offsetMs) {
        lane.openIndex = index;
        lane.openOffsetMs = offsetMs;
        lane.wantOffsetMs = offsetMs;
        lane.ready = false;
        lane.completedIndex = -1;
        lane.settleUntilMs = 0L;
        lane.justCorrected = false;
        boolean leads = lane == leader();
        if (leads) {
            switchStartedAtMs = SystemClock.elapsedRealtime();
        }
        // 换文件期间盖住画面，等新的一段渲染出第一帧再揭开
        cover(lane, 0);
        handler.removeCallbacks(lane.uncoverFallback);
        handler.postDelayed(lane.uncoverFallback, SHOW_VIDEO_TIMEOUT_MS);
        // 释放上一个、跳到偏移、要不要自动播，全部由播放器串行处理。
        // 跟着的几路精确跳，否则一落就落到几秒前的关键帧上
        lane.player.open(lane.track.clip(index).path, offsetMs,
                playWhenReady && isShown(lane), !leads);
    }

    private void lanePrepared(Lane lane) {
        lane.ready = true;
        // 换段等于换了一次播放器状态，倍速和取景都要重新下发，
        // 否则连续播放会在每个分段边界上悄悄变回 1.0x
        lane.player.setSpeed(SPEED_OPTIONS[speedIndex]);
        applyViewport(lane);
        if (lane == leader()) {
            consecutiveErrors = 0;
            if (switchStartedAtMs > 0) {
                AppLog.d(TAG, "切换耗时 · 准备完成: "
                        + (SystemClock.elapsedRealtime() - switchStartedAtMs) + "ms");
            }
            if (lane.wantOffsetMs != lane.openOffsetMs) {
                // 打开途中又被拖过：补上最后那一下
                lane.player.seekTo(lane.wantOffsetMs);
            }
            // 领头的就位了，跟着的按它对一次
            long epoch = clockEpoch();
            for (Lane other : lanes) {
                if (other != lane && isShown(other)) {
                    place(other, epoch, false, true);
                }
            }
        } else {
            place(lane, clockEpoch(), false, true);
        }
        matchPlayState(lane);
        updatePlayPauseLabel();
    }

    private void laneCompleted(Lane lane) {
        if (lane == leader()) {
            int next = lane.openIndex + 1;
            if (next >= lane.track.size()) {
                // 最后一段放完：停在末尾，跟着的几路也停
                lane.completedIndex = lane.openIndex;
                playWhenReady = false;
                for (Lane other : lanes) {
                    other.player.pause();
                }
                updatePlayPauseLabel();
                return;
            }
            // 直接打开下一段，不要绕回定位 —— 那会再做一次换算，
            // 边界上可能又落回当前段，造成原地打转
            targetEpochMs = lane.track.clip(next).startEpochMs;
            open(lane, next, 0L);
            return;
        }
        // 跟着的那一路这一段放完了。放完的播放器再 play() 会从头放，所以记下来；
        // 紧接着有下一段就接上，否则等领头的走出这一段的时间范围（见 place）
        lane.completedIndex = lane.openIndex;
        place(lane, clockEpoch(), false, true);
    }

    private void laneFailed(Lane lane) {
        lane.ready = false;
        if (lane == leader()) {
            uncover(lane);
            if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                AppLog.e(TAG, "连续 " + consecutiveErrors + " 次播放出错，停止自动续播");
                Toast.makeText(TimelinePlayerActivity.this,
                        R.string.msg_segment_unplayable, Toast.LENGTH_LONG).show();
                consecutiveErrors = 0;
                return;
            }
            laneCompleted(lane);
            return;
        }
        // 跟着的那一路：这个文件放不出来。盖住并记下，别每半秒再开一次
        lane.failedIndex = lane.openIndex;
        cover(lane, R.string.player_lane_unplayable);
    }

    /**
     * 这一路该放还是该停：跟着整体走；看不见的、已经放完的不放。
     *
     * <p>还在打开途中的也要告诉它 —— 播放器会在就绪时按这个决定要不要自己开始。</p>
     */
    private void matchPlayState(Lane lane) {
        boolean run = playWhenReady && isShown(lane)
                && lane.openIndex >= 0 && lane.openIndex != lane.completedIndex;
        if (run) {
            lane.player.play();
        } else {
            lane.player.pause();
        }
    }

    /** 每半秒：进度条跟着领头的走，跟着的几路差得多了拉一把。 */
    private void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        Lane lead = leader();
        if (!lead.ready || !lead.player.isPlaying()) {
            return;
        }
        targetEpochMs = clockEpoch();
        if (!userSeeking) {
            long position = timelinePosition();
            seekBar.setProgress((int) position);
            showPosition(position);
        }
        for (Lane lane : lanes) {
            if (lane != lead && isShown(lane)) {
                place(lane, targetEpochMs, false, true);
            }
        }
    }

    /** 盖住这一格；{@code text} 为 0 时只是黑一下（换分段），不写字。 */
    private void cover(Lane lane, int text) {
        lane.cover.setText(text == 0 ? "" : getString(text));
        lane.cover.setVisibility(View.VISIBLE);
    }

    private void uncover(Lane lane) {
        handler.removeCallbacks(lane.uncoverFallback);
        // 没有录像、放不出来，这两种遮罩要留着
        if (lane.openIndex >= 0 && lane.openIndex != lane.failedIndex) {
            lane.cover.setVisibility(View.GONE);
        }
    }

    // ================================================================ 播放控制

    /**
     * 把<b>当前正在看的那一路、正在播的那一段</b>发到手机上。
     *
     * <p>不是整条时间轴：一条时间轴是好几个分段文件接起来的，动辄几个 G，
     * 而人想要的通常就是刚看到的那一段。网格里发的是环视（主画面），
     * 放大了某一路座舱就发那一路。</p>
     */
    private void sendCurrentSegment() {
        Lane lane = expanded != null ? expanded : surround;
        if (sessions.isEmpty() || lane.openIndex < 0 || lane.openIndex >= lane.track.size()) {
            Toast.makeText(this, R.string.share_phone_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        // 各路分段时长可以不一样，取这一路自己的
        int minutes = RecordSpecs.forCameraKey(this, CameraSlots.keyForSuffix(lane.slot)).segmentMinutes;
        String note = getString(R.string.share_video_segment_note,
                getString(R.string.share_minutes, minutes));
        com.kooo.evcam.share.PhoneShare.show(this,
                new File(lane.track.clip(lane.openIndex).path), note);
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_OPTIONS.length;
        float speed = SPEED_OPTIONS[speedIndex];
        for (Lane lane : lanes) {
            lane.player.setSpeed(speed);
        }
        if (speedButton != null) {
            speedButton.setText(String.format(Locale.getDefault(), "%.1fx", speed));
        }
    }

    private void seekTimelineTo(long positionMs) {
        if (sessions.isEmpty()) {
            return;
        }
        long epoch = sessions.get(sessionIndex).epochAt(positionMs);
        seekEpoch(epoch, false);
        showPosition(positionMs);
    }

    /**
     * 跳到某一刻：看得见的每一路都去那里。
     *
     * <p>只有在播放器<b>确实 prepare 完成</b>时才直接 seek，否则等它就绪（见 {@link #place}）。
     * 对还没准备好的播放器 seek，解码器会进入坏状态：先是几帧几帧地抽搐，
     * 然后卡住，再拖也不会恢复，最后出乱码。</p>
     */
    private void seekEpoch(long epochMs, boolean exact) {
        targetEpochMs = epochMs;
        for (Lane lane : lanes) {
            if (isShown(lane)) {
                place(lane, epochMs, true, exact || lane != leader());
            }
        }
    }

    private void togglePlayPause() {
        Lane lead = leader();
        playWhenReady = !lead.player.isPlaying();
        if (playWhenReady && lead.openIndex >= 0 && lead.openIndex == lead.completedIndex
                && !sessions.isEmpty()) {
            // 整条放完了又点播放：从这一条的开头重新放
            seekTimelineTo(0);
            updatePlayPauseLabel();
            return;
        }
        for (Lane lane : lanes) {
            matchPlayState(lane);   // 还没就绪的话，播放器会在 prepare 完成后按这个办
        }
        updatePlayPauseLabel();
    }

    private void updatePlayPauseLabel() {
        if (playPauseButton != null) {
            playPauseButton.setText(leader().player.isPlaying()
                    ? R.string.action_pause : R.string.action_play);
        }
    }

    // ================================================================ 列表操作

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
        String title = getString(R.string.player_session_title,
                new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(session.startEpochMs)),
                session.segmentCount(),
                TimelineFormat.size(bytesOf(filesOf(session))));
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setItems(new CharSequence[]{getString(R.string.action_share_clip),
                        getString(R.string.action_delete_clip)}, (dialog, which) -> {
                    if (which == 0) {
                        shareSession(session);
                    } else {
                        confirmDeleteSession(index, session);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * 这一条录制的全部文件：环视的分段，加上同一次录制里其余几路的文件。
     *
     * <p>画面上几路是一起放的，删除和分享也按这个算。以前只删环视那一路，
     * 座舱的文件留在 U 盘上，列表里又再也找不到它们。</p>
     */
    private List<LaneTrack.Clip> filesOf(RecordingTimeline.Session session) {
        List<LaneTrack.Clip> files = new ArrayList<>(LaneTrack.of(session).clips());
        for (Lane lane : lanes) {
            List<RecordingTimeline.Source> sources = laneSources.get(lane.slot);
            if (lane != surround && sources != null) {
                files.addAll(LaneTrack.of(sources).within(session).clips());
            }
        }
        return files;
    }

    private static long bytesOf(List<LaneTrack.Clip> files) {
        long bytes = 0L;
        for (LaneTrack.Clip clip : files) {
            bytes += clip.sizeBytes;
        }
        return bytes;
    }

    private void shareSession(RecordingTimeline.Session session) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (LaneTrack.Clip clip : filesOf(session)) {
            File file = new File(clip.path);
            if (!file.exists()) {
                continue;
            }
            try {
                uris.add(FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file));
            } catch (Exception e) {
                AppLog.w(TAG, "无法分享 " + clip.path + ": " + e);
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
        startActivity(Intent.createChooser(intent, getString(R.string.player_share_chooser)));
    }

    /**
     * 菜单键回主界面并把抽屉拉开。
     *
     * <p>抽屉长在主界面上，这里是另一个界面 —— 所以不是「打开抽屉」，
     * 而是「回到有抽屉的那一屏，并让它开着」。看起来和主界面点菜单是一回事。</p>
     */
    private void openDrawerOnMain() {
        Intent intent = new Intent(this, com.kooo.evcam.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(com.kooo.evcam.MainActivity.EXTRA_OPEN_DRAWER, true);
        startActivity(intent);
        finish();
    }

    /** 进出多选：两条工具条互换，列表自己换成勾选的点法。 */
    private void setSelecting(boolean on) {
        sessionAdapter.setSelectionMode(on);
        // 标题区不动，换的是动作栏里的两组
        if (actionGroup != null) {
            actionGroup.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        if (selectionGroup != null) {
            selectionGroup.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        if (selectedCountText != null) {
            selectedCountText.setText(getString(R.string.msg_selected_n, sessionAdapter.chosenCount()));
        }
    }

    private void confirmDeleteChosen() {
        List<Integer> indexes = sessionAdapter.chosenIndexes();
        if (indexes.isEmpty()) {
            Toast.makeText(this, R.string.msg_selected_none, Toast.LENGTH_SHORT).show();
            return;
        }
        int files = 0;
        long bytes = 0;
        for (int index : indexes) {
            if (index >= 0 && index < sessions.size()) {
                List<LaneTrack.Clip> clips = filesOf(sessions.get(index));
                files += clips.size();
                bytes += bytesOf(clips);
            }
        }
        com.kooo.evcam.ui.CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.player_delete_title)
                .setMessage(getString(R.string.player_delete_msg, files, TimelineFormat.size(bytes)))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteChosen(indexes))
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void deleteChosen(List<Integer> indexes) {
        // 正在播的那一段可能也在里面，先停下，否则删的是一个还开着的文件
        for (Lane lane : lanes) {
            resetLane(lane);
        }
        int deleted = 0;
        int total = 0;
        for (int index : indexes) {
            if (index < 0 || index >= sessions.size()) {
                continue;
            }
            for (LaneTrack.Clip clip : filesOf(sessions.get(index))) {
                total++;
                File file = new File(clip.path);
                if (file.exists() && file.delete()) {
                    deleted++;
                }
            }
        }
        AppLog.i(TAG, "多选删除：" + deleted + "/" + total + " 个文件");
        Toast.makeText(this, getString(R.string.player_deleted, deleted), Toast.LENGTH_SHORT).show();
        setSelecting(false);
        loadTimelines();
    }

    private void confirmDeleteSession(int index, RecordingTimeline.Session session) {
        List<LaneTrack.Clip> files = filesOf(session);
        long bytes = bytesOf(files);
        com.kooo.evcam.ui.CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.player_delete_title)
                .setMessage(getString(R.string.player_delete_msg,
                        files.size(), TimelineFormat.size(bytes)))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteSession(index, session))
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void deleteSession(int index, RecordingTimeline.Session session) {
        // 正在播这一条就先停下，否则删的是一个还开着的文件
        if (index == sessionIndex) {
            for (Lane lane : lanes) {
                resetLane(lane);
            }
        }
        List<LaneTrack.Clip> files = filesOf(session);
        int deleted = 0;
        for (LaneTrack.Clip clip : files) {
            File file = new File(clip.path);
            if (file.exists() && file.delete()) {
                deleted++;
            }
        }
        AppLog.i(TAG, "删除时间轴 " + index + "：" + deleted + "/" + files.size() + " 个文件");
        Toast.makeText(this, getString(R.string.player_deleted, deleted), Toast.LENGTH_SHORT).show();
        loadTimelines();
    }

    private static final String STATE_SESSION_INDEX = "sessionIndex";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SESSION_INDEX, sessionIndex);
    }

    /**
     * 返回键：先退出当前这一层，最后才是离开这个界面。
     */
    @Override
    public void onBackPressed() {
        // 多选里按返回：先退出多选。直接离开会让人以为选择被「提交」了
        if (sessionAdapter != null && sessionAdapter.isSelectionMode()) {
            setSelecting(false);
            return;
        }
        if (expanded != null || zoomedCell != PlaybackViewport.NO_CELL) {
            // 和点画面一样，一下退回网格
            collapse();
            return;
        }
        super.onBackPressed();
    }

    private void updateSessionInfo(RecordingTimeline.Session session) {
        String started = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(session.startEpochMs));
        infoText.setText(getString(R.string.player_info,
                sessionIndex + 1, sessions.size(), started,
                session.segmentCount(), TimelineFormat.duration(session.totalDurationMs)));
    }

    private void showPosition(long positionMs) {
        if (sessions.isEmpty()) {
            return;
        }
        RecordingTimeline.Session session = sessions.get(sessionIndex);
        positionText.setText(TimelineFormat.duration(positionMs) + " / "
                + TimelineFormat.duration(session.totalDurationMs));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        for (Lane lane : lanes) {
            lane.player.pause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 同样的道理：窗口不可见后 surface 会被销毁，只 pause 会让 MediaPlayer
        // 继续持有它并不停超时。
        // 这里彻底释放，位置记下来，回前台时再开回去。
        if (!sessions.isEmpty()) {
            epochToRestore = clockEpoch();   // 领头的还没就绪时，就是它正要去的那一刻
        }
        for (Lane lane : lanes) {
            resetLane(lane);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (epochToRestore >= 0 && !sessions.isEmpty()) {
            long restore = epochToRestore;
            epochToRestore = -1L;
            // 回来时停在原处，不自动续播 —— 用户离开时未必想让它继续跑
            playWhenReady = false;
            seekEpoch(restore, true);
            updatePlayPauseLabel();
        }
        if (!sessions.isEmpty()) {
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (lanes != null) {
            for (Lane lane : lanes) {
                lane.player.release();
            }
        }
        super.onDestroy();
    }
}
