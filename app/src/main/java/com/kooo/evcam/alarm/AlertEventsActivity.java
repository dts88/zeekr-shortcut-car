/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「车能发给机器人的每一条消息」：一行一条，写清楚真的什么时候会发，点一下就发一条真的。
 *
 * <h3>为什么值得单独一屏</h3>
 *
 * <p>车主配好令牌之后第一个问题是「它到底会给我发什么」。一张测试照片回答不了：双闪那一条长什么样、
 * 忘了开哨兵那一句问长什么样、开了之后那张回执长什么样，都还没见过。这一屏把每一条摆出来，
 * 各自的描述写清楚触发条件，点一条就发一条 —— 发的是真消息、真按钮，只是触发器由人点。</p>
 *
 * <h3>Every message, one row, with what it means</h3>
 *
 * <p>The first question after configuring a token is "what will it actually send me", and one test picture
 * cannot answer it. Each row here names one message, says when the car really sends it, and sends that
 * exact message on a tap — real wording, real buttons, only the trigger is a finger.</p>
 */
public class AlertEventsActivity extends AppCompatActivity {

    private static final String KEY_TITLE = "title";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_EVENT = "event";

    private final List<Map<String, Object>> rows = new ArrayList<>();
    private SimpleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.alerts_events_title);
        setContentView(R.layout.activity_alert_events);
        // 左上角那个返回箭头：这一屏只有一个入口，回得去设置才顺手（车主 2026-11-15）。
        // The up arrow: this screen has one entry, and going back to settings has to be one tap.
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        for (AlertEvent event : AlertEvent.values()) {
            Map<String, Object> row = new HashMap<>();
            row.put(KEY_TITLE, getString(event.titleRes));
            row.put(KEY_DESCRIPTION, getString(event.descriptionRes));
            row.put(KEY_EVENT, event);
            rows.add(row);
        }
        adapter = new SimpleAdapter(this, rows, android.R.layout.simple_list_item_2,
                new String[] {KEY_TITLE, KEY_DESCRIPTION},
                new int[] {android.R.id.text1, android.R.id.text2});
        ListView list = findViewById(R.id.alert_events_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> trigger(rows.get(position)));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void trigger(Map<String, Object> row) {
        Object value = row.get(KEY_EVENT);
        if (!(value instanceof AlertEvent)) {
            return;
        }
        AlertEvent event = (AlertEvent) value;
        Toast.makeText(this, getString(R.string.alert_event_sending,
                getString(event.titleRes)), Toast.LENGTH_SHORT).show();
        SentryAlerts.get(this).sendEventSample(event, (ok, message) -> {
            if (!isFinishing()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
