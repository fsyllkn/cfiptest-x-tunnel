package com.x.tunnel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

public class NetworkPolicyActivity extends Activity {
    private Preferences prefs;
    private RadioButton standard;
    private RadioButton aiStable;
    private RadioButton webrtcCompat;
    private RadioButton webrtcStrict;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        setTitle("网络策略");
        setContentView(buildUi());
        load();
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("X-Tunnel 网络策略");
        title.setTextSize(22);
        root.addView(title, full());

        TextView note = new TextView(this);
        note.setText("所有进入 VPN 的应用仍然只使用当前一个 X-Tunnel 出站。这里仅控制 DNS/IP版本/UDP/WebRTC 处理，不做节点分组或域名分流。");
        note.setAlpha(0.72f);
        LinearLayout.LayoutParams nlp = full();
        nlp.topMargin = dp(8);
        root.addView(note, nlp);

        TextView modeTitle = new TextView(this);
        modeTitle.setText("网络模式");
        modeTitle.setTextSize(18);
        LinearLayout.LayoutParams mtp = full();
        mtp.topMargin = dp(18);
        root.addView(modeTitle, mtp);

        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        standard = new RadioButton(this);
        standard.setText("标准模式");
        aiStable = new RadioButton(this);
        aiStable.setText("AI 稳定模式");
        modeGroup.addView(standard);
        modeGroup.addView(aiStable);
        root.addView(modeGroup, full());

        TextView aiDesc = new TextView(this);
        aiDesc.setText("AI 稳定模式：保持远程 DNS，运行时强制 IPv4 only，并阻止 QUIC/UDP 443；IPv6 仍由 VPN 捕获，不允许绕过系统 VPN。");
        aiDesc.setAlpha(0.68f);
        root.addView(aiDesc, full());

        TextView wrTitle = new TextView(this);
        wrTitle.setText("WebRTC");
        wrTitle.setTextSize(18);
        LinearLayout.LayoutParams wtp = full();
        wtp.topMargin = dp(18);
        root.addView(wrTitle, wtp);

        RadioGroup wrGroup = new RadioGroup(this);
        wrGroup.setOrientation(RadioGroup.VERTICAL);
        webrtcCompat = new RadioButton(this);
        webrtcCompat.setText("兼容模式（推荐）");
        webrtcStrict = new RadioButton(this);
        webrtcStrict.setText("严格保护");
        wrGroup.addView(webrtcCompat);
        wrGroup.addView(webrtcStrict);
        root.addView(wrGroup, full());

        TextView wrDesc = new TextView(this);
        wrDesc.setText("兼容模式仅额外阻止 UDP/443。严格保护还会阻止常见 STUN/TURN UDP 端口，可能影响语音、视频和实时通信。");
        wrDesc.setAlpha(0.68f);
        root.addView(wrDesc, full());

        status = new TextView(this);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status, full());

        modeGroup.setOnCheckedChangeListener((g, id) -> save());
        wrGroup.setOnCheckedChangeListener((g, id) -> save());

        return root;
    }

    private void load() {
        if (prefs.getNetworkMode() == Preferences.NETWORK_AI_STABLE) {
            aiStable.setChecked(true);
        } else {
            standard.setChecked(true);
        }

        if (prefs.getWebRtcMode() == Preferences.WEBRTC_STRICT) {
            webrtcStrict.setChecked(true);
        } else {
            webrtcCompat.setChecked(true);
        }
        updateStatus();
    }

    private void save() {
        if (standard == null || aiStable == null || webrtcCompat == null || webrtcStrict == null) return;
        prefs.setNetworkMode(aiStable.isChecked()
                ? Preferences.NETWORK_AI_STABLE
                : Preferences.NETWORK_STANDARD);
        prefs.setWebRtcMode(webrtcStrict.isChecked()
                ? Preferences.WEBRTC_STRICT
                : Preferences.WEBRTC_COMPAT);
        updateStatus();
    }

    private void updateStatus() {
        String mode = prefs.getNetworkMode() == Preferences.NETWORK_AI_STABLE
                ? "AI 稳定"
                : "标准";
        String wr = prefs.getWebRtcMode() == Preferences.WEBRTC_STRICT
                ? "严格保护"
                : "兼容";
        status.setText("当前："
                + mode
                + " / WebRTC "
                + wr
                + "\n运行时 IP 策略："
                + prefs.getRuntimeIpsPref()
                + "\n运行时 UDP 屏蔽端口："
                + prefs.getRuntimeUdpBlockPorts()
                + "\n远程 DNS：启用");
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
