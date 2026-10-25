package com.audiohub.source;

import android.Manifest;
import android.app.Activity;
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
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简界面：填地址 → 开始 → 看状态。
 *
 * 故意不用 AndroidX：这个应用只依赖平台 API，好处是构建时不需要
 * 从网络拉任何第三方库（手工用 aapt2/d8 就能打包，也方便在没有
 * Android Studio 的机器上编译）。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_PROJECTION = 101;

    private EditText hostField;
    private EditText portField;
    private EditText streamField;
    private Button actionButton;
    private TextView statusView;

    private final Handler poller = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences("audiohub", MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "192.168.1.100"));
        portField.setText(String.valueOf(prefs.getInt("port", 47000)));
        streamField.setText(String.valueOf(prefs.getInt("streamId", 1)));

        // 同理：不用 lambda，理由见 CaptureService
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            if (CaptureService.sRunning) {
                // 匿名内部类里的 this 是监听器自己，不是 Activity
                Intent stop = new Intent(MainActivity.this, CaptureService.class)
                        .setAction(CaptureService.ACTION_STOP);
                startService(stop);
                actionButton.setText("开始采集");
                statusView.setText("已停止");
            } else {
                ensurePermissionsThenStart();
            }
            }
        });

        poller.post(new Runnable() {
            @Override
            public void run() {
                updateStatus();
                poller.postDelayed(this, 400);
            }
        });
    }

    @Override
    protected void onDestroy() {
        poller.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------ 界面

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("AudioHub 源端");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(13, 106, 107));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("把本机正在播放的声音送到 PC 的汇聚端。\n"
                + "需要授权「屏幕录制」——Android 的音频采集挂在 MediaProjection 上，"
                + "但不会真的录制画面。");
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(90, 100, 110));
        hint.setPadding(0, dp(8), 0, dp(20));
        root.addView(hint);

        hostField = addField(root, "汇聚端地址", "192.168.1.100", InputType.TYPE_CLASS_TEXT);
        portField = addField(root, "端口", "47000", InputType.TYPE_CLASS_NUMBER);
        streamField = addField(root, "流编号（多台设备用不同编号）", "1", InputType.TYPE_CLASS_NUMBER);

        actionButton = new Button(this);
        actionButton.setText("开始采集");
        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(12);
        actionButton.setLayoutParams(buttonParams);
        root.addView(actionButton);

        statusView = new TextView(this);
        statusView.setText("未启动");
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(20), 0, 0);
        statusView.setGravity(Gravity.START);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private EditText addField(LinearLayout parent, String label, String def, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(Color.rgb(60, 70, 80));
        tv.setPadding(0, dp(12), 0, dp(4));
        parent.addView(tv);

        EditText field = new EditText(this);
        field.setText(def);
        field.setInputType(inputType);
        field.setTextSize(16);
        parent.addView(field);
        return field;
    }

    private void updateStatus() {
        boolean running = CaptureService.sRunning;
        actionButton.setText(running ? "停止采集" : "开始采集");

        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(CaptureService.sStatus).append('\n');
        if (running) {
            sb.append("已发送：").append(CaptureService.sPackets).append(" 包\n");
            sb.append("电平：").append(CaptureService.sLevelDbfs).append(" dBFS\n");
            sb.append(String.format("码率：%.0f kbps", CaptureService.sKbps));
        }
        statusView.setText(sb.toString());
    }

    // ------------------------------------------------------------ 流程

    private void ensurePermissionsThenStart() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) return;

        // 录音权限是硬性的：没有它连 AudioRecord 都建不起来
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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

        final String host = hostField.getText().toString().trim();
        final int port = parseInt(portField.getText().toString(), 47000);
        final int streamId = parseInt(streamField.getText().toString(), 1);

        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putString("host", host)
                .putInt("port", port)
                .putInt("streamId", streamId)
                .apply();

        Intent service = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_HOST, host)
                .putExtra(CaptureService.EXTRA_PORT, port)
                .putExtra(CaptureService.EXTRA_STREAM_ID, streamId);

        // minSdk 29，所以总是走 startForegroundService
        startForegroundService(service);
        statusView.setText("正在启动…");
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
