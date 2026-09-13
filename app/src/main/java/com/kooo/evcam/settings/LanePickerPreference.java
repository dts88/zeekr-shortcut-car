package com.kooo.evcam.settings;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;
import com.kooo.evcam.profile.LaneLayout;

import java.util.List;

/**
 * 把摆位舞台（{@link LaneMapView}）放进设置列表的一行。
 *
 * <p>这一行本身不可点：点的、拖的都是图里的格子，由图自己处理。</p>
 */
final class LanePickerPreference extends Preference implements PreferenceRows.OwnLayout {

    private final List<LaneLayout> lanes;
    private final int selected;
    private final LaneMapView.OnLaneTap onTap;
    @Nullable
    private final LaneMapView.OnLaneMoved onMoved;

    /**
     * @param onMoved 拖完了告诉谁；传 null 就只能点不能拖
     */
    LanePickerPreference(Context context, List<LaneLayout> lanes, int selected,
                         LaneMapView.OnLaneTap onTap,
                         @Nullable LaneMapView.OnLaneMoved onMoved) {
        super(context);
        this.lanes = lanes;
        this.selected = selected;
        this.onTap = onTap;
        this.onMoved = onMoved;
        setLayoutResource(R.layout.pref_lane_picker);
        setTitle(onMoved != null ? R.string.editor_stage : R.string.editor_pick_lane);
        setSelectable(false);
        setPersistent(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View map = holder.findViewById(R.id.lane_map);
        if (map instanceof LaneMapView) {
            ((LaneMapView) map).bind(lanes, selected, onTap);
            ((LaneMapView) map).setEditable(onMoved != null, onMoved);
        }
    }
}
