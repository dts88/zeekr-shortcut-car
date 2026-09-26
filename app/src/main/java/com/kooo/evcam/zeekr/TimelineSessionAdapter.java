package com.kooo.evcam.zeekr;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 连续回放左栏的列表：日期分组标题 + 每条连续时间轴。
 *
 * <p>与普通回看列表的区别在于「一项」的含义：那边一项是一个文件，这边一项是一整段
 * 连续录制（可能由几十个分段拼成）。所以每项给的是这段录制的起止时刻、总时长、
 * 总大小和段数 —— 用户挑的是「哪一次行程」，不是「哪一个文件」。</p>
 *
 * <p><b>倒序显示</b>：最新的在最上面。{@link RecordingTimeline#build} 仍然是正序的 ——
 * 时间轴偏移量依赖那个顺序，也有单元测试钉着。倒序只是显示层的事，所以在这里做：
 * 本类把「日期标题 + 会话」摊成一个行列表，并负责把行号换算回会话下标交给播放器。</p>
 */
public class TimelineSessionAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SESSION = 1;

    /** 点选一条时间轴。传出的是 sessions 列表里的下标，不是行号。 */
    public interface OnSessionClickListener {
        void onSessionClick(int sessionIndex);
    }

    /**
     * 长按一条时间轴。
     *
     * <p>单独一个接口，而不是给上面那个加方法：{@link OnSessionClickListener}
     * 现在是用方法引用传进来的，加一个方法就用不了了。</p>
     */
    public interface OnSessionLongClickListener {
        void onSessionLongClick(int sessionIndex);
    }

    /** 一行：要么是日期标题，要么是一条时间轴。 */
    private static final class Row {
        final int type;
        final String header;
        final RecordingTimeline.Session session;
        /** 在原始 sessions 列表里的下标；标题行为 -1。 */
        final int sessionIndex;

        Row(String header) {
            this.type = TYPE_HEADER;
            this.header = header;
            this.session = null;
            this.sessionIndex = -1;
        }

        Row(RecordingTimeline.Session session, int sessionIndex) {
            this.type = TYPE_SESSION;
            this.header = null;
            this.session = session;
            this.sessionIndex = sessionIndex;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnSessionClickListener listener;
    private OnSessionLongClickListener longClickListener;
    private int selectedSessionIndex = -1;

    /**
     * 多选模式：点一下是勾选，不是播放。
     *
     * <p>选中的是 sessions 列表里的下标，不是行号 —— 行号会随着日期标题的增减而变，
     * 而删除之后整张列表都会重建。</p>
     */
    private boolean selectionMode;
    private final Set<Integer> chosen = new LinkedHashSet<>();
    private Runnable onSelectionChanged;

    public TimelineSessionAdapter(OnSessionClickListener listener) {
        this.listener = listener;
    }

    /**
     * 每一条录制的大小（字节），按会话下标。
     *
     * <p>会话本身只认环视那一路；同一次录制里座舱的文件也在回放里一起放、一起删，
     * 所以大小由界面算好了给过来。没给的按会话自己的算。</p>
     */
    private long[] sessionBytes = new long[0];

    /**
     * @param sessions 正序（最早在前）的会话列表，与 {@link RecordingTimeline#build} 的输出一致
     * @param bytes    每一条的大小（字节），和 sessions 一一对应
     */
    public void setSessions(List<RecordingTimeline.Session> sessions, long[] bytes) {
        sessionBytes = bytes != null ? bytes : new long[0];
        setSessions(sessions);
    }

    /**
     * @param sessions 正序（最早在前）的会话列表，与 {@link RecordingTimeline#build} 的输出一致
     */
    private void setSessions(List<RecordingTimeline.Session> sessions) {
        rows.clear();
        if (sessions != null) {
            SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            // 「9月11日 星期四」/「Thursday, September 11」：按当前语言由系统给最合适的写法
            SimpleDateFormat dayLabel = new SimpleDateFormat(
                    android.text.format.DateFormat.getBestDateTimePattern(
                            Locale.getDefault(), "MMMMdEEEE"), Locale.getDefault());
            String currentDay = null;
            // 倒着遍历：最新的排在最上面
            for (int i = sessions.size() - 1; i >= 0; i--) {
                RecordingTimeline.Session session = sessions.get(i);
                Date start = new Date(session.startEpochMs);
                String day = dayKey.format(start);
                if (!day.equals(currentDay)) {
                    currentDay = day;
                    rows.add(new Row(dayLabel.format(start)));
                }
                rows.add(new Row(session, i));
            }
        }
        notifyDataSetChanged();
    }

    /** 高亮当前正在播放的那一条（传 sessions 列表里的下标）。 */
    public void setOnSessionLongClickListener(OnSessionLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setSelectedIndex(int sessionIndex) {
        int previousRow = rowOf(selectedSessionIndex);
        selectedSessionIndex = sessionIndex;
        int newRow = rowOf(sessionIndex);
        if (previousRow >= 0) {
            notifyItemChanged(previousRow);
        }
        if (newRow >= 0) {
            notifyItemChanged(newRow);
        }
    }

    /** 进出多选。进出都把已选清空：留着上一次的选择只会让人误删。 */
    public void setSelectionMode(boolean on) {
        if (selectionMode == on) {
            return;
        }
        selectionMode = on;
        chosen.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** 选中变化时通知一声，让工具条上的计数跟着走。 */
    public void setOnSelectionChangedListener(Runnable listener) {
        this.onSelectionChanged = listener;
    }

    public void chooseAll() {
        chosen.clear();
        for (Row row : rows) {
            if (row.type == TYPE_SESSION) {
                chosen.add(row.sessionIndex);
            }
        }
        notifyDataSetChanged();
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    public List<Integer> chosenIndexes() {
        return new ArrayList<>(chosen);
    }

    public int chosenCount() {
        return chosen.size();
    }

    private void toggle(int sessionIndex) {
        if (!chosen.remove(sessionIndex)) {
            chosen.add(sessionIndex);
        }
        int row = rowOf(sessionIndex);
        if (row >= 0) {
            notifyItemChanged(row);
        }
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    /** 会话下标 -> 行号；用于滚动定位。找不到返回 -1。 */
    public int rowOf(int sessionIndex) {
        if (sessionIndex < 0) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).sessionIndex == sessionIndex) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(
                    inflater.inflate(R.layout.item_timeline_date_header, parent, false));
        }
        return new SessionViewHolder(
                inflater.inflate(R.layout.item_timeline_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (row.type == TYPE_HEADER) {
            ((HeaderViewHolder) holder).bind(row.header);
            return;
        }
        SessionViewHolder sessionHolder = (SessionViewHolder) holder;
        long bytes = row.sessionIndex < sessionBytes.length
                ? sessionBytes[row.sessionIndex] : row.session.totalSizeBytes;
        sessionHolder.bind(row.session, bytes, row.sessionIndex == selectedSessionIndex,
                selectionMode, chosen.contains(row.sessionIndex));
        sessionHolder.itemView.setOnClickListener(v -> {
            int clicked = sessionHolder.getAdapterPosition();
            // 列表刚刷新时 getAdapterPosition 会是 NO_POSITION，别把 -1 传出去
            if (clicked == RecyclerView.NO_POSITION) {
                return;
            }
            int index = rows.get(clicked).sessionIndex;
            if (selectionMode) {
                toggle(index);
            } else if (listener != null) {
                listener.onSessionClick(index);
            }
        });
        sessionHolder.itemView.setOnLongClickListener(v -> {
            int clicked = sessionHolder.getAdapterPosition();
            if (longClickListener != null && clicked != RecyclerView.NO_POSITION) {
                longClickListener.onSessionLongClick(rows.get(clicked).sessionIndex);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleText;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.timeline_date_header);
        }

        void bind(String title) {
            titleText.setText(title);
        }
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        private final TextView timeText;
        private final TextView metaText;
        private final View selectedBar;
        private final View check;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            timeText = itemView.findViewById(R.id.session_time);
            metaText = itemView.findViewById(R.id.session_meta);
            selectedBar = itemView.findViewById(R.id.session_selected_bar);
            check = itemView.findViewById(R.id.session_check);
        }

        void bind(RecordingTimeline.Session session, long bytes, boolean selected,
                  boolean selecting, boolean chosen) {
            Date start = new Date(session.startEpochMs);
            Date end = new Date(session.startEpochMs + session.totalDurationMs);

            // 日期由分组标题给出，这里突出时间跨度
            SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
            timeText.setText(clock.format(start) + " – " + clock.format(end));

            metaText.setText(TimelineFormat.duration(session.totalDurationMs)
                    + " · " + TimelineFormat.size(bytes)
                    + " · " + itemView.getContext().getString(
                            R.string.player_clip_count, session.segmentCount()));

            // 正在播的那条留着红条；多选时右边多一个勾
            selectedBar.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            check.setVisibility(selecting && chosen ? View.VISIBLE : View.GONE);
            itemView.setAlpha(selecting && !chosen ? 0.55f : 1f);
        }
    }
}
