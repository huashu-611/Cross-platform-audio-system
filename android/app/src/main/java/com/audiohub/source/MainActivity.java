package com.audiohub.source;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.util.TypedValue;

import java.util.List;

/**
 * AudioHub。
 *
 * 两屏流程：
 *   连接界面 —— 选汇聚端、连设备；连接建立后自动进控制界面
 *   控制界面 —— 可视化网络图：球 = 设备，线 = 连接，汇聚端的球更大；
 *               点任意球调它的音量，实时生效。
 *
 * 架构上两端能力完全一致：都能采音、都能放音、都能发起或接受连接。
 * 唯一的差别是用户选了哪台设备当汇聚端，默认是电脑。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_PROJECTION = 101;

    private LinearLayout screenConnect;
    private LinearLayout screenControl;

    private TextView hubValue;
    private TextView sourceState;
    private Button sourceToggle;
    private LinearLayout peerList;

    private HubGraphView graph;
    private LinearLayout volPanel;
    private TextView volName;
    private SeekBar volSlider;
    private TextView volPct;

    private final Handler poller = new Handler(Looper.getMainLooper());
    private Discovery discovery;
    private boolean inviteDialogShowing;

    /** 汇聚端：null = 本机（默认，因为电脑跑着汇聚端）；否则是那台设备。 */
    private String hubIp;
    private int hubPort;
    private String hubName;

    private static final int COLOR_BG = 0xFF0F1420;
    private static final int COLOR_CARD = 0xFF1A2130;
    private static final int COLOR_TEXT = 0xFFEEF2F7;
    private static final int COLOR_DIM = 0xFF8B98A8;
    private static final int COLOR_LINE = 0xFF2A3348;
    private static final int COLOR_ACCENT = 0xFF3B82F6;
    private static final int COLOR_WARN = 0xFFFBBF24;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences("audiohub", MODE_PRIVATE);
        hubIp = prefs.getString("hub_ip", null);
        hubPort = prefs.getInt("hub_port", 47000);
        hubName = prefs.getString("hub_name", null);

        poller.post(new Runnable() {
            @Override
            public void run() {
                refresh();
                poller.postDelayed(this, 400);
            }
        });

        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            android.net.wifi.WifiManager.MulticastLock lock =
                    wm.createMulticastLock("audiohub");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        discovery = new Discovery();
        discovery.setListener(new Discovery.Listener() {
            @Override
            public void onPeersChanged(final List<Discovery.Device> peers) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { renderPeers(peers); }
                });
            }
        });
        if (!discovery.start()) {
            Toast.makeText(this, discovery.lastError(), Toast.LENGTH_LONG).show();
        } else {
            // 进来就发一帧探测，让对端立刻回信标，不用等它下一次广播
            discovery.probe();
        }
    }

    @Override
    protected void onDestroy() {
        poller.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 控件

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView sectLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10.5f);
        tv.setLetterSpacing(0.14f);
        tv.setAllCaps(true);
        tv.setTextColor(COLOR_DIM);
        return tv;
    }

    private TextView sub(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(COLOR_DIM);
        tv.setPadding(0, dp(3), 0, 0);
        return tv;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(p);
        card.setBackgroundColor(COLOR_CARD);
        return card;
    }

    private Button textButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.getBackground().setAlpha(0);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        root.setBackgroundColor(COLOR_BG);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AudioHub");
        title.setTextSize(24);
        title.setLetterSpacing(0.02f);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView subT = new TextView(this);
        subT.setText("多台设备的声音，汇到一处");
        subT.setTextSize(12.5f);
        subT.setTextColor(COLOR_DIM);
        subT.setPadding(0, dp(4), 0, dp(14));
        root.addView(subT);

        // ================= 连接界面 =================
        screenConnect = new LinearLayout(this);
        screenConnect.setOrientation(LinearLayout.VERTICAL);

        screenConnect.addView(sectLabel("汇聚端"));
        LinearLayout hubCard = card();
        LinearLayout hubRow = new LinearLayout(this);
        hubRow.setOrientation(LinearLayout.HORIZONTAL);
        hubRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hubInfo = new LinearLayout(this);
        hubInfo.setOrientation(LinearLayout.VERTICAL);
        hubInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hubValue = new TextView(this);
        hubValue.setTextSize(15);
        hubValue.setTextColor(COLOR_TEXT);
        hubInfo.addView(hubValue);
        hubInfo.addView(sub("收到的声音从这里播出"));
        hubRow.addView(hubInfo);
        Button change = textButton("更改", COLOR_ACCENT);
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showHubPicker(); }
        });
        hubRow.addView(change);
        hubCard.addView(hubRow);
        screenConnect.addView(hubCard);

        screenConnect.addView(sectLabel("本机声音"));
        LinearLayout srcCard = card();
        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout srcInfo = new LinearLayout(this);
        srcInfo.setOrientation(LinearLayout.VERTICAL);
        srcInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView srcTitle = new TextView(this);
        srcTitle.setText("把本机的声音送进汇聚");
        srcTitle.setTextSize(14);
        srcTitle.setTextColor(COLOR_TEXT);
        srcInfo.addView(srcTitle);
        sourceState = sub("未发送");
        srcInfo.addView(sourceState);
        srcRow.addView(srcInfo);
        sourceToggle = textButton("送入", COLOR_ACCENT);
        sourceToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleSource(); }
        });
        srcRow.addView(sourceToggle);
        srcCard.addView(srcRow);
        screenConnect.addView(srcCard);

        screenConnect.addView(sectLabel("设备"));
        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        screenConnect.addView(peerList);
        root.addView(screenConnect);

        // ================= 控制界面 =================
        screenControl = new LinearLayout(this);
        screenControl.setOrientation(LinearLayout.VERTICAL);
        screenControl.setVisibility(View.GONE);

        graph = new HubGraphView(this, null);
        graph.setOnNodeTap(new HubGraphView.OnNodeTap() {
            @Override
            public void onTap(HubGraphView.Node node) {
                graph.setSelected(node.key);
                showVolumePanel(node);
            }
        });
        screenControl.addView(graph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        volPanel = new LinearLayout(this);
        volPanel.setOrientation(LinearLayout.VERTICAL);
        volPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.setMargins(0, dp(6), 0, dp(10));
        volPanel.setLayoutParams(vp);
        volPanel.setBackgroundColor(COLOR_CARD);
        volName = new TextView(this);
        volName.setTextSize(14);
        volName.setTextColor(COLOR_TEXT);
        volPanel.addView(volName);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(0, dp(8), 0, 0);
        volSlider = new SeekBar(this);
        volSlider.setMax(150);
        volSlider.setProgress(100);
        volSlider.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || selectedKey == null) return;
                final float g = progress / 100f;
                volPct.setText(Math.round(g * 100) + "%");
                applyVolume(g);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        volRow.addView(volSlider);
        volPct = new TextView(this);
        volPct.setText("100%");
        volPct.setTextSize(12);
        volPct.setTextColor(COLOR_DIM);
        volPct.setPadding(dp(10), 0, 0, 0);
        volRow.addView(volPct);
        Button mute = textButton("静音", COLOR_DIM);
        mute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { applyVolume(0f); volSlider.setProgress(0); }
        });
        volRow.addView(mute);
        volPanel.addView(volRow);
        screenControl.addView(volPanel);

        Button leave = textButton("断开所有连接", COLOR_WARN);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 接收端：清掉所有流
                startService(new Intent(MainActivity.this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_STOP));
                stopSource();
                Toast.makeText(MainActivity.this, "已断开", Toast.LENGTH_SHORT).show();
            }
        });
        screenControl.addView(leave);
        root.addView(screenControl);

        statusViewPlaceholder();
        return scroll;
    }

    private TextView statusViewPlaceholder() {
        // 占位：状态文本并入各卡片，不再单独放
        return null;
    }

    // ------------------------------------------------------------ 两屏切换

    private void showScreen(String next) {
        screenControl.setVisibility("control".equals(next) ? View.VISIBLE : View.GONE);
        screenConnect.setVisibility("control".equals(next) ? View.GONE : View.VISIBLE);
    }

    private String selectedKey;

    private void showVolumePanel(HubGraphView.Node node) {
        selectedKey = node.key;
        volName.setText(node.hub ? "汇聚端总音量" : node.name);
        volPct.setText("100%");
        volSlider.setProgress(100);
    }

    private void applyVolume(float gain) {
        if (selectedKey == null || "hub".equals(selectedKey)) return;
        try {
            ReceiverService.setStreamGain(Integer.parseInt(selectedKey), gain);
        } catch (NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------ 汇聚端

    private boolean isLocalHub() {
        return hubIp == null;
    }

    private void showHubPicker() {
        final List<Discovery.Device> peers = discovery.peers();
        final String[] names = new String[peers.size() + 1];
        names[0] = "本机（" + android.os.Build.MODEL + "）";
        final Discovery.Device[] devices = new Discovery.Device[peers.size()];
        for (int i = 0; i < peers.size(); ++i) {
            devices[i] = peers.get(i);
            names[i + 1] = devices[i].name + "（" + devices[i].ip + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("选哪台设备当汇聚端")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) setLocalHub();
                        else pickRemoteHub(devices[which - 1]);
                    }
                })
                .show();
    }

    private void setLocalHub() {
        hubIp = null;
        hubName = null;
        saveHub();
        stopSource();
        startForegroundService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_START)
                .putExtra(ReceiverService.EXTRA_PORT, 47000));
        refresh();
        Toast.makeText(this, "本机已成为汇聚端", Toast.LENGTH_SHORT).show();
    }

    private void pickRemoteHub(final Discovery.Device d) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = ReceiverService.respondToInvite(
                        d.ip, d.audioPort, android.os.Build.MODEL);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Toast.makeText(MainActivity.this, "对方未确认连接",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        hubIp = d.ip;
                        hubPort = d.audioPort;
                        hubName = d.name;
                        saveHub();
                        startService(new Intent(MainActivity.this, ReceiverService.class)
                                .setAction(ReceiverService.ACTION_STOP));
                        refresh();
                        Toast.makeText(MainActivity.this,
                                d.name + " 现在是汇聚端", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "audiohub-hubpick").start();
    }

    private void saveHub() {
        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putString("hub_ip", hubIp)
                .putInt("hub_port", hubPort)
                .putString("hub_name", hubName)
                .apply();
    }

    // ------------------------------------------------------------ 本机声音

    private void toggleSource() {
        if (CaptureService.sRunning) {
            stopSource();
        } else if (hubIp != null) {
            ensurePermissionsThenStart();
        } else {
            Toast.makeText(this, "请先选择一台远端设备当汇聚端", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void stopSource() {
        if (CaptureService.sRunning) {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP));
        }
    }

    private void ensurePermissionsThenStart() {
        List<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要录音权限才能采集音频", Toast.LENGTH_LONG).show();
            return;
        }
        requestProjection();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "没有授权，无法采集", Toast.LENGTH_SHORT).show();
            return;
        }
        final int port = hubPort != 0 ? hubPort : 47000;
        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putInt("port", port).putInt("streamId", 1).apply();

        startForegroundService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_HOST, hubIp)
                .putExtra(CaptureService.EXTRA_PORT, port)
                .putExtra(CaptureService.EXTRA_STREAM_ID, 1));
    }

    // ------------------------------------------------------------ 刷新

    private void refresh() {
        // 汇聚端显示
        hubValue.setText(isLocalHub() ? "本机" : (hubName != null ? hubName : hubIp));

        // 本机声音
        final boolean srcRunning = CaptureService.sRunning;
        sourceToggle.setText(srcRunning ? "停止" : "送入");
        sourceState.setText(srcRunning
                ? ("发送中 · " + CaptureService.sPackets + " 包 · "
                   + CaptureService.sLevelDbfs + " dBFS")
                : (isLocalHub() ? "本机是汇聚端" : "未发送"));

        // 屏幕自动切换：有已确认的连接 → 控制界面；没有 → 连接界面
        final boolean hubRunning = ReceiverService.sRunning;
        final boolean anyConnection = (hubRunning && ReceiverService.sStreamCount > 0)
                || srcRunning;
        showScreen(anyConnection ? "control" : "connect");

        // 网络图数据
        if (hubRunning) {
            List<Integer> ids = new java.util.ArrayList<>();
            List<String> names = new java.util.ArrayList<>();
            List<Double> levels = new java.util.ArrayList<>();
            // ReceiverService 目前只暴露流数；球按流编号铺开，名字用编号
            for (int i = 1; i <= ReceiverService.sStreamCount; ++i) {
                ids.add(i);
                names.add("设备 " + i);
                levels.add(ReceiverService.sBusLevel);
            }
            graph.setData(isLocalHub() ? "本机" : (hubName != null ? hubName : hubIp),
                    ReceiverService.sBusLevel, names, ids, levels);
        }

        // PC 发来的邀请：弹窗确认
        if (ReceiverService.sInvitePending && !inviteDialogShowing) {
            inviteDialogShowing = true;
            ReceiverService.sInvitePending = false;
            final String name = ReceiverService.sInviteName;
            new AlertDialog.Builder(this)
                    .setTitle("连接请求")
                    .setMessage(name + " 请求把声音送到这里")
                    .setPositiveButton("同意", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            // 同意后对方开始发送；汇聚端已就绪，无需额外动作
                            Toast.makeText(MainActivity.this, "已同意", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("拒绝", null)
                    .show();
        }
    }

    private void renderPeers(List<Discovery.Device> peers) {
        peerList.removeAllViews();
        if (peers.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("正在扫描附近设备…");
            tv.setTextSize(12.5f);
            tv.setTextColor(COLOR_DIM);
            tv.setPadding(dp(2), dp(6), 0, dp(6));
            peerList.addView(tv);
            return;
        }
        for (final Discovery.Device d : peers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rp);
            row.setBackgroundColor(COLOR_CARD);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(this);
            name.setText(d.name + "  ·  " + d.roleText());
            name.setTextSize(13.5f);
            name.setTextColor(COLOR_TEXT);
            info.addView(name);
            TextView addr = new TextView(this);
            addr.setText(d.ip + ":" + d.audioPort);
            addr.setTextSize(11.5f);
            addr.setTextColor(COLOR_DIM);
            info.addView(addr);
            row.addView(info);

            Button invite = textButton("邀请", COLOR_ACCENT);
            invite.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { invitePeer(d); }
            });
            row.addView(invite);

            Button connect = textButton("连入", COLOR_ACCENT);
            connect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { pickRemoteHub(d); }
            });
            row.addView(connect);
            peerList.addView(row);
        }
    }

    private void invitePeer(final Discovery.Device d) {
        if (!isLocalHub() || !ReceiverService.sRunning) {
            Toast.makeText(this, "只有汇聚端能邀请别人，请先把汇聚端设为本机",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.DatagramSocket s = new java.net.DatagramSocket();
                    String invite = "CONN-INVITE|AudioHub 手机|" + localIp() + ":47000\n";
                    byte[] data = invite.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    s.send(new java.net.DatagramPacket(data, data.length,
                            java.net.InetAddress.getByName(d.ip), d.audioPort + 2));
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }, "audiohub-invite").start();
        Toast.makeText(this, "已向 " + d.name + " 发出邀请", Toast.LENGTH_SHORT).show();
    }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.util.Enumeration<java.net.InetAddress> addrs =
                        nis.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
