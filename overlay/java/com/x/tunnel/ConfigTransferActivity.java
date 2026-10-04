package com.x.tunnel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ConfigTransferActivity extends Activity {
    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final String SOCKS_PREFS = "SocksPrefs";
    private static final String CF_PREFS = "CfIpPrefs";
    private static final String ENABLE_KEY = "Enable";
    private static final int CONFIG_VERSION = 2;

    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("配置导入 / 导出");
        setContentView(buildView());
    }

    private LinearLayout buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("X-Tunnel 完整配置备份");
        title.setTextSize(22);
        root.addView(title, fullWidthWrap());

        TextView note = new TextView(this);
        note.setText("导出会保存应用内全部 SharedPreferences 配置，包括所有节点、当前节点、分流设置、CF 优选参数和测速历史。导入前自动备份当前配置；导入后 VPN 保持停止。CF 下载缓存不属于配置。 ");
        note.setAlpha(0.72f);
        LinearLayout.LayoutParams noteLp = fullWidthWrap();
        noteLp.topMargin = dp(8);
        root.addView(note, noteLp);

        Button export = new Button(this);
        export.setText("导出全部配置 JSON");
        LinearLayout.LayoutParams eLp = fullWidthWrap();
        eLp.topMargin = dp(18);
        root.addView(export, eLp);

        Button importButton = new Button(this);
        importButton.setText("导入完整配置 JSON");
        root.addView(importButton, fullWidthWrap());

        status = new TextView(this);
        status.setGravity(Gravity.START);
        status.setPadding(0, dp(14), 0, 0);
        status.setText("等待操作");
        root.addView(status, fullWidthWrap());

        export.setOnClickListener(v -> createExportDocument());
        importButton.setOnClickListener(v -> openImportDocument());
        return root;
    }

    private void createExportDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "x-tunnel-full-config-" + stamp + ".json");
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void openImportDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_EXPORT) doExport(data.getData());
        else if (requestCode == REQ_IMPORT) doImport(data.getData());
    }

    private void doExport(Uri uri) {
        try {
            String json = buildCompleteConfigJson();
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new IllegalStateException("无法打开导出文件");
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            status.setText("全部配置导出成功");
            Toast.makeText(this, "全部配置导出成功", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            status.setText("导出失败: " + t.getMessage());
        }
    }

    private void doImport(Uri uri) {
        Preferences prefs = new Preferences(this);
        prefs.setEnable(false);
        try {
            Intent stop = new Intent(this, TProxyService.class);
            stop.setAction(TProxyService.ACTION_DISCONNECT);
            startService(stop);
        } catch (Throwable ignored) {}

        try {
            String raw;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IllegalStateException("无法读取导入文件");
                raw = readAll(is);
            }
            JSONObject root = new JSONObject(raw);
            validateRoot(root);
            backupCurrentConfig();

            JSONObject stores = root.getJSONObject("stores");
            JSONArray names = stores.names();
            int count = 0;
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    validateStoreName(name);
                    JSONObject dataObj = stores.optJSONObject(name);
                    if (dataObj == null) continue;
                    importStore(name, dataObj);
                    count++;
                }
            }
            getSharedPreferences(SOCKS_PREFS, MODE_MULTI_PROCESS)
                    .edit().putBoolean(ENABLE_KEY, false).commit();
            status.setText("完整配置导入成功（" + count + " 个配置仓库）；VPN 保持停止");
            Toast.makeText(this, "全部配置已恢复；VPN 未启动", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            status.setText("导入失败: " + t.getMessage());
        }
    }

    private String buildCompleteConfigJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "x-tunnel-config");
        root.put("version", CONFIG_VERSION);
        root.put("package", getPackageName());
        root.put("exportedAt", System.currentTimeMillis());

        JSONObject stores = new JSONObject();
        for (String name : getAllPreferenceStoreNames()) {
            boolean runtime = SOCKS_PREFS.equals(name);
            SharedPreferences sp = getSharedPreferences(name, runtime ? MODE_MULTI_PROCESS : MODE_PRIVATE);
            stores.put(name, exportStore(sp, runtime));
        }
        root.put("stores", stores);
        root.put("storeCount", stores.length());
        return root.toString(2);
    }

    private Set<String> getAllPreferenceStoreNames() {
        Set<String> names = new TreeSet<>();
        names.add(SOCKS_PREFS);
        names.add(CF_PREFS);
        try {
            File dir = new File(getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (f.isFile() && n.endsWith(".xml")) {
                        String store = n.substring(0, n.length() - 4);
                        if (isValidStoreName(store)) names.add(store);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return names;
    }

    private JSONObject exportStore(SharedPreferences sp, boolean forceDisabled) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (forceDisabled && ENABLE_KEY.equals(key)) value = false;

            JSONObject item = new JSONObject();
            if (value instanceof String) {
                item.put("type", "string").put("value", value);
            } else if (value instanceof Boolean) {
                item.put("type", "boolean").put("value", value);
            } else if (value instanceof Integer) {
                item.put("type", "int").put("value", value);
            } else if (value instanceof Long) {
                item.put("type", "long").put("value", value);
            } else if (value instanceof Float) {
                item.put("type", "float").put("value", ((Float) value).doubleValue());
            } else if (value instanceof Set) {
                item.put("type", "stringSet");
                JSONArray arr = new JSONArray();
                for (Object s : (Set<?>) value) arr.put(String.valueOf(s));
                item.put("value", arr);
            } else {
                continue;
            }
            out.put(key, item);
        }
        return out;
    }

    private void importStore(String name, JSONObject data) throws Exception {
        validateStoreName(name);
        boolean runtime = SOCKS_PREFS.equals(name);
        SharedPreferences sp = getSharedPreferences(name, runtime ? MODE_MULTI_PROCESS : MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit().clear();
        JSONArray keys = data.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.getString(i);
                JSONObject item = data.optJSONObject(key);
                if (item == null) continue;
                String type = item.optString("type", "");
                if ("string".equals(type)) ed.putString(key, item.optString("value", ""));
                else if ("boolean".equals(type)) ed.putBoolean(key, item.optBoolean("value", false));
                else if ("int".equals(type)) ed.putInt(key, item.optInt("value", 0));
                else if ("long".equals(type)) ed.putLong(key, item.optLong("value", 0));
                else if ("float".equals(type)) ed.putFloat(key, (float) item.optDouble("value", 0));
                else if ("stringSet".equals(type)) {
                    JSONArray arr = item.optJSONArray("value");
                    Set<String> set = new HashSet<>();
                    if (arr != null) for (int j = 0; j < arr.length(); j++) {
                        String v = arr.optString(j, "");
                        if (!v.isEmpty()) set.add(v);
                    }
                    ed.putStringSet(key, set);
                }
            }
        }
        if (runtime) ed.putBoolean(ENABLE_KEY, false);
        if (!ed.commit()) throw new IllegalStateException("写入 " + name + " 失败");
    }

    private void validateRoot(JSONObject root) {
        if (!"x-tunnel-config".equals(root.optString("format")))
            throw new IllegalArgumentException("不是 X-Tunnel 配置文件");
        int v = root.optInt("version", 0);
        if (v != 1 && v != CONFIG_VERSION)
            throw new IllegalArgumentException("不支持的配置版本: " + v);
        if (root.optJSONObject("stores") == null)
            throw new IllegalArgumentException("配置内容缺失");
    }

    private boolean isValidStoreName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private void validateStoreName(String name) {
        if (!isValidStoreName(name)) throw new IllegalArgumentException("非法配置仓库名称: " + name);
    }

    private void backupCurrentConfig() {
        try {
            File dir = new File(getFilesDir(), "config-backups");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "before-import-" + stamp + ".json"))) {
                fos.write(buildCompleteConfigJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
            if (out.size() > 32 * 1024 * 1024) throw new IllegalArgumentException("配置文件过大");
        }
        return out.toString("UTF-8");
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
