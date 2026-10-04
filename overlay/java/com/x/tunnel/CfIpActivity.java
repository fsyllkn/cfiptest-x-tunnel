package com.x.tunnel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CfIpActivity extends Activity {
    private static final String STORE = "CfIpPrefs";
    private static final String K_V4 = "UseIpv4";
    private static final String K_TLS = "UseTls";
    private static final String K_BW = "Bandwidth";
    private static final String K_MAX = "MaxResults";
    private static final String K_HISTORY = "ScanHistoryV1";
    private static final String K_TARGET_PROFILE = "TargetProfileId";

    private Preferences prefs;
    private SharedPreferences cfPrefs;
    private CfIpProcessClient client;

    private RadioButton v4;
    private RadioButton v6;
    private CheckBox tls;
    private EditText bandwidth;
    private EditText maxResults;
    private Button scan;
    private Button apply;
    private Button selectQualified;
    private Button clearSelection;
    private Button clearHistory;
    private TextView profile;
    private TextView currentIp;
    private Spinner targetProfile;
    private TextView progress;
    private LinearLayout results;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final List<Row> rows = new ArrayList<>();
    private final List<String> targetProfileIds = new ArrayList<>();

    private static final class Row {
        String ip;
        boolean qualified;
        boolean latestBatch;
        CheckBox box;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        cfPrefs = getSharedPreferences(STORE, MODE_PRIVATE);
        client = new CfIpProcessClient(this);
        setTitle("CF 优选 IP");
        setContentView(buildUi());
        restoreSettings();
        refreshProfileSelector();
        refreshHeader();
        renderHistory();
    }

    @Override protected void onResume() {
        super.onResume();
        prefs = new Preferences(this);
        if (profile != null) {
            refreshProfileSelector();
            refreshHeader();
        }
    }

    @Override protected void onDestroy() {
        client.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = bold("Cloudflare 优选 IP");
        title.setTextSize(22);
        root.addView(title, full());

        TextView note = new TextView(this);
        note.setText("开始测速前自动停止 X-Tunnel VPN；应用结果只写入当前节点，不会自动启动 VPN。测速记录会一直保留，直到手动清除。");
        note.setAlpha(0.72f);
        root.addView(note, full());

        profile = new TextView(this);
        LinearLayout.LayoutParams profileLp = full();
        profileLp.topMargin = dp(12);
        root.addView(profile, profileLp);

        TextView targetLabel = bold("应用目标节点");
        LinearLayout.LayoutParams targetLabelLp = full();
        targetLabelLp.topMargin = dp(8);
        root.addView(targetLabel, targetLabelLp);

        targetProfile = new Spinner(this);
        root.addView(targetProfile, full());

        currentIp = new TextView(this);
        currentIp.setAlpha(0.75f);
        root.addView(currentIp, full());

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        v4 = new RadioButton(this); v4.setText("IPv4");
        v6 = new RadioButton(this); v6.setText("IPv6");
        group.addView(v4); group.addView(v6);
        root.addView(group, full());

        tls = new CheckBox(this);
        tls.setText("TLS (443)");
        root.addView(tls, full());

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        left.addView(bold("目标带宽 Mbps"), full());
        right.addView(bold("候选数量 1-10"), full());
        bandwidth = numberInput();
        maxResults = numberInput();
        left.addView(bandwidth, full()); right.addView(maxResults, full());
        LinearLayout.LayoutParams halfL = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams halfR = new LinearLayout.LayoutParams(0, -2, 1f);
        halfL.rightMargin = dp(5); halfR.leftMargin = dp(5);
        inputRow.addView(left, halfL); inputRow.addView(right, halfR);
        root.addView(inputRow, full());

        scan = new Button(this); scan.setText("开始优选");
        root.addView(scan, full());

        LinearLayout maintenance = new LinearLayout(this);
        maintenance.setOrientation(LinearLayout.HORIZONTAL);
        Button update = new Button(this); update.setText("更新 CF 数据");
        Button clearCache = new Button(this); clearCache.setText("清除 CF 缓存");
        maintenance.addView(update, new LinearLayout.LayoutParams(0, -2, 1f));
        maintenance.addView(clearCache, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(maintenance, full());

        progress = new TextView(this);
        progress.setText("等待开始");
        progress.setPadding(0, dp(8), 0, dp(8));
        root.addView(progress, full());

        LinearLayout historyHead = new LinearLayout(this);
        historyHead.setOrientation(LinearLayout.HORIZONTAL);
        historyHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView historyTitle = bold("测速记录");
        historyTitle.setTextSize(18);
        historyHead.addView(historyTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        clearHistory = new Button(this); clearHistory.setText("清除测速记录");
        historyHead.addView(clearHistory, new LinearLayout.LayoutParams(-2, -2));
        root.addView(historyHead, full());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        selectQualified = new Button(this); selectQualified.setText("全选达标");
        clearSelection = new Button(this); clearSelection.setText("清空所选");
        actions.addView(selectQualified, new LinearLayout.LayoutParams(0, -2, 1f));
        actions.addView(clearSelection, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions, full());

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results, full());

        apply = new Button(this); apply.setText("应用到所选节点");
        root.addView(apply, full());

        scan.setOnClickListener(x -> { if (busy.get()) cancel(); else prepareScan(); });
        selectQualified.setOnClickListener(x -> {
            int selectedCount = 0;
            for (Row row : rows) {
                boolean choose = row.latestBatch && row.qualified;
                row.box.setChecked(choose);
                if (choose) selectedCount++;
            }
            updateControls();
            progress.setText(selectedCount > 0
                    ? "已选择最近一次测速的 " + selectedCount + " 个达标 IP"
                    : "最近一次测速中没有达到目标带宽的 IP");
        });
        clearSelection.setOnClickListener(x -> {
            for (Row row : rows) row.box.setChecked(false);
            updateControls();
        });
        clearHistory.setOnClickListener(x -> clearHistory());
        apply.setOnClickListener(x -> applySelected());
        update.setOnClickListener(x -> maintenance(true));
        clearCache.setOnClickListener(x -> maintenance(false));
        return scroll;
    }

    private EditText numberInput() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        return e;
    }

    private void restoreSettings() {
        boolean useV4 = cfPrefs.getBoolean(K_V4, true);
        v4.setChecked(useV4); v6.setChecked(!useV4);
        tls.setChecked(cfPrefs.getBoolean(K_TLS, true));
        bandwidth.setText(Integer.toString(cfPrefs.getInt(K_BW, 20)));
        maxResults.setText(Integer.toString(cfPrefs.getInt(K_MAX, 6)));
    }

    private void refreshProfileSelector() {
        if (targetProfile == null) return;

        String currentId = prefs.getCurrentProfileId();
        String remembered = cfPrefs.getString(K_TARGET_PROFILE, currentId);

        List<String> ids = new ArrayList<>(prefs.getProfileIds());
        Collections.sort(ids, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                if (a.equals(currentId) && !b.equals(currentId)) return -1;
                if (!a.equals(currentId) && b.equals(currentId)) return 1;
                return prefs.getProfileName(a).compareToIgnoreCase(prefs.getProfileName(b));
            }
        });

        targetProfileIds.clear();
        targetProfileIds.addAll(ids);

        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < targetProfileIds.size(); i++) {
            String id = targetProfileIds.get(i);
            String label = prefs.getProfileName(id);
            if (id.equals(currentId)) label += "（当前）";
            labels.add(label);
            if (id.equals(remembered)) selected = i;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetProfile.setAdapter(adapter);
        if (!labels.isEmpty()) targetProfile.setSelection(selected, false);

        targetProfile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < targetProfileIds.size()) {
                    cfPrefs.edit().putString(K_TARGET_PROFILE, targetProfileIds.get(position)).apply();
                    refreshHeader();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private String getTargetProfileId() {
        if (targetProfileIds.isEmpty()) return prefs.getCurrentProfileId();
        int pos = targetProfile == null ? -1 : targetProfile.getSelectedItemPosition();
        if (pos < 0 || pos >= targetProfileIds.size()) {
            String remembered = cfPrefs.getString(K_TARGET_PROFILE, prefs.getCurrentProfileId());
            if (targetProfileIds.contains(remembered)) return remembered;
            return prefs.getCurrentProfileId();
        }
        return targetProfileIds.get(pos);
    }

    private void refreshHeader() {
        String currentId = prefs.getCurrentProfileId();
        profile.setText("当前 X-Tunnel 节点：" + prefs.getProfileName(currentId));

        String targetId = getTargetProfileId();
        String ip = prefs.getPrefIpForProfile(targetId);
        currentIp.setText("目标节点优选 IP：" + ((ip == null || ip.trim().isEmpty()) ? "未设置" : ip));
    }

    private void prepareScan() {
        final int bw = parseInt(bandwidth.getText().toString(), 20, 1, 100000);
        final int max = parseInt(maxResults.getText().toString(), 6, 1, 10);
        final boolean useV4 = v4.isChecked();
        final boolean useTls = tls.isChecked();

        cfPrefs.edit().putBoolean(K_V4, useV4).putBoolean(K_TLS, useTls)
                .putInt(K_BW, bw).putInt(K_MAX, max).apply();
        bandwidth.setText(Integer.toString(bw));
        maxResults.setText(Integer.toString(max));

        for (Row row : rows) row.box.setChecked(false);
        prefs.setEnable(false);
        busy.set(true);
        setBusyUi(true);
        scan.setText("停止优选");
        progress.setText("正在停止 X-Tunnel VPN...");

        try {
            Intent stop = new Intent(this, TProxyService.class);
            stop.setAction(TProxyService.ACTION_DISCONNECT);
            startService(stop);
        } catch (Throwable ignored) {}

        main.postDelayed(() -> {
            if (!busy.get()) return;
            progress.setText("VPN 已停止，正在开始优选...");
            worker.execute(() -> client.scan(useV4, useTls, bw, max, new CfIpProcessClient.Listener() {
                @Override public void onProgress(String message) {
                    main.post(() -> progress.setText(message));
                }
                @Override public void onResult(String json) {
                    main.post(() -> finishScan(json, useV4, useTls, bw));
                }
                @Override public void onError(String message) {
                    main.post(() -> finishError("扫描失败: " + message));
                }
            }));
        }, 1200);
    }

    private void cancel() {
        busy.set(false);
        client.cancel();
        setBusyUi(false);
        scan.setText("开始优选");
        progress.setText("已取消；VPN 保持停止");
    }

    private void finishScan(String json, boolean useV4, boolean useTls, int bw) {
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                finishError(root.optString("error", "未找到可用 IP"));
                return;
            }
            appendHistory(root, useV4, useTls, bw);
            busy.set(false);
            renderHistory();
            setBusyUi(false);
            scan.setText("重新优选");

            int qualified = 0;
            for (int i=0; i<candidates.length(); i++) {
                JSONObject c = candidates.optJSONObject(i);
                if (c != null && c.optBoolean("qualified", false)) qualified++;
            }
            progress.setText("优选完成：" + candidates.length() + " 个候选，" + qualified
                    + " 个达标；记录已保存，VPN 保持停止。");
        } catch (Throwable t) {
            finishError("结果解析失败: " + t.getMessage());
        }
    }

    private void finishError(String message) {
        busy.set(false);
        setBusyUi(false);
        scan.setText("重新优选");
        progress.setText(message + "\nVPN 保持停止。");
    }

    private void maintenance(boolean update) {
        if (busy.get()) return;
        busy.set(true);
        setBusyUi(true);
        progress.setText(update ? "正在更新 CF 数据..." : "正在清除 CF 缓存...");
        worker.execute(() -> {
            CfIpProcessClient.Listener l = new CfIpProcessClient.Listener() {
                @Override public void onProgress(String message) {
                    main.post(() -> progress.setText(message));
                }
                @Override public void onResult(String json) {
                    main.post(() -> {
                        busy.set(false);
                        setBusyUi(false);
                        progress.setText(update ? "CF 数据更新完成" : "CF 缓存已清除");
                    });
                }
                @Override public void onError(String message) {
                    main.post(() -> finishError("操作失败: " + message));
                }
            };
            if (update) client.update(l); else client.clearCache(l);
        });
    }

    private JSONArray history() {
        String raw = cfPrefs.getString(K_HISTORY, "");
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try { return new JSONArray(raw); } catch (Throwable t) { return new JSONArray(); }
    }

    private void appendHistory(JSONObject scanResult, boolean useV4, boolean useTls, int bw) throws Exception {
        JSONObject batch = new JSONObject();
        batch.put("timestamp", System.currentTimeMillis());
        batch.put("v4", useV4);
        batch.put("tls", useTls);
        batch.put("bandwidth", bw);
        batch.put("elapsed", scanResult.optInt("elapsed", 0));
        JSONArray candidates = scanResult.optJSONArray("candidates");
        batch.put("candidates", candidates == null ? new JSONArray() : candidates);

        JSONArray old = history();
        JSONArray all = new JSONArray();
        all.put(batch);
        for (int i=0; i<old.length(); i++) all.put(old.opt(i));
        if (!cfPrefs.edit().putString(K_HISTORY, all.toString()).commit())
            throw new IllegalStateException("测速记录保存失败");
    }

    private void renderHistory() {
        rows.clear();
        results.removeAllViews();
        JSONArray h = history();
        if (h.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无测速记录");
            empty.setAlpha(0.65f);
            results.addView(empty, full());
            updateControls();
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        for (int batchIndex=0; batchIndex<h.length(); batchIndex++) {
            JSONObject batch = h.optJSONObject(batchIndex);
            if (batch == null) continue;
            long ts = batch.optLong("timestamp", 0);
            TextView head = bold((ts > 0 ? fmt.format(new Date(ts)) : "历史记录")
                    + " · " + (batch.optBoolean("v4", true) ? "IPv4" : "IPv6")
                    + " · " + (batch.optBoolean("tls", true) ? "TLS" : "HTTP")
                    + " · 目标 " + batch.optInt("bandwidth", 0) + " Mbps");
            head.setPadding(0, batchIndex == 0 ? dp(6) : dp(12), 0, dp(3));
            results.addView(head, full());

            JSONArray candidates = batch.optJSONArray("candidates");
            if (candidates == null) continue;
            for (int i=0; i<candidates.length(); i++) {
                JSONObject c = candidates.optJSONObject(i);
                if (c == null) continue;
                String ip = c.optString("ip", "").trim();
                if (ip.isEmpty()) continue;
                int targetMbps = Math.max(1, batch.optInt("bandwidth", 1));
                int realMbps = c.optInt("realBandwidth", 0);
                int maxSpeedKB = c.optInt("maxSpeed", 0);
                boolean qualified = c.optBoolean("qualified", false)
                        || realMbps >= targetMbps
                        || maxSpeedKB >= targetMbps * 128;
                CheckBox box = new CheckBox(this);
                box.setText(ip + "\n"
                        + realMbps + " Mbps  |  "
                        + maxSpeedKB + " kB/s  |  "
                        + c.optInt("latencyMs", 0) + " ms  |  "
                        + c.optString("dataCenter", "-") + "  |  "
                        + (qualified ? "达标" : "未达目标"));
                box.setOnCheckedChangeListener((b, checked) -> updateControls());
                results.addView(box, full());
                Row row = new Row();
                row.ip = ip;
                row.qualified = qualified;
                row.latestBatch = (batchIndex == 0);
                row.box = box;
                rows.add(row);
            }
        }
        updateControls();
    }

    private void clearHistory() {
        if (busy.get()) return;
        if (history().length() == 0) {
            Toast.makeText(this, "没有可清除的测速记录", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清除测速记录")
                .setMessage("确定清除全部测速历史吗？当前节点已应用的优选 IP 不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> {
                    cfPrefs.edit().remove(K_HISTORY).commit();
                    renderHistory();
                    progress.setText("测速记录已清除");
                }).show();
    }

    private void applySelected() {
        Set<String> selected = new LinkedHashSet<>();
        for (Row row : rows) if (row.box.isChecked()) selected.add(row.ip);
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一个 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        String value = TextUtils.join(",", selected);
        String targetId = getTargetProfileId();
        String targetName = prefs.getProfileName(targetId);
        prefs.setPrefIpForProfile(targetId, value);
        prefs.setEnable(false);
        refreshHeader();
        progress.setText("已写入节点「" + targetName + "」：" + value
                + "\n测速记录已保留；VPN 未启动。");
        Toast.makeText(this, "已应用到「" + targetName + "」；VPN 保持停止", Toast.LENGTH_LONG).show();
    }

    private void setBusyUi(boolean state) {
        v4.setEnabled(!state); v6.setEnabled(!state); tls.setEnabled(!state);
        bandwidth.setEnabled(!state); maxResults.setEnabled(!state);
        if (targetProfile != null) targetProfile.setEnabled(!state);
        for (Row row : rows) row.box.setEnabled(!state);
        updateControls();
    }

    private void updateControls() {
        boolean state = busy.get();
        boolean hasRows = !rows.isEmpty();
        boolean hasSelection = false;
        for (Row row : rows) if (row.box.isChecked()) { hasSelection = true; break; }

        selectQualified.setEnabled(!state && hasRows);
        clearSelection.setEnabled(!state && hasRows);
        apply.setEnabled(!state && hasSelection);
        clearHistory.setEnabled(!state && history().length() > 0);
        scan.setEnabled(true);
    }

    private int parseInt(String raw, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(raw.trim()))); }
        catch (Throwable t) { return def; }
    }

    private TextView bold(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
