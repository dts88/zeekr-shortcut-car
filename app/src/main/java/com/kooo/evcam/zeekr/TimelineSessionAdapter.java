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
 * 连续回放左栏的列表：一项就是一条连续时间轴。
 *
 * <p>与普通回看列表的区别在于「一项」的含义：那边一项是一个文件，这边一项是一整段
 * 连续录制（可能由几十个分段拼成）。所以每项给的是这段录制的起止时刻、总时长、
 * 总大小和段数 —— 用户挑的是「哪一次行程」，不是「哪一个文件」。</p>
 */
public class TimelineSessionAdapter
        extends RecyclerView.Adapter<TimelineSessionAdapter.SessionViewHolder> {

    /** 点选一条时间轴。 */
    public interface OnSessionClickListener {
        void onSessionClick(int index);
    }

    private final List<RecordingTimeline.Session> sessions = new ArrayList<>();
    private final OnSessionClickListener listener;
    private int selectedIndex = -1;

    public TimelineSessionAdapter(OnSessionClickListener listener) {
        this.listener = listener;
    }

    public void setSessions(List<RecordingTimeline.Session> newSessions) {
        sessions.clear();
        if (newSessions != null) {
            sessions.addAll(newSessions);
        }
        notifyDataSetChanged();
    }

    /** 高亮当前正在播放的那一条。 */
    public void setSelectedIndex(int index) {
        int previous = selectedIndex;
        selectedIndex = index;
        if (previous >= 0 && previous < sessions.size()) {
            notifyItemChanged(previous);
        }
        if (index >= 0 && index < sessions.size()) {
            notifyItemChanged(index);
        }
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_timeline_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        holder.bind(sessions.get(position), position == selectedIndex);
        holder.itemView.setOnClickListener(v -> {
            int clicked = holder.getAdapterPosition();
            // 列表刚刷新时 getAdapterPosition 会是 NO_POSITION，别把 -1 传出去
            if (listener != null && clicked != RecyclerView.NO_POSITION) {
                listener.onSessionClick(clicked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        private final TextView dateText;
        private final TextView timeText;
        private final TextView metaText;
        private final View selectedBar;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            dateText = itemView.findViewById(R.id.session_date);
            timeText = itemView.findViewById(R.id.session_time);
            metaText = itemView.findViewById(R.id.session_meta);
            selectedBar = itemView.findViewById(R.id.session_selected_bar);
        }

        void bind(RecordingTimeline.Session session, boolean selected) {
            Date start = new Date(session.startEpochMs);
            Date end = new Date(session.startEpochMs + session.totalDurationMs);

            dateText.setText(new SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault())
                    .format(start));

            SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
            timeText.setText(clock.format(start) + " – " + clock.format(end));

            metaText.setText(TimelineFormat.duration(session.totalDurationMs)
                    + "　·　" + TimelineFormat.size(session.totalSizeBytes)
                    + "　·　" + session.segmentCount() + " 段");

            selectedBar.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

}
