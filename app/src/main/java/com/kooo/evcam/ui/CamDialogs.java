package com.kooo.evcam.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.R;

/**
 * 应用里所有对话框都从这里弹。
 *
 * <h3>为什么要有这一层</h3>
 *
 * <p>「确认键看不见」修过五次。每次修的都是「让主题属性解析到正确的颜色」，
 * 而下一个没传主题、或者走了另一条解析路径的对话框就会再犯一次 ——
 * 按钮在那里、能点，只是颜色和底色一样。</p>
 *
 * <p>这里不再问主题要颜色：对话框显示出来的那一刻，把三个按钮的底色、字色、
 * 尺寸直接画上去，颜色取自语义色（日夜各一份）。</p>
 *
 * <p>只画颜色还不够。0.39.0 之前，应用里大部分对话框是框架的
 * {@code android.app.AlertDialog}，它那一栏按钮长什么样由车机 ROM 说了算 ——
 * 在实车上整栏不显示，于是「确认键看不见」修一次复发一次；而同期用 Material
 * 对话框的那几个（相机映射、设备名）一直是好的。所以现在只剩一条路：
 * {@code MaterialAlertDialogBuilder} + {@code Theme.Cam.MaterialAlertDialog}，
 * 由 {@code DialogStyleTest} 钉住。</p>
 *
 * <h3>三条规则</h3>
 *
 * <ul>
 *   <li><b>主操作实心，次操作灰底</b>：哪个是「确定」靠颜色一眼分辨，不靠位置记忆。
 *       两者都是 52dp 高的实块，不是一行字。</li>
 *   <li><b>破坏性操作用红色</b>：删除、恢复初值、改系统配置这类不可逆的，
 *       主操作换成录制红（{@link #showDestructive}）。</li>
 *   <li><b>按钮栏不跟内容一起滚</b>：这是 AlertDialog 本来的结构，这里不去动它。</li>
 * </ul>
 */
public final class CamDialogs {

    private static final int BUTTON_HEIGHT_DP = 52;
    private static final int BUTTON_MIN_WIDTH_DP = 120;
    private static final int BUTTON_PADDING_DP = 24;
    private static final int BUTTON_GAP_DP = 12;
    private static final int BUTTON_BAR_PADDING_DP = 12;
    private static final int INPUT_HEIGHT_DP = 56;
    private static final int INPUT_PADDING_H_DP = 16;
    private static final int INPUT_PADDING_V_DP = 14;
    private static final int CORNER_DP = 10;

    private CamDialogs() {
    }

