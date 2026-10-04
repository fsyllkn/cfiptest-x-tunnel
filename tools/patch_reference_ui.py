#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: patch_reference_ui.py <x-tunnel-repo-root>')

root = Path(sys.argv[1]).resolve()
app = root / 'x-tunnel-android-gui-src' / 'src' / 'main'


def replace_once(path: Path, old: str, new: str):
    data = path.read_text(encoding='utf-8')
    count = data.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected marker once, got {count}: {old[:120]!r}')
    path.write_text(data.replace(old, new, 1), encoding='utf-8')

manifest = app / 'AndroidManifest.xml'
replace_once(
    manifest,
    '''	<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
		android:minSdkVersion="34" />
''',
    '''	<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
''',
)
replace_once(
    manifest,
    '''		<activity android:name=".AppListActivity" android:label="@string/app_name"/>
''',
    '''		<activity android:name=".AppListActivity" android:label="@string/app_name"/>
		<activity android:name=".CfIpActivity" android:label="CF 优选 IP" android:exported="false"/>
		<activity android:name=".ConfigTransferActivity" android:label="配置导入 / 导出" android:exported="false"/>
''',
)

svc = app / 'java/com/x/tunnel/TProxyService.java'
replace_once(
    svc,
    '''                        String wsAddr = prefs.getWssAddr().trim();
                        if (!wsAddr.startsWith("wss://")) {
                                wsAddr = "wss://" + wsAddr;
                        }
''',
    '''                        String wsAddr = prefs.getWssAddr().trim();
                        String lowerWsAddr = wsAddr.toLowerCase();
                        if (!lowerWsAddr.startsWith("wss://") && !lowerWsAddr.startsWith("ws://")) {
                                wsAddr = "wss://" + wsAddr;
                        }
''',
)

prefs = app / 'java/com/x/tunnel/Preferences.java'
replace_once(
    prefs,
    '''        public void removeProfile(String id) {
''',
    '''        public void copyProfile(String fromId, String toId) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(WSS_ADDR + "_" + toId, prefs.getString(WSS_ADDR + "_" + fromId, ""));
            editor.putString(ECH_DNS + "_" + toId, prefs.getString(ECH_DNS + "_" + fromId, "https://doh.pub/dns-query"));
            editor.putString(ECH_DOMAIN + "_" + toId, prefs.getString(ECH_DOMAIN + "_" + fromId, "cloudflare-ech.com"));
            editor.putString(PREF_IP + "_" + toId, prefs.getString(PREF_IP + "_" + fromId, ""));
            editor.putString(IPS_PREF + "_" + toId, prefs.getString(IPS_PREF + "_" + fromId, ""));
            editor.putString(UDP_BLOCK_PORTS + "_" + toId, prefs.getString(UDP_BLOCK_PORTS + "_" + fromId, "443"));
            editor.putBoolean(INSECURE + "_" + toId, prefs.getBoolean(INSECURE + "_" + fromId, false));
            editor.putString(TOKEN + "_" + toId, prefs.getString(TOKEN + "_" + fromId, ""));
            editor.putInt(WS_CONN + "_" + toId, prefs.getInt(WS_CONN + "_" + fromId, 3));
            editor.putBoolean(DISABLE_ECH + "_" + toId, prefs.getBoolean(DISABLE_ECH + "_" + fromId, false));
            editor.commit();
        }

        public void removeProfile(String id) {
''',
)

