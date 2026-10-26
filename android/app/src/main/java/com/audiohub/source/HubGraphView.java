package com.audiohub.source;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制界面的网络图：球 = 设备，线 = 连接，汇聚端的球更大。
 *
 * 自绘而不是用现成图表库：节点数少（个位数）、布局是固定的星形、
 * 还要响应点击——几十行 Canvas 代码比引一个图表库轻得多。
 */
public class HubGraphView extends View {

    /** 图上的一个球。 */
    public static final class Node {
        public String key;      // "hub" 或 streamId 字符串
        public String name;
        public double level = -120;
        public float x, y, r;
        public boolean hub;
    }

    public interface OnNodeTap {
        void onTap(Node node);
    }

    private final List<Node> nodes = new ArrayList<>();
    private final Paint ball = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int colorAccent = 0xFF3B82F6;
    private int colorCard = 0xFF2A3450;
    private int colorText = 0xFFEEF2F7;
    private int colorDim = 0xFF8B98A8;
    private int colorLine = 0xFF2A3348;

    private OnNodeTap tapListener;
    private String selectedKey;

    public HubGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        text.setTextAlign(Paint.Align.CENTER);
        dim.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnNodeTap(OnNodeTap l) { tapListener = l; }

    public void setSelected(String key) { selectedKey = key; invalidate(); }

    /** 传入汇聚端标签与各设备（第一层是汇聚端）。 */
    public void setData(String hubName, double hubLevel,
                        List<String> names, List<Integer> ids, List<Double> levels) {
        nodes.clear();
        Node hub = new Node();
        hub.key = "hub";
        // drawText(null) 会抛 NPE，而这里是 UI 线程 —— 直接闪退
        hub.name = hubName != null ? hubName : "汇聚端";
        hub.level = hubLevel;
        hub.hub = true;
        nodes.add(hub);

        final int n = Math.max(names.size(), 1);
        for (int i = 0; i < names.size(); ++i) {
            Node nd = new Node();
            nd.key = String.valueOf(ids.get(i));
            nd.name = names.get(i) != null ? names.get(i) : ("设备 " + ids.get(i));
            nd.level = levels.get(i);
            nd.hub = false;
            nodes.add(nd);
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (nodes.isEmpty()) return;

        final int w = getWidth(), h = getHeight();
        final float cx = w / 2f, cy = h * 0.42f;
        final float hubR = Math.min(w, h) * 0.13f;
        final float srcR = Math.min(w, h) * 0.085f;
        final float ring = Math.min(w, h) * 0.32f;

        // 布局
        final int n = Math.max(nodes.size() - 1, 1);
        for (int i = 1; i < nodes.size(); ++i) {
            final Node nd = nodes.get(i);
            final double angle = -Math.PI / 2 + (i - 1) * 2 * Math.PI / n;
            nd.x = cx + (float) (ring * Math.cos(angle));
            nd.y = cy + (float) (ring * Math.sin(angle) * 0.8);
            nd.r = srcR;
        }
        nodes.get(0).x = cx;
        nodes.get(0).y = cy;
        nodes.get(0).r = hubR;

        // 连线
        for (int i = 1; i < nodes.size(); ++i) {
            final Node nd = nodes.get(i);
            final boolean sel = nd.key.equals(selectedKey);
            line.setColor(sel ? colorAccent : colorLine);
            line.setStrokeWidth(sel ? dp(3) : dp(2));
            c.drawLine(cx, cy, nd.x, nd.y, line);
        }

        // 球
        for (final Node nd : nodes) {
            final boolean sel = nd.key.equals(selectedKey);
            if (nd.hub) {
                halo.setColor(0x2E3B82F6);
                c.drawCircle(nd.x, nd.y, nd.r + dp(9), halo);
            }
            ball.setColor(nd.hub ? colorAccent : colorCard);
            c.drawCircle(nd.x, nd.y, nd.r, ball);
            line.setColor(sel ? colorText : 0xFF55648A);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(sel ? dp(3) : dp(2));
            c.drawCircle(nd.x, nd.y, nd.r, line);
            line.setStyle(Paint.Style.FILL);

            text.setColor(colorText);
            text.setTextSize(nd.hub ? sp(15) : sp(13));
            text.setFakeBoldText(true);
            c.drawText(nd.hub ? "汇聚" : String.valueOf(nd.key), nd.x,
                    nd.y + sp(5), text);
            text.setFakeBoldText(false);

            dim.setColor(colorDim);
            dim.setTextSize(sp(12));
            c.drawText(nd.name, nd.x, nd.y + nd.r + sp(20), dim);
            dim.setTextSize(sp(11));
            dim.setColor(0xFF66718A);
            c.drawText(String.format("%.1f dBFS", nd.level),
                    nd.x, nd.y + nd.r + sp(36), dim);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            final float x = ev.getX(), y = ev.getY();
            // 命中最里层的球
            Node best = null;
            float bestDist = Float.MAX_VALUE;
            for (final Node nd : nodes) {
                final float dx = x - nd.x, dy = y - nd.y;
                final float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < nd.r + dp(12) && dist < bestDist) {
                    best = nd;
                    bestDist = dist;
                }
            }
            if (best != null && tapListener != null) {
                tapListener.onTap(best);
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