    /**
     * 对话框里的输入框。
     *
     * <p>以前几处各写各的：车牌号和存储上限是裸的 EditText，设备名和问题描述是
     * 像素内边距加一圈描边 —— 同一个应用里两种长相。这里给一份。</p>
     */
    public static EditText input(Context context) {
        EditText input = new EditText(context);
        float density = context.getResources().getDisplayMetrics().density;
        input.setBackgroundResource(R.drawable.bg_dialog_input);
        input.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.text_row));
        int horizontal = Math.round(INPUT_PADDING_H_DP * density);
        int vertical = Math.round(INPUT_PADDING_V_DP * density);
        input.setPadding(horizontal, vertical, horizontal, vertical);
        input.setMinHeight(Math.round(INPUT_HEIGHT_DP * density));
        return input;
    }

    // ------------------------------------------------------------------ 弹

    public static androidx.appcompat.app.AlertDialog show(
            androidx.appcompat.app.AlertDialog.Builder builder) {
        return showNow(style(builder.create(), false));
    }

    /** 主操作不可逆（删除、恢复初值、改系统文件）：主按钮用红色。 */
    public static androidx.appcompat.app.AlertDialog showDestructive(
            androidx.appcompat.app.AlertDialog.Builder builder) {
        return showNow(style(builder.create(), true));
    }

    /**
     * 已经 {@code create()} 出来、还要先改窗口属性再 show 的那几处用这个。
     * 返回同一个对象，可以直接接在 {@code create()} 后面。
     */
    public static <T extends Dialog> T style(T dialog) {
        return style(dialog, false);
    }

    public static <T extends Dialog> T styleDestructive(T dialog) {
        return style(dialog, true);
    }

    private static <T extends Dialog> T style(T dialog, boolean destructive) {
        if (dialog != null) {
            dialog.setOnShowListener(d -> paintButtons(dialog, destructive));
        }
        return dialog;
    }

    private static <T extends Dialog> T showNow(T dialog) {
        dialog.show();
        return dialog;
    }

    /**
     * 已经自己设了 OnShowListener 的对话框（一个 Dialog 只能挂一个），
     * 在那个监听里调这一句，效果和 {@link #style} 一样。
     */
    public static void paintNow(Dialog dialog) {
        paintButtons(dialog, false);
    }

    // ------------------------------------------------------------------ 画按钮

    private static void paintButtons(Dialog dialog, boolean destructive) {
        paint(button(dialog, DialogInterface.BUTTON_POSITIVE),
                destructive ? R.color.recording : R.color.energy, R.color.on_energy, true);
        paint(button(dialog, DialogInterface.BUTTON_NEGATIVE),
                R.color.sunken, R.color.text_primary, false);
        paint(button(dialog, DialogInterface.BUTTON_NEUTRAL),
                R.color.sunken, R.color.text_primary, false);
        makeRoomForButtons(dialog);
    }

    /**
     * 按钮栏是按「一行字」的高度排的，而这里的按钮是 52dp 的块：
     * 不给它留出位置，块的上半就被裁掉，看起来像只有下沿有圆角。
     */
    private static void makeRoomForButtons(Dialog dialog) {
        Button any = button(dialog, DialogInterface.BUTTON_POSITIVE);
        if (any == null) {
            any = button(dialog, DialogInterface.BUTTON_NEGATIVE);
        }
        if (any == null || !(any.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) any.getParent();
        float density = bar.getResources().getDisplayMetrics().density;
        int vertical = Math.round(BUTTON_BAR_PADDING_DP * density);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(bar.getPaddingLeft(), vertical, bar.getPaddingRight(), vertical);
        bar.setMinimumHeight(Math.round(
                (BUTTON_HEIGHT_DP + 2 * BUTTON_BAR_PADDING_DP) * density));
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params != null && params.height >= 0) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bar.setLayoutParams(params);
        }
        bar.requestLayout();
    }

    private static Button button(Dialog dialog, int which) {
        if (dialog instanceof androidx.appcompat.app.AlertDialog) {
            return ((androidx.appcompat.app.AlertDialog) dialog).getButton(which);
        }
        return null;
    }

    private static void paint(Button button, int backgroundRes, int textRes, boolean primary) {
        if (button == null || button.getVisibility() != View.VISIBLE) {
            return;
        }
        Context context = button.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int background = ContextCompat.getColor(context, backgroundRes);
        int ripple = ContextCompat.getColor(context, R.color.nav_ripple_color);

        button.setTextColor(ContextCompat.getColor(context, textRes));
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.text_row));
        button.setTypeface(Typeface.create(button.getTypeface(),
                primary ? Typeface.BOLD : Typeface.NORMAL));

        if (button instanceof MaterialButton) {
            // AppCompat / Material 的对话框：按钮本身就是 MaterialButton，改它的着色
            MaterialButton material = (MaterialButton) button;
            material.setBackgroundTintList(ColorStateList.valueOf(background));
            material.setRippleColor(ColorStateList.valueOf(ripple));
            material.setCornerRadius(Math.round(CORNER_DP * density));
            material.setInsetTop(0);
            material.setInsetBottom(0);
        } else {
            // 框架的 AlertDialog：普通 Button，直接给一块圆角底
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(background);
            shape.setCornerRadius(CORNER_DP * density);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
        }

        int height = Math.round(BUTTON_HEIGHT_DP * density);
        int minWidth = Math.round(BUTTON_MIN_WIDTH_DP * density);
        int padding = Math.round(BUTTON_PADDING_DP * density);
        button.setMinHeight(height);
        button.setMinimumHeight(height);
        button.setMinWidth(minWidth);
        button.setMinimumWidth(minWidth);
        button.setPadding(padding, 0, padding, 0);

        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMarginStart(Math.round(BUTTON_GAP_DP * density));
            margins.topMargin = Math.round(8 * density);
            margins.bottomMargin = Math.round(8 * density);
            button.setLayoutParams(margins);
        }
    }
}
