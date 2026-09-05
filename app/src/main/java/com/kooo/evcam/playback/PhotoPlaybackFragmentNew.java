package com.kooo.evcam.playback;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import android.widget.PopupMenu;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片回看Fragment（新版）
 * 支持左右分栏、四宫格预览、单路/多路切换
 */
public class PhotoPlaybackFragmentNew extends Fragment {

    // UI 组件
    private RecyclerView photoList;
    private TextView emptyText;
    private TextView currentDatetime;
    private View noSelectionHint;
    private Button btnMenu, btnRefresh, btnMultiSelect, btnHome;
    private Button btnSelectAll, btnDeleteSelected, btnCancelSelect, btnShareSelected;
    private TextView selectedCount;
    private static final String TAG = "PhotoPlaybackFragmentNew";
    private View toolbar, multiSelectToolbar;

    // 预览区组件
    private View multiViewLayout, singleViewLayout;
    private ImageView imageFront, imageBack, imageLeft, imageRight, imageSingle;
    private FrameLayout frameFront, frameBack, frameLeft, frameRight;
    private TextView labelFront, labelBack, labelLeft, labelRight, labelSingle;
    private TextView placeholderFront, placeholderBack, placeholderLeft, placeholderRight;
    private Button btnViewMode;
    private Button btnSendToPhone;
    private View controlsLayout;

    // 数据
    private List<DateSection<PhotoGroup>> dateSections = new ArrayList<>();
    private ExpandablePhotoGroupAdapter adapter;
    private PhotoGroup currentGroup;

