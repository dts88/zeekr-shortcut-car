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
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    private int selectedSessionIndex = -1;

    public TimelineSessionAdapter(OnSessionClickListener listener) {
        this.listener = listener;
    }

    /**
     * @param sessions 正序（最早在前）的会话列表，与 {@link RecordingTimeline#build} 的输出一致
     */
    public void setSessions(List<RecordingTimeline.Session> sessions) {
        rows.clear();
        if (sessions != null) {
            SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat dayLabel = new SimpleDateFormat("M月d日 EEEE", Locale.getDefault());
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
        sessionHolder.bind(row.session, row.sessionIndex == selectedSessionIndex);
        sessionHolder.itemView.setOnClickListener(v -> {
            int clicked = sessionHolder.getAdapterPosition();
            // 列表刚刷新时 getAdapterPosition 会是 NO_POSITION，别把 -1 传出去
            if (listener != null && clicked != RecyclerView.NO_POSITION) {
                listener.onSessionClick(rows.get(clicked).sessionIndex);
            }
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

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            timeText = itemView.findViewById(R.id.session_time);
            metaText = itemView.findViewById(R.id.session_meta);
            selectedBar = itemView.findViewById(R.id.session_selected_bar);
        }

        void bind(RecordingTimeline.Session session, boolean selected) {
            Date start = new Date(session.startEpochMs);
            Date end = new Date(session.startEpochMs + session.totalDurationMs);

            // 日期由分组标题给出，这里突出时间跨度
            SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
            timeText.setText(clock.format(start) + " – " + clock.format(end));

            metaText.setText(TimelineFormat.duration(session.totalDurationMs)
                    + "　·　" + TimelineFormat.size(session.totalSizeBytes)
                    + "　·　" + session.segmentCount() + " 段");

            selectedBar.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }
}