main = app / 'java/com/x/tunnel/MainActivity.java'
replace_once(
    main,
    '''    private Button btn_add_profile;
    private Button btn_save_profile;
    private Button btn_rename_profile;
''',
    '''    private Button btn_add_profile;
    private Button btn_save_profile;
    private Button btn_copy_profile;
    private Button btn_rename_profile;
''',
)
replace_once(
    main,
    '''    private Button button_apps;
    private Button button_control;
''',
    '''    private Button button_apps;
    private Button button_control;
    private Button button_cf_optimize;
    private Button button_config_transfer;
''',
)
replace_once(
    main,
    '''        btn_add_profile = (Button) findViewById(R.id.btn_add_profile);
        btn_save_profile = (Button) findViewById(R.id.btn_save_profile);
        btn_rename_profile = (Button) findViewById(R.id.btn_rename_profile);
''',
    '''        btn_add_profile = (Button) findViewById(R.id.btn_add_profile);
        btn_save_profile = (Button) findViewById(R.id.btn_save_profile);
        btn_copy_profile = (Button) findViewById(R.id.btn_copy_profile);
        btn_rename_profile = (Button) findViewById(R.id.btn_rename_profile);
''',
)
replace_once(
    main,
    '''        button_apps = (Button) findViewById(R.id.apps);
        button_control = (Button) findViewById(R.id.control);
''',
    '''        button_apps = (Button) findViewById(R.id.apps);
        button_control = (Button) findViewById(R.id.control);
        button_cf_optimize = (Button) findViewById(R.id.cf_optimize);
        button_config_transfer = (Button) findViewById(R.id.config_transfer);
''',
)
replace_once(
    main,
    '''        btn_add_profile.setOnClickListener(this);
        btn_save_profile.setOnClickListener(this);
        btn_rename_profile.setOnClickListener(this);
''',
    '''        btn_add_profile.setOnClickListener(this);
        btn_save_profile.setOnClickListener(this);
        btn_copy_profile.setOnClickListener(this);
        btn_rename_profile.setOnClickListener(this);
''',
)
replace_once(
    main,
    '''        button_apps.setOnClickListener(this);
        button_control.setOnClickListener(this);
''',
    '''        button_apps.setOnClickListener(this);
        button_control.setOnClickListener(this);
        button_cf_optimize.setOnClickListener(this);
        button_config_transfer.setOnClickListener(this);
''',
)
replace_once(
    main,
    '''    private void showRenameProfileDialog() {
''',
    '''    private String buildCopyName(String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (true) {
            boolean exists = false;
            for (String id : prefs.getProfileIds()) {
                if (prefs.getProfileName(id).equals(candidate)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) return candidate;
            candidate = baseName + suffix;
            suffix++;
        }
    }

    private void copyCurrentProfile() {
        final String currentId = prefs.getCurrentProfileId();
        String currentName = prefs.getProfileName(currentId);
        final EditText input = new EditText(this);
        input.setText(buildCopyName(currentName + "_副本"));
        new AlertDialog.Builder(this)
            .setTitle(R.string.btn_copy)
            .setView(input)
            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(MainActivity.this, R.string.toast_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    for (String id : prefs.getProfileIds()) {
                        if (prefs.getProfileName(id).equals(name)) {
                            Toast.makeText(MainActivity.this, R.string.toast_profile_exists, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    String newId = UUID.randomUUID().toString();
                    savePrefs();
                    prefs.addProfile(newId, name);
                    prefs.copyProfile(currentId, newId);
                    prefs.setCurrentProfileId(newId);
                    updateUI();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showRenameProfileDialog() {
''',
)
replace_once(
    main,
    '''        } else if (view == btn_rename_profile) {
            showRenameProfileDialog();
''',
    '''        } else if (view == btn_copy_profile) {
            if (prefs.getEnable()) return;
            copyCurrentProfile();
        } else if (view == btn_rename_profile) {
            showRenameProfileDialog();
''',
)
replace_once(
    main,
    '''        } else if (view == button_apps) {
            startActivity(new Intent(this, AppListActivity.class));
''',
    '''        } else if (view == button_cf_optimize) {
            startActivity(new Intent(this, CfIpActivity.class));
        } else if (view == button_config_transfer) {
            startActivity(new Intent(this, ConfigTransferActivity.class));
        } else if (view == button_apps) {
            startActivity(new Intent(this, AppListActivity.class));
''',
)
replace_once(
    main,
    '''        btn_add_profile.setEnabled(editable);
        btn_save_profile.setEnabled(editable);
        btn_rename_profile.setEnabled(editable);
''',
    '''        btn_add_profile.setEnabled(editable);
        btn_save_profile.setEnabled(editable);
        btn_copy_profile.setEnabled(editable);
        btn_rename_profile.setEnabled(editable);
''',
)
replace_once(
    main,
    '''        btn_add_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF4CAF50 : grey));
        btn_save_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF2196F3 : grey));
        btn_rename_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFFFF9800 : grey));
''',
    '''        btn_add_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF4CAF50 : grey));
        btn_save_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF2196F3 : grey));
        btn_copy_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF008896 : grey));
        btn_rename_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFFFF9800 : grey));
''',
)
replace_once(
    main,
    '''    private int getIpsPrefSelection(String v) {
''',
    '''    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null) {
            prefs = new Preferences(this);
            updateUI();
        }
    }

    private int getIpsPrefSelection(String v) {
''',
)

layout = app / 'res/layout/main.xml'
replace_once(
    layout,
    '''            <Button
                android:id="@+id/btn_rename_profile"
''',
    '''            <Button
                android:id="@+id/btn_copy_profile"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/btn_copy"
                android:textColor="#FFFFFF"
                android:backgroundTint="#008896"
                android:textSize="12sp"
                android:layout_marginEnd="2dp"/>
            <Button
                android:id="@+id/btn_rename_profile"
''',
)
replace_once(
    layout,
    '''    <!-- Bottom Fixed Controls -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="8dp">
        
        <LinearLayout
''',
    '''    <!-- Bottom Fixed Controls -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="8dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingBottom="6dp">
            <Button
                android:id="@+id/cf_optimize"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/cf_optimize"
                android:textColor="#FFFFFF"
                android:backgroundTint="#1565C0"
                android:layout_marginEnd="4dp" />
            <Button
                android:id="@+id/config_transfer"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/config_transfer"
                android:textColor="#FFFFFF"
                android:backgroundTint="#546E7A" />
        </LinearLayout>
        
        <LinearLayout
''',
)

strings = app / 'res/values/strings.xml'
replace_once(
    strings,
    '''        <string name="btn_add">添加</string>
''',
    '''        <string name="btn_add">添加</string>
        <string name="btn_copy">复制</string>
''',
)
replace_once(
    strings,
    '''        <string name="control_disable">停止</string>
''',
    '''        <string name="control_disable">停止</string>
        <string name="cf_optimize">CF 优选 IP</string>
        <string name="config_transfer">配置导入/导出</string>
''',
)

print('patched reference-APK UI shell:', root)
