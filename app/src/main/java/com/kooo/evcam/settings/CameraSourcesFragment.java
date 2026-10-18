package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.profile.*;
import com.kooo.evcam.ui.CamDialogs;
import java.util.*;

/** One ordinary source page and picker shared by Settings, Developer and the stream editor. */
public class CameraSourcesFragment extends PreferenceFragmentCompat {
    @Override public void onCreatePreferences(@Nullable Bundle state, @Nullable String rootKey) {
        Context context = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        Preference help = new Preference(context); help.setTitle(R.string.camera_sources_title);
        help.setSummary(R.string.camera_sources_help); help.setSelectable(false); screen.addPreference(help);
        Profile profile = new ProfileStore(context).current();
        AppConfig config = new AppConfig(context);
        for (String role : new String[]{CameraProfile.ROLE_COMPOSITE, CameraProfile.ROLE_ROAD_FRONT,
                CameraProfile.ROLE_ROAD_REAR, CameraProfile.ROLE_CABIN_1, CameraProfile.ROLE_CABIN_2, CameraProfile.ROLE_MANUAL}) {
            Preference row = new Preference(context); row.setKey(role); row.setTitle(roleLabel(context, role));
            row.setOnPreferenceClickListener(pref -> {
                pick(context, role, new ProfileStore(context).current(), this::refresh); return true;
            }); screen.addPreference(row);
        }
        Preference reset = new Preference(context); reset.setKey("reset_sources");
        reset.setTitle(R.string.camera_sources_reset); reset.setSummary(R.string.camera_sources_reset_note);
        reset.setOnPreferenceClickListener(pref -> {
            CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.camera_sources_reset).setMessage(R.string.camera_sources_reset_note)
                    .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                        new AppConfig(context).resetSourceCameraIds(); refresh(); saved(context);
                    }).setNegativeButton(R.string.action_cancel, null)); return true;
        }); screen.addPreference(reset);
        PreferenceRows.apply(screen); refresh();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state); PreferenceRows.styleList(this, 28, 16);
    }
    private void refresh() {
        if (getContext() == null) return;
        AppConfig config = new AppConfig(requireContext());
        for (String role : RecordingSources.ALL_ROLES) {
            Preference row = findPreference(role); if (row == null) continue;
            String choice = config.getSourceCameraId(role);
            String id = ProfileSizes.cameraIdFor(requireContext(), role);
            String resolved = id == null ? getString(R.string.camera_source_not_found) : getString(R.string.opt_camera_id, id);
            row.setSummary(choice == null ? getString(R.string.camera_source_automatic, resolved)
                    : choice.isEmpty() ? getString(R.string.record_source_unassigned)
                    : getString(R.string.camera_source_manual, resolved));
        }
    }
    static String roleLabel(Context context, String role) {
        int resource = CameraProfile.ROLE_COMPOSITE.equals(role) ? R.string.record_group_surround
                : CameraProfile.ROLE_CABIN_1.equals(role) ? R.string.record_preview_cabin
                : CameraProfile.ROLE_CABIN_2.equals(role) ? R.string.slot_cabin_rear
                : CameraProfile.ROLE_ROAD_FRONT.equals(role) ? R.string.slot_road_front
                : CameraProfile.ROLE_ROAD_REAR.equals(role) ? R.string.slot_road_rear : R.string.record_group_manual;
        return context.getString(resource);
    }
    static void saved(Context context) { Toast.makeText(context, R.string.msg_mapping_saved, Toast.LENGTH_LONG).show(); }

    static void pick(Context context, String role, Profile profile, Runnable changed) {
        AppConfig config = new AppConfig(context);
        Set<String> choices = new LinkedHashSet<>();
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) Collections.addAll(choices, manager.getCameraIdList());
        } catch (Exception ignored) { /* Retain configured/discovered IDs while a camera is held. */ }
        choices.addAll(context.getSharedPreferences("camera_discovery", 0).getStringSet("captured_ids", Collections.emptySet()));
        String detected = config.getDetectedSurroundId(); if (detected != null) choices.add(detected);
        for (String candidate : RecordingSources.ALL_ROLES) {
            String explicit = config.getSourceCameraId(candidate);
            if (explicit != null && !explicit.isEmpty()) choices.add(explicit);
            String resolved = ProfileSizes.cameraIdFor(context, candidate); if (resolved != null) choices.add(resolved);
        }
        List<String> ids = new ArrayList<>(choices);
        String[] labels = new String[ids.size() + 2]; labels[0] = context.getString(R.string.opt_camera_auto);
        labels[1] = context.getString(R.string.record_source_unassigned);
        for (int i = 0; i < ids.size(); i++) labels[i + 2] = context.getString(R.string.opt_camera_id, ids.get(i));
        String old = config.getSourceCameraId(role);
        int[] selected = {old == null ? 0 : old.isEmpty() ? 1 : Math.max(0, ids.indexOf(old) + 2)};
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(context.getString(R.string.record_source_pick, roleLabel(context, role)))
                .setSingleChoiceItems(labels, selected[0], (dialog, which) -> selected[0] = which)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    String picked = selected[0] == 0 ? null : selected[0] == 1 ? "" : ids.get(selected[0] - 2);
                    String resolved = picked == null ? ProfileSizes.automaticCameraIdFor(context, role) : picked;
                    Set<String> active = ProfileChannels.of(profile, config.getPreviewSourceMode()).keys;
                    if (resolved != null && !resolved.isEmpty()) for (CameraProfile camera : profile.cameras) {
                        if ((camera.enabled || active.contains(RecordingSources.key(camera.role)))
                                && !camera.role.equals(role) && resolved.equals(ProfileSizes.cameraIdFor(context, camera.role))) {
                            Toast.makeText(context, R.string.record_source_duplicate, Toast.LENGTH_LONG).show(); return;
                        }
                    }
                    config.setSourceCameraId(role, picked);
                    if ("".equals(picked)) {
                        // Explicit Unassigned preserves the existing editor behavior, without normalising other fields.
                        ProfileStore store = new ProfileStore(context); Profile stored = store.current(); boolean update = false;
                        boolean roads = CameraProfile.ROLE_ROAD_FRONT.equals(role) || CameraProfile.ROLE_ROAD_REAR.equals(role);
                        for (CameraProfile camera : stored.cameras) if (camera.role.equals(role) || roads &&
                                (CameraProfile.ROLE_ROAD_FRONT.equals(camera.role) || CameraProfile.ROLE_ROAD_REAR.equals(camera.role))) {
                            if (camera.enabled) { camera.enabled = false; update = true; }
                        }
                        if (update) store.save(stored);
                    }
                    changed.run(); saved(context);
                }).setNegativeButton(R.string.action_cancel, null));
    }
}
