/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Place;
import com.kooo.evcam.automation.PlaceStore;
import com.kooo.evcam.automation.Rule;
import com.kooo.evcam.automation.ShareCode;
import com.kooo.evcam.share.QrCode;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 分享码（ui-spec §十八「分享码」）：展开的一条最后一行「分享码」（{@link #rowText}），点了弹「分享这条自动化」框
 * （{@link #showShare}：二维码 + 码）；「添加自动化」里的「输入分享码」框（{@link #showImport}）。码的规格在
 * {@link ShareCode}。车上分享、导入都不联网。
 */
final class AutomationShare {

    /**
     * 二维码里的网址：手机扫了在 zeekr.autos 打开这一条。码在 # 后面 —— 浏览器不把 # 后面的发给网站，
     * 网站只在用户自己点「分享」时才收到这串码。
     */
    static final String LINK = "https://zeekr.autos/s#";
    /** 二维码边长（同「用手机订阅」框）。 */
    private static final int QR_SIZE_PX = 420;
    /** 输入框最多收几个字符：最长的码 40 位上下，加上组间的空格。 */
    private static final int INPUT_MAX = 80;

    private AutomationShare() {
    }

    // ================================================================= 分享

    /** 这一条的码；不能分享的是 null。 */
    static String code(Rule r) {
        try {
            return ShareCode.encode(r);
        } catch (ShareCode.Rejected e) {
            return null;
        }
    }

    /** 「分享码」那一行的内容：码，4 个一组、等宽字；不能分享的写为什么。 */
    static CharSequence rowText(Context c, Rule r) {
        try {
            SpannableString shown = new SpannableString(ShareCode.display(ShareCode.encode(r)));
            shown.setSpan(new TypefaceSpan("monospace"), 0, shown.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return shown;
        } catch (ShareCode.Rejected e) {
            if (e.problem != ShareCode.Problem.PLACES) {
                return c.getString(R.string.auto_share_unsupported);
            }
            return ShareCode.places(r) > ShareCode.MAX_PLACES
                    ? c.getString(R.string.auto_share_too_many_places, ShareCode.MAX_PLACES)
                    : c.getString(R.string.auto_share_place_conditions);
        }
    }

    /** 「分享这条自动化」框：二维码（手机扫了在 zeekr.autos 打开这一条）、码（大字）、在别的车上怎么添加。按钮「关闭」。 */
    static AlertDialog showShare(Context c, Rule r) {
        String code = code(r);
        if (code == null) {
            return null;
        }
        float density = c.getResources().getDisplayMetrics().density;
        LinearLayout box = box(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        Bitmap bitmap = QrCode.encode(LINK + code, QR_SIZE_PX);
        if (bitmap != null) {
            ImageView qr = new ImageView(c);
            qr.setImageBitmap(bitmap);
            qr.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(qr, new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX));
        }
        TextView shown = new TextView(c);
        shown.setTextAppearance(R.style.TextAppearance_Cam_Title);
        shown.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        shown.setTypeface(Typeface.MONOSPACE);
        shown.setText(ShareCode.display(code));
        shown.setTextDirection(View.TEXT_DIRECTION_LTR);
        shown.setGravity(Gravity.CENTER_HORIZONTAL);
        shown.setPadding(0, Math.round(12 * density), 0, Math.round(4 * density));
        box.addView(shown, wide());
        // 画不出二维码时那一格是空的，说出来（同「用手机订阅」框）
        box.addView(centered(CamDialogs.note(c, c.getString(bitmap != null
                ? R.string.auto_share_scan : R.string.sentry_alerts_qr_failed))), wide());
        TextView how = centered(CamDialogs.note(c, c.getString(R.string.auto_share_how)));
        how.setPadding(0, Math.round(4 * density), 0, Math.round(8 * density));
        box.addView(how, wide());
        return CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_share_title)
                .setView(scroll(c, box))
                .setPositiveButton(R.string.action_close, null));
    }

    // ================================================================= 输入分享码

    /** 「输入分享码」框里打到哪儿了。 */
    private static final class Typed {
        /** 整理过的码（不带空格）。 */
        String code = "";
        /** 验得过的那一条（地点是编号 "1"……）；没打满、验不过是 null。 */
        Rule shared;
        /** 验不过的原因（打满 8 位以后才验）。 */
        ShareCode.Problem problem;
        /** 点过「添加」而码不对：这时才说「分享码不对」（还在打的时候不说）。 */
        boolean saidWrong;
        /** 第 i 个是编号 i+1 选的地点（{@link Place#id}），没选的是 null。 */
        final List<String> chosen = new ArrayList<>();
    }

    /**
     * 「输入分享码」框：边打边 4 个一组；码对了下面马上显示这一条（当 / 而且 / 就，说法同展开的规则），用到地点的每个编号一行，
     * 从自己的地点里选（选过的灰着，不能选重）。「添加」：码不对说「分享码不对」；还有编号没选的，弹那个编号的选择框；
     * 都好了交给 {@code onAdd}（换成了自己的地点、开着、没有名字，同「添加自动化」）。
     */
    static AlertDialog showImport(Context c, Supplier<String> newId, Consumer<Rule> onAdd) {
        LinearLayout box = box(c);
        EditText input = CamDialogs.input(c);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(INPUT_MAX)});
        input.setTypeface(Typeface.MONOSPACE);
        input.setHint(R.string.auto_import_hint);
        // 码从左往右（阿拉伯文界面里也是），同网站的输入框
        input.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(input, wide());

        TextView status = CamDialogs.note(c, "");
        status.setPadding(0, Math.round(8 * c.getResources().getDisplayMetrics().density), 0, 0);
        box.addView(status, wide());
        LinearLayout preview = new LinearLayout(c);
        preview.setOrientation(LinearLayout.VERTICAL);
        box.addView(preview, wide());

        AlertDialog dialog = CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_import)
                .setView(scroll(c, box))
                .setPositiveButton(R.string.action_add, null)
                .setNegativeButton(R.string.action_cancel, null));
        Typed typed = new Typed();
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> draw(c, dialog, typed, status, preview, draw[0]);
        input.addTextChangedListener(new TextWatcher() {
            private boolean busy;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (busy) {
                    return;
                }
                busy = true;
                regroup(input, s);
                busy = false;
                if (read(typed, s.toString())) {
                    draw[0].run();
                }
            }
        });
        // 直接拿按钮改点击：默认会关掉框，而码不对、地点没选时框要留着
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (typed.shared == null) {
                typed.saidWrong = true;
                draw[0].run();
                return;
            }
            int missing = typed.chosen.indexOf(null);
            if (missing >= 0) {
                pickSlot(c, typed, missing, draw[0]);
                return;
            }
            Rule mine = ShareCode.adopt(typed.shared, newId.get(), typed.chosen);
            if (mine != null) {
                dialog.dismiss();
                onAdd.accept(mine);
            }
        });
        draw[0].run();
        return dialog;
    }

    /** 输入框里也是 4 个一组，光标停在原来那个字后面；输入法正在拼的时候不动。 */
    private static void regroup(EditText input, Editable s) {
        if (BaseInputConnection.getComposingSpanStart(s) >= 0) {
            return;
        }
        String raw = s.toString();
        int cursor = input.getSelectionEnd();
        cursor = cursor < 0 ? raw.length() : Math.min(cursor, raw.length());
        int before = ShareCode.normalize(raw.substring(0, cursor)).length();
        String shown = ShareCode.display(ShareCode.normalize(raw));
        if (shown.equals(raw)) {
            return;
        }
        s.replace(0, s.length(), shown);
        int pos = before == 0 ? 0 : before + (before - 1) / ShareCode.GROUP;
        input.setSelection(Math.min(pos, s.length()));
    }

    /** 照输入框里的字验一遍；码没变（只是重新分组）返回 false。码变了，选过的地点清掉。 */
    private static boolean read(Typed t, String text) {
        String code = ShareCode.normalize(text);
        if (code.equals(t.code)) {
            return false;
        }
        t.code = code;
        t.saidWrong = false;
        t.shared = null;
        t.problem = null;
        if (code.length() >= ShareCode.MIN_LENGTH) {
            try {
                t.shared = ShareCode.decode(code);
            } catch (ShareCode.Rejected e) {
                t.problem = e.problem;
            }
        }
        t.chosen.clear();
        int n = t.shared == null ? 0 : ShareCode.places(t.shared);
        for (int i = 0; i < n; i++) {
            t.chosen.add(null);
        }
        return true;
    }

    /** 框里的样子：这一条、每个编号选的地点、下面一句话，「添加」能不能点。 */
    private static void draw(Context c, AlertDialog dialog, Typed t, TextView status, LinearLayout preview,
                             Runnable again) {
        preview.removeAllViews();
        List<Place> mine = AutomationTexts.places(c);
        String note = null;
        boolean addable;
        if (t.shared != null) {
            int n = t.chosen.size();
            ruleRows(c, preview, t);
            if (n > mine.size()) {
                note = c.getResources().getQuantityString(R.plurals.auto_import_places_missing, n, n);
                addable = false;
            } else {
                for (int i = 0; i < n; i++) {
                    preview.addView(slotRow(c, t, i, mine, again));
                }
                addable = true;
            }
        } else if (t.problem == ShareCode.Problem.VERSION) {
            note = c.getString(R.string.auto_import_newer);
            addable = false;
        } else {
            note = t.saidWrong ? c.getString(R.string.auto_import_wrong) : null;
            addable = t.code.length() >= ShareCode.MIN_LENGTH;
        }
        status.setText(note);
        status.setVisibility(note == null ? View.GONE : View.VISIBLE);
        CamDialogs.enable(dialog, AlertDialog.BUTTON_POSITIVE, addable);
    }

    /** 这一条：当 / 而且 / 就，最后一句什么时候恢复（同展开的规则和网站）。地点选了的写自己的地点名，没选的写「地点 1」。 */
    private static void ruleRows(Context c, LinearLayout into, Typed t) {
        Function<String, String> names = id -> {
            int slot = Integer.parseInt(id) - 1;
            String place = slot >= 0 && slot < t.chosen.size() ? t.chosen.get(slot) : null;
            return place != null ? AutomationTexts.placeName(c, place)
                    : c.getString(R.string.auto_place_default_name, slot + 1);
        };
        Rule r = t.shared;
        int top = Math.round(12 * c.getResources().getDisplayMetrics().density);
        View first = clause(c, c.getString(R.string.auto_label_when), AutomationTexts.trigger(c, r.trigger, names));
        first.setPadding(0, top, 0, 0);
        into.addView(first);
        for (int i = 0; i < r.conditions.size(); i++) {
            into.addView(clause(c, i == 0 ? c.getString(R.string.auto_label_and) : null,
                    AutomationTexts.condition(c, r.conditions.get(i), names)));
        }
        for (int i = 0; i < r.changes.size(); i++) {
            into.addView(clause(c, i == 0 ? c.getString(R.string.auto_label_then) : null,
                    AutomationTexts.change(c, r.changes.get(i))));
        }
        String end = AutomationTexts.end(c, r.trigger, names);
        if (end != null && r.restores()) {
            into.addView(clause(c, null, c.getString(R.string.auto_restore_short, end)));
        }
    }

    /** 一个编号一行：「地点 1」后面是选的地点，没选的是「选择地点」（强调色）；点了选。 */
    private static View slotRow(Context c, Typed t, int slot, List<Place> mine, Runnable again) {
        String id = t.chosen.get(slot);
        int index = id == null ? -1 : PlaceStore.indexOf(mine, id);
        String value = index < 0 ? c.getString(R.string.auto_import_pick)
                : AutomationTexts.placeName(c, mine.get(index), index);
        LinearLayout row = (LinearLayout) clause(c, c.getString(R.string.auto_place_default_name, slot + 1), value);
        float density = c.getResources().getDisplayMetrics().density;
        row.setMinimumHeight(Math.round(56 * density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (index < 0) {
            ((TextView) row.getChildAt(1)).setTextColor(ContextCompat.getColor(c, R.color.energy_text));
        }
        ImageView chevron = new ImageView(c);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.text_tertiary)));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(26 * density);
        row.addView(chevron, new LinearLayout.LayoutParams(size, size));
        TypedValue ripple = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> pickSlot(c, t, slot, again));
        return row;
    }

    /** 给一个编号选自己的地点：别的编号选了的灰着（不能选重）。 */
    private static void pickSlot(Context c, Typed t, int slot, Runnable then) {
        List<Place> mine = AutomationTexts.places(c);
        List<AutomationPickers.Item> items = new ArrayList<>();
        int checked = -1;
        for (int i = 0; i < mine.size(); i++) {
            Place p = mine.get(i);
            boolean taken = t.chosen.contains(p.id) && !p.id.equals(t.chosen.get(slot));
            items.add(new AutomationPickers.Item(AutomationTexts.placeName(c, p, i), !taken));
            if (p.id.equals(t.chosen.get(slot))) {
                checked = i;
            }
        }
        AutomationPickers.list(c, c.getString(R.string.auto_place_default_name, slot + 1), items, checked, which -> {
            t.chosen.set(slot, mine.get(which).id);
            then.run();
        });
    }

    // ================================================================= 画法

    /** 「标签 + 内容」一行（同展开的规则：标签次要文字色、同一个宽度）。 */
    private static View clause(Context c, String label, String text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(4 * c.getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);
        TextView l = new TextView(c);
        l.setTextAppearance(R.style.TextAppearance_Cam_Body);
        l.setTextColor(ContextCompat.getColor(c, R.color.text_secondary));
        l.setText(label);
        // 照版面的方向对齐，不照字的方向（阿拉伯文界面里，中文、英文的地点名也靠右，同展开的规则）
        l.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                c.getResources().getDimensionPixelSize(R.dimen.pref_clause_label_width),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(16 * c.getResources().getDisplayMetrics().density));
        row.addView(l, lp);
        TextView v = new TextView(c);
        v.setTextAppearance(R.style.TextAppearance_Cam_Body);
        v.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        v.setText(text);
        v.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static LinearLayout box(Context c) {
        int pad = Math.round(24 * c.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    /** 框里的内容可以滚（车机屏不高，长的规则、键盘弹起来时放不下）。 */
    private static ScrollView scroll(Context c, View content) {
        ScrollView scroll = new ScrollView(c);
        scroll.addView(content);
        return scroll;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static TextView centered(TextView view) {
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        return view;
    }
}
