package com.kooo.evcam.settings;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;
import com.kooo.evcam.profile.LaneLayout;

import java.util.List;

/**
 * 把格子图（{@link LaneMapView}）放进设置列表的一行。
 *
 * <p>这一行本身不可点：点的是图里的格子，由图自己处理。</p>
 */
final class LanePickerPreference extends Preference implements PreferenceRows.OwnLayout {

    private final List<LaneLayout> lanes;
    private final int selected;
    private final LaneMapView.OnLaneTap onTap;

    LanePickerPreference(Context context, List<LaneLayout> lanes, int selected,
                         LaneMapView.OnLaneTap onTap) {
        super(context);
        this.lanes = lanes;
        this.selected = selected;
        this.onTap = onTap;
        setLayoutResource(R.layout.pref_lane_picker);
        setTitle(R.string.editor_pick_lane);
        setSelectable(false);
        setPersistent(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View map = holder.findViewById(R.id.lane_map);
        if (map instanceof LaneMapView) {
            ((LaneMapView) map).bind(lanes, selected, onTap);
        }
    }
}