    // 状态
    private boolean isMultiSelectMode = false;
    private boolean isSingleMode = false;
    private String currentSinglePosition = PhotoGroup.POSITION_FRONT;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photo_playback_new, container, false);

        initViews(view);
        setupListeners();
        setupDoubleTapListeners();
        updatePhotoList();
        applyStatusBarInsets(view);

        return view;
    }

    private void initViews(View view) {
        // 工具栏
        toolbar = view.findViewById(R.id.toolbar);
        multiSelectToolbar = view.findViewById(R.id.multi_select_toolbar);
        btnMenu = view.findViewById(R.id.btn_menu);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        btnMultiSelect = view.findViewById(R.id.btn_multi_select);
        btnHome = view.findViewById(R.id.btn_home);
        currentDatetime = view.findViewById(R.id.current_datetime);

        // 多选工具栏
        btnSelectAll = view.findViewById(R.id.btn_select_all);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelect = view.findViewById(R.id.btn_cancel_select);
        btnShareSelected = view.findViewById(R.id.btn_share_selected);
        selectedCount = view.findViewById(R.id.selected_count);

        // 列表
        photoList = view.findViewById(R.id.photo_list);
        emptyText = view.findViewById(R.id.empty_text);
        noSelectionHint = view.findViewById(R.id.no_selection_hint);

        // 四宫格预览
        multiViewLayout = view.findViewById(R.id.multi_view_layout);
        singleViewLayout = view.findViewById(R.id.single_view_layout);

        imageFront = view.findViewById(R.id.image_front);
        imageBack = view.findViewById(R.id.image_back);
        imageLeft = view.findViewById(R.id.image_left);
        imageRight = view.findViewById(R.id.image_right);
        imageSingle = view.findViewById(R.id.image_single);

        frameFront = view.findViewById(R.id.frame_front);
        frameBack = view.findViewById(R.id.frame_back);
        frameLeft = view.findViewById(R.id.frame_left);
        frameRight = view.findViewById(R.id.frame_right);

        labelFront = view.findViewById(R.id.label_front);
        labelBack = view.findViewById(R.id.label_back);
        labelLeft = view.findViewById(R.id.label_left);
        labelRight = view.findViewById(R.id.label_right);
        labelSingle = view.findViewById(R.id.label_single);

        placeholderFront = view.findViewById(R.id.placeholder_front);
        placeholderBack = view.findViewById(R.id.placeholder_back);
        placeholderLeft = view.findViewById(R.id.placeholder_left);
        placeholderRight = view.findViewById(R.id.placeholder_right);

        // 摄像头切换按钮和控制栏
        btnViewMode = view.findViewById(R.id.btn_view_mode);
        btnSendToPhone = view.findViewById(R.id.btn_send_to_phone);
        controlsLayout = view.findViewById(R.id.controls_layout);

        // 设置列表（竖屏2列，横屏1列，日期头部跨越所有列）
        adapter = new ExpandablePhotoGroupAdapter(getContext(), dateSections);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 2);
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // 日期头部占满2列，图片项占1列
                    return adapter.getItemViewType(position) == 0 ? 2 : 1;
                }
            });
            photoList.setLayoutManager(gridLayoutManager);
        } else {
            photoList.setLayoutManager(new LinearLayoutManager(getContext()));
        }
        photoList.setAdapter(adapter);

        // 初始状态：隐藏四宫格，显示提示
        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
    }

    private void setupListeners() {
        // 菜单按钮
        btnMenu.setOnClickListener(v -> {
            if (getActivity() != null) {
                DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                }
            }
        });

        // 返回主界面
        btnHome.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        // 刷新
        btnRefresh.setOnClickListener(v -> updatePhotoList());

        // 多选模式
        btnMultiSelect.setOnClickListener(v -> toggleMultiSelectMode());
        btnSelectAll.setOnClickListener(v -> selectAll());
        btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        btnDeleteSelected.setOnClickListener(v -> deleteSelected());
        btnShareSelected.setOnClickListener(v -> shareSelected());

        // 列表项点击
        adapter.setOnItemClickListener((group, position) -> {
            loadPhotoGroup(group);
        });

        adapter.setOnItemSelectedListener(group -> {
            updateSelectedCount();
        });

        // 列表项长按 - 分享图片
        adapter.setOnItemLongClickListener((group, position) -> {
            if (adapter.isMultiSelectMode()) {
                // 多选模式下，分享所有已选中的图片
                shareSelected();
            } else {
                // 单选模式下，分享当前长按的图片组
                showPhotoShareDialog(group);
            }
        });

        // 摄像头切换按钮（循环切换）
        btnViewMode.setOnClickListener(v -> cycleViewMode());

        if (btnSendToPhone != null) {
            btnSendToPhone.setOnClickListener(v -> sendCurrentPhotoToPhone());
        }
    }

    /**
     * 设置四宫格双击监听（双击放大到单路）
     */
    private void setupDoubleTapListeners() {
        setupDoubleTap(frameFront, PhotoGroup.POSITION_FRONT,
                getString(R.string.zeekr_lane_front));
        setupDoubleTap(frameBack, PhotoGroup.POSITION_BACK,
                getString(R.string.zeekr_lane_back));
        setupDoubleTap(frameLeft, PhotoGroup.POSITION_LEFT,
                getString(R.string.zeekr_lane_left));
        setupDoubleTap(frameRight, PhotoGroup.POSITION_RIGHT,
                getString(R.string.zeekr_lane_right));

        // 单路模式双击返回多路
        if (singleViewLayout != null) {
            GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (isSingleMode) {
                        switchToMultiMode();
                    }
                    return true;
                }
            });
            singleViewLayout.setOnTouchListener((v, event) -> {
                detector.onTouchEvent(event);
                return true;
            });
        }
    }

    private void setupDoubleTap(View view, String position, String label) {
        if (view == null) return;

        GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isSingleMode && currentGroup != null && currentGroup.hasPhoto(position)) {
                    switchToSingleMode(position, label);
                }
                return true;
            }
        });

        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    /**
     * 把当前这张照片发到手机上。
     *
     * <p>四宫格模式下不发：那时屏幕上是四张图，「这一张」没有定义。
     * 与其挑一张替用户做主，不如让他先放大到想要的那一路。</p>
     */
    private void sendCurrentPhotoToPhone() {
        if (!isSingleMode || currentSinglePosition == null) {
            Toast.makeText(getContext(), R.string.share_photo_pick_lane_first,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (currentGroup == null) {
            Toast.makeText(getContext(), R.string.share_phone_no_file,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        com.kooo.evcam.share.PhoneShare.show(getActivity(),
                currentGroup.getPhotoFile(currentSinglePosition));
    }

    /**
     * 切换到单路模式
     */
    private void switchToSingleMode(String position, String label) {
        isSingleMode = true;
        currentSinglePosition = position;

        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.VISIBLE);
        labelSingle.setText(label);
        btnViewMode.setText(getString(R.string.photo_mode_single, label));

        // 加载大图
        if (currentGroup != null) {
            File photoFile = currentGroup.getPhotoFile(position);
            loadImage(photoFile, imageSingle);
        }
    }

    /**
     * 切换到多路模式
     */
    private void switchToMultiMode() {
        isSingleMode = false;

        multiViewLayout.setVisibility(View.VISIBLE);
        singleViewLayout.setVisibility(View.GONE);
        btnViewMode.setText(getString(R.string.photo_mode_multi));
    }

    /**
     * 循环切换视图模式：多路 → 前摄 → 后摄 → 左摄 → 右摄 → 多路...
     * 只切换到有图片的摄像头
     */
    private void cycleViewMode() {
        if (currentGroup == null) return;
        
        // 构建可用位置列表
        java.util.List<String> availablePositions = new java.util.ArrayList<>();
        availablePositions.add("multi"); // 多路始终可用
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_FRONT)) availablePositions.add(PhotoGroup.POSITION_FRONT);
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_BACK)) availablePositions.add(PhotoGroup.POSITION_BACK);
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_LEFT)) availablePositions.add(PhotoGroup.POSITION_LEFT);
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_RIGHT)) availablePositions.add(PhotoGroup.POSITION_RIGHT);
        
        // 找到当前位置的索引
        String currentPos = isSingleMode ? currentSinglePosition : "multi";
        int currentIndex = availablePositions.indexOf(currentPos);
        if (currentIndex < 0) currentIndex = 0;
        
        // 切换到下一个位置
        int nextIndex = (currentIndex + 1) % availablePositions.size();
        String nextPos = availablePositions.get(nextIndex);
        
        if ("multi".equals(nextPos)) {
            switchToMultiMode();
        } else {
            String label = getPositionLabel(nextPos);
            switchToSingleMode(nextPos, label);
        }
    }
    
    /**
     * 获取位置对应的标签
     */
    private String getPositionLabel(String position) {
        switch (position) {
            case PhotoGroup.POSITION_FRONT: return getString(R.string.zeekr_lane_front);
            case PhotoGroup.POSITION_BACK: return getString(R.string.zeekr_lane_back);
            case PhotoGroup.POSITION_LEFT: return getString(R.string.zeekr_lane_left);
            case PhotoGroup.POSITION_RIGHT: return getString(R.string.zeekr_lane_right);
            default: return "";
        }
    }

    /**
     * 切换单路/多路模式（保留用于双击）
     */
    private void toggleViewMode() {
        cycleViewMode();
    }

    /**
     * 加载图片组进行显示
     */
    private void loadPhotoGroup(PhotoGroup group) {
        this.currentGroup = group;
        noSelectionHint.setVisibility(View.GONE);

        // 如果在单路模式下，检查当前选择的摄像头是否有图片
        if (isSingleMode) {
            if (!group.hasPhoto(currentSinglePosition)) {
                // 当前摄像头在新图片组中没有图片，切回多路模式
                isSingleMode = false;
                btnViewMode.setText(getString(R.string.photo_mode_multi));
            }
        }

        // 显示四宫格（根据当前模式）
        if (isSingleMode) {
            multiViewLayout.setVisibility(View.GONE);
            singleViewLayout.setVisibility(View.VISIBLE);
            // 重新加载单路大图
            File photoFile = group.getPhotoFile(currentSinglePosition);
            loadImage(photoFile, imageSingle);
        } else {
            multiViewLayout.setVisibility(View.VISIBLE);
            singleViewLayout.setVisibility(View.GONE);
        }

        // 显示控制栏
        controlsLayout.setVisibility(View.VISIBLE);

        // 更新标题栏日期时间
        currentDatetime.setText(group.getFormattedDateTime());

        // 更新四宫格的占位符和图片
        updatePhotoDisplay(group);
    }

    /**
     * 更新图片显示
     */
    private void updatePhotoDisplay(PhotoGroup group) {
        boolean hasFront = group.hasPhoto(PhotoGroup.POSITION_FRONT);
        boolean hasBack = group.hasPhoto(PhotoGroup.POSITION_BACK);
        boolean hasLeft = group.hasPhoto(PhotoGroup.POSITION_LEFT);
        boolean hasRight = group.hasPhoto(PhotoGroup.POSITION_RIGHT);

        // 前置
        imageFront.setVisibility(hasFront ? View.VISIBLE : View.GONE);
        placeholderFront.setVisibility(hasFront ? View.GONE : View.VISIBLE);
        if (hasFront) loadImage(group.getFrontPhoto(), imageFront);

        // 后置
        imageBack.setVisibility(hasBack ? View.VISIBLE : View.GONE);
        placeholderBack.setVisibility(hasBack ? View.GONE : View.VISIBLE);
        if (hasBack) loadImage(group.getBackPhoto(), imageBack);

        // 左侧
        imageLeft.setVisibility(hasLeft ? View.VISIBLE : View.GONE);
        placeholderLeft.setVisibility(hasLeft ? View.GONE : View.VISIBLE);
        if (hasLeft) loadImage(group.getLeftPhoto(), imageLeft);

        // 右侧
        imageRight.setVisibility(hasRight ? View.VISIBLE : View.GONE);
        placeholderRight.setVisibility(hasRight ? View.GONE : View.VISIBLE);
        if (hasRight) loadImage(group.getRightPhoto(), imageRight);
    }

    /**
     * 加载图片
     */
    private void loadImage(File photoFile, ImageView imageView) {
        if (photoFile == null || !photoFile.exists() || getContext() == null) {
            return;
        }

        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(photoFile.lastModified()))
                .placeholder(android.R.color.black)
                .error(android.R.color.black);

        Glide.with(getContext())
                .load(photoFile)
                .apply(options)
                .into(imageView);
    }

    /**
     * 更新图片列表（按日期分组，然后按时间戳分组）
     */
    private void updatePhotoList() {
        // 屏幕上现在放的是哪一组。扫描之后 PhotoGroup 全是新对象，
        // 得靠时间戳把它认回来。
        String shown = currentGroup != null ? currentGroup.getTimestampPrefix() : null;
        dateSections.clear();

        File saveDir = StorageHelper.getPhotoDir(getContext());
        if (!saveDir.exists() || !saveDir.isDirectory()) {
            showEmptyState();
            showNoSelection();
            return;
        }

        File[] files = saveDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        });

        if (files == null || files.length == 0) {
            showEmptyState();
            showNoSelection();
            return;
        }

        // 第一步：按时间戳分组（同一秒拍摄的多路图片）
        Map<String, PhotoGroup> groupMap = new HashMap<>();
        for (File file : files) {
            String timestamp = PhotoGroup.extractTimestampPrefix(file.getName());
            PhotoGroup group = groupMap.get(timestamp);
            if (group == null) {
                group = new PhotoGroup(timestamp);
                groupMap.put(timestamp, group);
            }
            group.addFile(file);
        }

        // 转为列表并排序（最新的在前）
        List<PhotoGroup> allGroups = new ArrayList<>(groupMap.values());
        Collections.sort(allGroups, (g1, g2) -> g2.getCaptureTime().compareTo(g1.getCaptureTime()));

        // 第二步：按日期分组
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Map<String, DateSection<PhotoGroup>> dateSectionMap = new LinkedHashMap<>();
        
        for (PhotoGroup group : allGroups) {
            String dateString = dateFormat.format(group.getCaptureTime());
            DateSection<PhotoGroup> section = dateSectionMap.get(dateString);
            if (section == null) {
                section = new DateSection<>(dateString, group.getCaptureTime());
                dateSectionMap.put(dateString, section);
            }
            section.addItem(group);
        }

        // 日期分组已按日期排序（LinkedHashMap 保持插入顺序，而 allGroups 已排序）
        dateSections.addAll(dateSectionMap.values());

        // 更新UI
        if (dateSections.isEmpty()) {
            showEmptyState();
        } else {
            photoList.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
        }

        adapter.buildFlattenedList();
        adapter.notifyDataSetChanged();
        reloadShownGroup(shown);
    }

    /**
     * 扫描之后，把预览区换成新扫出来的那一组。
     *
     * <h3>为什么必须换</h3>
     *
     * <p>扫描把每一组都重建了，{@code currentGroup} 指着的还是上一次扫出来的旧对象。
     * 不换的话「刷新」只刷新了列表，预览区还停在旧的那一份 —— 拍完三路马上进来，
     * 最后一张还没落盘，刷新看着毫无反应，切到别的照片再切回来才出得来。</p>
     *
     * <p>那一组已经不在了（被删了）就退回没选中的状态，不留一张指向空文件的旧图。</p>
     */
    private void reloadShownGroup(String timestampPrefix) {
        if (timestampPrefix == null) {
            return;
        }
        for (DateSection<PhotoGroup> section : dateSections) {
            for (PhotoGroup group : section.getItems()) {
                if (timestampPrefix.equals(group.getTimestampPrefix())) {
                    adapter.setSelectedGroup(group);
                    adapter.notifyDataSetChanged();
                    loadPhotoGroup(group);
                    return;
                }
            }
        }
        showNoSelection();
    }

    /** 回到「还没选照片」的样子。 */
    private void showNoSelection() {
        currentGroup = null;
        adapter.setSelectedGroup(null);
        adapter.notifyDataSetChanged();
        isSingleMode = false;
        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        controlsLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
        currentDatetime.setText("");
    }

    private void showEmptyState() {
        photoList.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void toggleMultiSelectMode() {
        isMultiSelectMode = !isMultiSelectMode;
        adapter.clearSelection();
        adapter.setMultiSelectMode(isMultiSelectMode);
        adapter.notifyDataSetChanged();

        if (isMultiSelectMode) {
            toolbar.setVisibility(View.GONE);
            multiSelectToolbar.setVisibility(View.VISIBLE);
            updateSelectedCount();
        } else {
            toolbar.setVisibility(View.VISIBLE);
            multiSelectToolbar.setVisibility(View.GONE);
        }
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        adapter.clearSelection();
        adapter.setMultiSelectMode(false);
        adapter.notifyDataSetChanged();
        toolbar.setVisibility(View.VISIBLE);
        multiSelectToolbar.setVisibility(View.GONE);
    }

    private void selectAll() {
        adapter.selectAll();
        adapter.notifyDataSetChanged();
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        selectedCount.setText(getString(R.string.msg_selected_n, adapter.getSelectedCount()));
    }

    private void deleteSelected() {
        Set<PhotoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty()) {
            return;
        }

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_delete_photos_title)
                .setMessage(getString(R.string.dlg_delete_photos_msg, selectedGroups.size()))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    // selectedGroups 就是 adapter 手里那个集合，下面会被清空，
                    // 先留一份 —— 删完还要拿它对一下预览区放的是不是其中之一
                    Set<PhotoGroup> deleted = new HashSet<>(selectedGroups);
                    int deletedCount = 0;
                    
                    // 删除选中的图片组
                    for (PhotoGroup group : deleted) {
                        deletedCount += group.deleteAll();
                    }
                    
                    // 从日期分组中移除已删除的组
                    for (DateSection<PhotoGroup> section : dateSections) {
                        section.getItems().removeAll(deleted);
                    }
                    
                    // 移除空的日期分组
                    dateSections.removeIf(section -> section.getItemCount() == 0);

                    adapter.clearSelection();
                    adapter.buildFlattenedList();
                    // 预览区放的那一组也在这一批里的话，别再挂着已经删掉的照片
                    if (currentGroup != null && deleted.contains(currentGroup)) {
                        showNoSelection();
                    } else {
                        adapter.setSelectedGroup(currentGroup);
                    }
                    adapter.notifyDataSetChanged();
                    updateSelectedCount();

                    if (getContext() != null) {
                        android.widget.Toast.makeText(getContext(),
                                getString(R.string.msg_photos_deleted, deletedCount),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }

                    if (dateSections.isEmpty()) {
                        exitMultiSelectMode();
                        showEmptyState();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void applyStatusBarInsets(View view) {
        View toolbarView = view.findViewById(R.id.toolbar);
        if (toolbarView != null) {
            final int originalPaddingTop = toolbarView.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbarView, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbarView);
        }
    }

    /**
     * 分享选中的图片
     */
    private void shareSelected() {
        Set<PhotoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty()) {
            Toast.makeText(getContext(), getString(R.string.msg_select_photos_first), Toast.LENGTH_SHORT).show();
            return;
        }

        // 收集所有选中的图片文件
        List<File> allPhotoFiles = new ArrayList<>();
        String[] positions = {PhotoGroup.POSITION_FRONT, PhotoGroup.POSITION_BACK,
                              PhotoGroup.POSITION_LEFT, PhotoGroup.POSITION_RIGHT};

        for (PhotoGroup group : selectedGroups) {
            for (String position : positions) {
                File photoFile = group.getPhotoFile(position);
                if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                    allPhotoFiles.add(photoFile);
                }
            }
        }

        if (allPhotoFiles.isEmpty()) {
            Toast.makeText(getContext(), getString(R.string.msg_no_photos_to_share), Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示分享选项对话框
        showPhotoShareOptionsDialog(getString(R.string.action_share_photos),
            getString(R.string.photo_share_count,
                    selectedGroups.size(), allPhotoFiles.size()),
            allPhotoFiles);
    }

    /**
     * 显示单组图片分享对话框
     */
    private void showPhotoShareDialog(PhotoGroup group) {
        if (getContext() == null) return;

        // 获取所有可用的图片文件
        List<File> photoFiles = new ArrayList<>();
        String[] positions = {PhotoGroup.POSITION_FRONT, PhotoGroup.POSITION_BACK,
                              PhotoGroup.POSITION_LEFT, PhotoGroup.POSITION_RIGHT};

        for (String position : positions) {
            File photoFile = group.getPhotoFile(position);
            if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                photoFiles.add(photoFile);
            }
        }

        if (photoFiles.isEmpty()) {
            Toast.makeText(getContext(), getString(R.string.msg_no_photos_to_share), Toast.LENGTH_SHORT).show();
            return;
        }

        showPhotoShareOptionsDialog(getString(R.string.action_share_photos),
            getString(R.string.photo_share_total, photoFiles.size()),
            photoFiles);
    }

    /**
     * 显示图片分享选项对话框（使用与视频相同的布局）
     */
    private void showPhotoShareOptionsDialog(String title, String message, List<File> photoFiles) {
        if (getContext() == null) return;

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(
                getContext(), R.style.AlertDialogTheme);

        // 加载自定义布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_share_options, null);
        TextView titleView = dialogView.findViewById(R.id.dialog_title);
        TextView messageView = dialogView.findViewById(R.id.dialog_message);
        View btnQr = dialogView.findViewById(R.id.btn_qr);
        View btnShare = dialogView.findViewById(R.id.btn_share);
        View btnClose = dialogView.findViewById(R.id.btn_close);

        titleView.setText(title);
        messageView.setText(message);

        builder.setView(dialogView);
        builder.setCancelable(true);

        android.app.AlertDialog dialog = builder.create();

        // 设置按钮点击事件
        // 扫码互传依赖已移除的 transfer 模块；U 盘可直接取文件，此入口隐藏
        btnQr.setVisibility(android.view.View.GONE);

        btnShare.setOnClickListener(v -> {
            Log.d(TAG, "用户选择系统分享图片");
            dialog.dismiss();
            sharePhotos(photoFiles);
        });

        btnClose.setOnClickListener(v -> {
            Log.d(TAG, "用户取消图片分享");
            dialog.dismiss();
        });

        dialog.show();
        Log.d(TAG, "图片分享选项对话框已显示");
    }


    /**
     * 分享图片文件
     */
    private void sharePhotos(List<File> photoFiles) {
        if (getContext() == null || photoFiles.isEmpty()) return;

        try {
            String authority = getContext().getPackageName() + ".fileprovider";

            if (photoFiles.size() == 1) {
                // 分享单个图片
                File photoFile = photoFiles.get(0);

                // 检查文件是否存在且可读
                if (!photoFile.exists() || !photoFile.canRead()) {
                    Toast.makeText(getContext(), getString(R.string.msg_file_unreadable), Toast.LENGTH_SHORT).show();
                    return;
                }

                Uri photoUri = FileProvider.getUriForFile(getContext(), authority, photoFile);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("image/jpeg");
                shareIntent.putExtra(Intent.EXTRA_STREAM, photoUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                    getString(R.string.action_share_photos));
                shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.msg_share_photo_subject));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // 创建选择器
                Intent chooser = Intent.createChooser(shareIntent, getString(R.string.action_share_photos));
                if (chooser.resolveActivity(getContext().getPackageManager()) != null) {
                    startActivity(chooser);
                } else {
                    Toast.makeText(getContext(), getString(R.string.msg_no_share_target), Toast.LENGTH_SHORT).show();
                }
            } else {
                // 分享多个图片
                ArrayList<Uri> photoUris = new ArrayList<>();
                for (File photoFile : photoFiles) {
                    // 检查文件是否存在且可读
                    if (!photoFile.exists() || !photoFile.canRead()) {
                        continue;
                    }
                    Uri photoUri = FileProvider.getUriForFile(getContext(), authority, photoFile);
                    photoUris.add(photoUri);
                }

                if (photoUris.isEmpty()) {
                    Toast.makeText(getContext(), getString(R.string.msg_nothing_to_share), Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("image/jpeg");
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, photoUris);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                    getString(R.string.action_share_photos));
                shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.msg_share_photo_subject));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // 创建选择器
                Intent chooser = Intent.createChooser(shareIntent, getString(R.string.action_share_photos));
                if (chooser.resolveActivity(getContext().getPackageManager()) != null) {
                    startActivity(chooser);
                } else {
                    Toast.makeText(getContext(), getString(R.string.msg_no_share_target), Toast.LENGTH_SHORT).show();
                }
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "分享图片失败: FileProvider 无法处理该文件路径", e);
            Toast.makeText(getContext(), R.string.msg_share_path_unsupported,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "分享图片失败", e);
            Toast.makeText(getContext(), getString(R.string.msg_share_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
