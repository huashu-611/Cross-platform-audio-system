package com.audiohub.source;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 俯视平面图（top-down floor plan）。v1.5 起手机端不再做 3D 透视，改为「从上方俯瞰」的只读图谱：
 *
 *   · 正交映射：1 米 = ppu 像素。世界坐标 (wx, wz) 米 →
 *     {@code sx = cx + wx * ppu}、{@code sy = cy + wz * ppu}；
 *     屏幕上方 = -Z = 房间正前方，屏幕右 = +X = 右耳方向。
 *   · 视野自适应（v1.6）：遍历所有节点的 wx/wz，算出让「最远节点 + 球半径 + 标签」也落在
 *     画布内的 ppu（四边各留约 12% 边距）：近处不小于 2.6 m 视野，远处最多缩到 640 m 视野
 *     （±320 m 坐标上限的对角也装得下）。等比缩放、居中，绝不会像旧的 920x460 虚拟画布
 *     那样被非等比拉伸成椭圆。
 *   · 环按真实比例（半径米数 × ppu）：缩放小到环挤成一团时自动省略环/方位字，只留比例尺。
 *   · 完全只读：没有轨道旋转、没有捏合缩放、没有拖动节点、没有长按取消 pin。
 *     保留点击（选中节点/音量面板/切换汇聚端确认）与双击（切汇聚端）两个入口。
 *   · 位置权威来自 hub 同步的 POS 帧（{@link PlacementStore}）；没有人工摆位的节点由
 *     MainActivity 用 Scene3D 的确定性自动槽位兜底，本类只负责画。
 *
 * 教训：drawText 收到 null 会直接 NPE 崩在 onDraw（UI 线程），所以所有文本都经过
 * displayName()/displayText()/clampText() 兜底。
 */
public class HubGraphView extends View {

    /** 图谱里的一个球。MainActivity 每次 refresh 重新构造列表（不可变快照）。 */
    public static final class Node {
        public String id = "";          // 节点 id "ip:audioPort"
        public String name = "";        // 显示名（可能为空 → 退回 id）
        public String kind = "other";   // pc | android | other
        public boolean hub;             // 是不是汇聚端
        public boolean self;            // 是不是本机
        public boolean online = true;   // 10s 内有过数据报
        public double level = -120;     // 该节点音频流电平（dBFS）
        public int streamId = 0;        // 0 = 没有音频流

        // ---- 俯视平面图的世界坐标（米）----
        /** 世界坐标 x（米，右正）。 */
        public double wx = 0;
        /** 世界坐标 z（米，屏幕下方正 / 正前方为 -Z）。 */
        public double wz = 0;
        /** 分配到的槽位 index（0..31；-1 = 来自 hub 人工摆位 POS 帧）。 */
        public int slot = -1;
        /** 是否来自 hub 人工摆位（POS）；false = 自动槽位。 */
        public boolean pinned = false;
        /** 相对听者的方位角（度，0=正前，+90=右）。 */
        public double azDeg = 0;
    }

    public interface OnNodeTap {
        /** 已选中该重绘；node 不会为 null。（MainActivity 用它调音量/切 hub） */
        void onTap(Node node);
    }

    public interface OnNodeDoubleTap {
        /** 双击节点 → 切汇聚端（PC 端 dblclick → onActivate 的 Android 对应）。 */
        void onDoubleTap(Node node);
    }

    // =====================================================================
    // 颜色：直接用 PC 端 styles.css / scene3d.js 的 oklch 字面值转 sRGB，
    // 这样深色主题下的球体/地面/文字色与 PC 保持一致。
    // =====================================================================

    private static final int COLOR_LINE = oklch(0.35, 0.03, 255, 0.60);
    private static final int COLOR_FLOW = oklch(0.72, 0.15, 255, 0.90);
    private static final int COLOR_TEXT = oklch(0.94, 0.01, 255, 1.0);
    private static final int COLOR_DIM = oklch(0.68, 0.02, 255, 1.0);
    private static final int COLOR_FAINT = oklch(0.52, 0.02, 255, 1.0);
    private static final int COLOR_ACCENT = oklch(0.62, 0.17, 255, 1.0);
    private static final int COLOR_ACCENT_HI = oklch(0.72, 0.15, 255, 1.0);
    private static final int COLOR_STROKE = oklch(0.55, 0.10, 255, 1.0);
    private static final int COLOR_STROKE_OFF = oklch(0.42, 0.02, 255, 1.0);
    private static final int COLOR_FILL_NODE = oklch(0.40, 0.06, 255, 1.0);
    private static final int COLOR_FILL_OFF = oklch(0.30, 0.03, 255, 1.0);
    private static final int COLOR_FILL_GHOST = oklch(0.26, 0.02, 255, 1.0);
    private static final int COLOR_GROUND = oklch(0.42, 0.03, 255, 0.42);
    private static final int COLOR_GROUND_DIM = oklch(0.42, 0.03, 255, 0.22);

    /** 标签占用的像素盒（名称/信息行/角标），拟合缩放时算进边距。 */
    private static final float LABEL_HALF_W_DP = 60f;
    private static final float LABEL_V_DP = 30f;
    /** 环/方位字低于这些像素尺寸就不画：否则在 hub 球上糊成一团。 */
    private static final float RING_MIN_PX_DP = 8f;
    private static final float ROOM_MIN_PX_DP = 10f;
    private static final float RING_LABEL_MIN_PX_DP = 20f;
    private static final float MARK_MIN_PX_DP = 60f;

    /** 双击时间窗（ms）。 */
    private static final long DOUBLE_TAP_MS = 320L;

    private final List<Node> nodes = new ArrayList<>();
    private OnNodeTap onNodeTap;
    private OnNodeDoubleTap onNodeDoubleTap;
    private String selectedId = "";
    private String statusText = "";

    // 布局（onSizeChanged / onDraw 里算好）：sx = cx + wx*ppu, sy = cy + wz*ppu
    private float cx;
    private float cy;
    private float ppu;
    /** 当前视野半径（米）：算 ppu 时得到，画在左下角当比例尺。 */
    private float viewRadiusM = GraphFit.MIN_VIEW_RADIUS_M;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dashGround = new DashPathEffect(new float[]{4, 6}, 0);
    private final DashPathEffect dashOffline = new DashPathEffect(new float[]{5, 5}, 0);

    // 当前帧（onDraw 里算好，onTouchEvent 复用做命中测试；顺序 = 绘制顺序，越后越在上层）
    private final List<Item> frameItems = new ArrayList<>();

    // 手势：只保留点击/双击；没有拖动、缩放、长按
    private float downX;
    private float downY;
    private boolean gestureMoved;
    private long lastTapMs;
    private float lastTapX;
    private float lastTapY;
    private String lastTapNodeId = "";

    /** 一帧里的一个节点（屏幕坐标）。 */
    private static final class Item {
        Node node;
        float sx;
        float sy;
        float rPx;
        float dist;
        boolean selected;
    }

    public HubGraphView(Context context) {
        super(context);
        init();
    }

    public HubGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        arcPaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        setFocusable(true);
        setClickable(true);
    }

    // =====================================================================
    // 对外 API：Node / OnNodeTap / OnNodeDoubleTap / setData / setSelected / setStatus
    // =====================================================================

    public void setOnNodeTap(OnNodeTap listener) {
        onNodeTap = listener;
    }

    public void setOnNodeDoubleTap(OnNodeDoubleTap listener) {
        onNodeDoubleTap = listener;
    }

    /** 用当前图谱快照刷新画面。Node 的 wx/wz 必须由调用方（MainActivity）先填好。 */
    public void setData(List<Node> input) {
        nodes.clear();
        if (input != null) nodes.addAll(input);
        if (!selectedId.isEmpty() && find(selectedId) == null) selectedId = "";
        // 节点位置变了就重算缩放：桌面把节点拖远后，手机端必须跟着缩放到能看见
        final int w = getWidth();
        final int h = getHeight();
        if (w > 0 && h > 0) computeLayout(w, h);
        invalidate();
    }

    public void setSelected(String id) {
        selectedId = id != null ? id : "";
        invalidate();
    }

    public String selectedId() {
        return selectedId;
    }

    /** 顶部提示（"已切换，正在同步…" / "已同步 v7" 之类）。 */
    public void setStatus(String text) {
        statusText = text != null ? text : "";
        invalidate();
    }

    private Node find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Node n : nodes) {
            if (id.equals(n.id)) return n;
        }
        return null;
    }

    // =====================================================================
    // 布局：等比、居中、视野自适应
    // =====================================================================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeLayout(w, h);
    }

    /**
     * 布局：视野自适应（v1.6）。ppu 由 {@link GraphFit} 按当前所有节点算：最远节点连球半径
     * 与标签一起装进画布、四边各留 12% 边距；近处不小于 2.6 m 视野，远处最多 640 m。
     * 节点位置每次 setData 变化都会重算（否则拖远的节点会一直画在屏外）。
     */
    private void computeLayout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        // 顶部给状态文字留一条窄带；剩余区域等比居中
        final float topReserve = sp(16f) + dp(8);
        final GraphFit.Item[] items = new GraphFit.Item[nodes.size()];
        for (int i = 0; i < items.length; ++i) {
            final Node n = nodes.get(i);
            items[i] = new GraphFit.Item((float) n.wx, (float) n.wz,
                    (float) (n.hub ? Scene3D.CFG.HUB_BALL_R : Scene3D.CFG.NODE_BALL_R),
                    n.hub ? dp(18f) : dp(8f), n.hub ? dp(56f) : dp(22f));
        }
        final GraphFit.Result fit = GraphFit.fit(w, h, topReserve, items,
                dp(LABEL_HALF_W_DP), dp(LABEL_V_DP));
        ppu = fit.ppu;
        viewRadiusM = fit.viewRadiusM;
        // 等比、居中：cx/cy 就是听者/hub 原点（屏幕上方 = -Z = 正前方，右侧 = +X）
        final float usableH = Math.max(1f, h - topReserve);
        cx = w / 2f;
        cy = topReserve + usableH / 2f;
    }

    // =====================================================================
    // 绘制
    // =====================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (ppu <= 0) computeLayout(w, h);
        canvas.drawColor(Color.TRANSPARENT);

        // ---- 顶部状态文字 ----
        if (!statusText.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(sp(10.5f));
            textPaint.setColor(COLOR_DIM);
            textPaint.setAlpha(255);
            canvas.drawText(displayText(statusText), w / 2f, sp(10.5f) + dp(2), textPaint);
        }

        // ---- 地面（1 m 实线内圈、2.4 m 虚线房间边界 + 前/右/后/左方位 + 距离标注）----
        drawGround(canvas);

        final List<Item> items = buildItems(w, h);
        if (items.isEmpty()) {
            drawGhostHub(canvas);
            return;
        }

        // ---- 连线（hub → 每个节点，表示音频流走向）----
        drawLinks(canvas, items);

        // ---- 球：hub 最后画（压在中心，保证可读）----
        for (Item it : items) {
            if (!it.node.hub) drawNode(canvas, it, w, h);
        }
        for (Item it : items) {
            if (it.node.hub) drawNode(canvas, it, w, h);
        }
    }

    /** 俯视正交映射 + 每帧品。 */
    private List<Item> buildItems(int w, int h) {
        final List<Item> items = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            final Item it = new Item();
            it.node = n;
            it.selected = !n.id.isEmpty() && n.id.equals(selectedId);
            it.sx = cx + (float) n.wx * ppu;
            it.sy = cy + (float) n.wz * ppu;
            it.dist = (float) Math.hypot(n.wx, n.wz);
            n.azDeg = Scene3D.wrap360(Math.atan2(n.wx, -n.wz) / Scene3D.DEG);
            it.rPx = n.hub ? hubRadiusPx() : nodeRadiusPx();
            items.add(it);
        }
        frameItems.clear();
        frameItems.addAll(items);
        return items;
    }

    private float hubRadiusPx() {
        return clamp((float) (Scene3D.CFG.HUB_BALL_R * ppu), dp(18), dp(56));
    }

    private float nodeRadiusPx() {
        return clamp((float) (Scene3D.CFG.NODE_BALL_R * ppu), dp(8), dp(22));
    }

    /** 地面：R=1 m 实线、R=2.4 m 虚线 + 方位 + 45° 距离标注 + 原点 + 左下比例尺。 */
    private void drawGround(Canvas canvas) {
        final float ringPx = (float) Scene3D.CFG.RING_R * ppu;
        final float roomPx = (float) Scene3D.CFG.ROOM_R * ppu;
        // 环始终按真实比例（半径米数 × ppu），不单独缩放；缩得太小就干脆不画，避免糊在 hub 球上。
        if (roomPx >= dp(ROOM_MIN_PX_DP)) {
            drawRing(canvas, (float) Scene3D.CFG.ROOM_R, true, COLOR_GROUND_DIM);
            if (ringPx >= dp(RING_MIN_PX_DP)) {
                drawRing(canvas, (float) Scene3D.CFG.RING_R, false, COLOR_GROUND);
            }
        }

        // 方位标注（两个环中间 1.7 m 处）：离中心太近会压在 hub 球上，缩到 60 dp 以内就不画
        final float markR = (float) ((Scene3D.CFG.RING_R + Scene3D.CFG.ROOM_R) / 2.0);
        final float markRpx = markR * ppu;
        if (markRpx >= dp(MARK_MIN_PX_DP)) {
            final String[] marks = {"前", "右", "后", "左"};
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(sp(10f));
            textPaint.setColor(COLOR_FAINT);
            textPaint.setAlpha((int) Math.round(255 * 0.85f));
            final float[][] positions = {
                    {cx, cy - markRpx},
                    {cx + markRpx, cy},
                    {cx, cy + markRpx},
                    {cx - markRpx, cy},
            };
            for (int i = 0; i < marks.length; ++i) {
                canvas.drawText(marks[i], clampTextX(positions[i][0], marks[i]),
                        positions[i][1] + sp(3.5f), textPaint);
            }
        }

        // 距离标注：两个环各在 45°（右后方向）标 "1.0 m" / "2.4 m"；环本身有足够像素才标
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(sp(9.5f));
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha((int) Math.round(255 * 0.70f));
        final float k = (float) Math.sin(45 * Scene3D.DEG);
        final float[] ringRs = {(float) Scene3D.CFG.RING_R, (float) Scene3D.CFG.ROOM_R};
        for (float rr : ringRs) {
            final float rrPx = rr * ppu;
            if (rrPx < dp(RING_LABEL_MIN_PX_DP)) continue;   // 环太小，标了也糊
            final String label = formatMeters(rr) + " m";
            canvas.drawText(label, cx + rrPx * k + dp(6), cy + rrPx * k, textPaint);
        }
        textPaint.setAlpha(255);

        // 原点（听者位置）小圆点
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(COLOR_GROUND_DIM);
        fillPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, dp(1) + 1f, fillPaint);

        drawScaleHint(canvas);
    }

    /**
     * 左下角比例尺：当前视野半径（米）。缩放到几百米时节点都挤成小点，
     * 用户需要一个「现在这屏有多大」的参照，否则会误以为位置信息丢了。
     */
    private void drawScaleHint(Canvas canvas) {
        final int h = getHeight();
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(sp(9.5f));
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha((int) Math.round(255 * 0.65f));
        canvas.drawText("视野 " + formatMeters(viewRadiusM) + " m", dp(6), h - dp(6), textPaint);
        textPaint.setAlpha(255);
    }

    private void drawRing(Canvas canvas, float radiusM, boolean dashed, int color) {
        linePaint.setPathEffect(dashed ? dashGround : null);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(dp(1));
        linePaint.setAlpha(255);
        canvas.drawCircle(cx, cy, radiusM * ppu, linePaint);
        linePaint.setPathEffect(null);
    }

    /** 连线：hub → 每个节点；选中加粗、有流高亮，电平越亮越粗。 */
    private void drawLinks(Canvas canvas, List<Item> items) {
        Item hub = null;
        for (Item it : items) {
            if (it.node.hub) {
                hub = it;
                break;
            }
        }
        if (hub == null) return;
        for (Item it : items) {
            if (it.node.hub) continue;
            final double boost = levelBoost(it.node);
            linePaint.setPathEffect(null);
            if (it.selected) {
                linePaint.setColor(COLOR_ACCENT_HI);
                linePaint.setStrokeWidth((float) Math.max(dp(3), dp(2) + boost));
                linePaint.setAlpha(255);
            } else if (it.node.streamId > 0) {
                linePaint.setColor(COLOR_FLOW);
                linePaint.setStrokeWidth((float) (dp(2) + boost));
                linePaint.setAlpha((int) Math.round(255 * 0.95f));
            } else {
                linePaint.setColor(COLOR_LINE);
                linePaint.setStrokeWidth(dp(2));
                linePaint.setAlpha((int) Math.round(255 * 0.70f));
            }
            canvas.drawLine(hub.sx, hub.sy, it.sx, it.sy, linePaint);
        }
        linePaint.setAlpha(255);
    }

    private double levelBoost(Node n) {
        if (n.streamId <= 0 || Double.isNaN(n.level)) return 0;
        return Scene3D.levelPct(n.level) / 26.0;
    }

    /** 单个球：光晕/球体渐变/描边（离线虚线）/电平弧/选中环/内部缩写/角标/名称与信息。 */
    private void drawNode(Canvas canvas, Item it, int w, int h) {
        final Node n = it.node;
        final float r = it.rPx;
        final float sx = it.sx;
        final float sy = it.sy;
        final float alpha = nodeAlpha(n);
        final boolean offline = !n.online && !n.hub;

        // 底部光晕：hub 常亮（两个同心圆）
        if (n.hub) {
            fillPaint.setShader(null);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(COLOR_ACCENT);
            fillPaint.setAlpha((int) Math.round(255 * 0.10f * alpha));
            canvas.drawCircle(sx, sy, r * (1 + 15.0f / 46.0f), fillPaint);
            fillPaint.setAlpha((int) Math.round(255 * 0.14f * alpha));
            canvas.drawCircle(sx, sy, r * (1 + 7.0f / 46.0f), fillPaint);
        }

        // 球体底色
        fillPaint.setShader(null);
        fillPaint.setColor(ballColor(n));
        fillPaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawCircle(sx, sy, r, fillPaint);

        // 球面渐变（左上高光 → 右下暗角）
        shaderPaint.setStyle(Paint.Style.FILL);
        shaderPaint.setShader(new RadialGradient(
                sx - r * 0.34f, sy - r * 0.38f, r * 1.06f,
                new int[]{
                        Color.argb(Math.round(255 * 0.40f), 255, 255, 255),
                        Color.argb(Math.round(255 * 0.10f), 255, 255, 255),
                        Color.argb(Math.round(255 * 0.06f), 0, 0, 0),
                        Color.argb(Math.round(255 * 0.34f), 0, 0, 0)},
                new float[]{0f, 0.42f, 0.82f, 1f},
                Shader.TileMode.CLAMP));
        shaderPaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawCircle(sx, sy, r, shaderPaint);
        shaderPaint.setShader(null);

        // 描边：选中加粗、离线虚线置灰
        strokePaint.setPathEffect(offline ? dashOffline : null);
        strokePaint.setColor(it.selected ? COLOR_ACCENT_HI
                : (offline ? COLOR_STROKE_OFF : COLOR_STROKE));
        strokePaint.setStrokeWidth(it.selected ? dp(1.5f) : dp(1));
        strokePaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawCircle(sx, sy, r, strokePaint);
        strokePaint.setPathEffect(null);

        // 电平弧：从正上方顺时针扫 levelPct
        if (n.streamId > 0 && !Double.isNaN(n.level)) {
            final double pct = Scene3D.levelPct(n.level);
            final float arcR = r + dp(2);
            arcPaint.setColor(COLOR_FLOW);
            arcPaint.setStrokeWidth(dp(1.5f));
            arcPaint.setAlpha((int) Math.round(255 * 0.85f * alpha));
            canvas.drawArc(new android.graphics.RectF(sx - arcR, sy - arcR, sx + arcR, sy + arcR),
                    -90f, (float) (pct * 360), false, arcPaint);
        }

        // 选中外圈
        if (it.selected) {
            strokePaint.setColor(COLOR_ACCENT_HI);
            strokePaint.setStrokeWidth(dp(1));
            strokePaint.setAlpha((int) Math.round(255 * 0.45f));
            canvas.drawCircle(sx, sy, r + dp(3), strokePaint);
        }

        // 球内缩写："本机" / 名称前 4~6 个字符
        final String inside = n.self ? "本机" : Scene3D.shortName(displayName(n), n.hub ? 6 : 4);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(clamp(r * 0.34f, sp(9f), sp(15f)));
        textPaint.setColor(COLOR_TEXT);
        textPaint.setAlpha((int) Math.round(255 * alpha));
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(displayText(inside), sx, sy - (fm.ascent + fm.descent) / 2f, textPaint);

        // 右上角标：本机 / 汇聚端 / 本机·汇聚端
        final String badge = badgeText(n);
        if (!badge.isEmpty()) {
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(sp(10.5f));
            textPaint.setColor(COLOR_ACCENT_HI);
            textPaint.setAlpha((int) Math.round(255 * alpha));
            final float textWidth = textPaint.measureText(badge);
            float bx = sx + r + dp(5);
            boolean flip = false;
            if (bx + textWidth > w - dp(4)) {
                flip = true;
                bx = sx - r - dp(5);
            }
            final float by = clamp(sy - r + sp(2), sp(12f), h - dp(4));
            textPaint.setTextAlign(flip ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText(badge, bx, by, textPaint);
        }

        // 名称 + 第二行（电平 · 距离）
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        if (n.hub) {
            textPaint.setTextSize(sp(13f));
            textPaint.setColor(COLOR_TEXT);
            textPaint.setAlpha((int) Math.round(255 * alpha));
            final float nameY = clamp(sy + r + sp(16f), sp(14f), h - dp(4));
            canvas.drawText(displayText(Scene3D.clampText(displayName(n), 22)), sx, nameY, textPaint);
            if (n.streamId > 0 && !Double.isNaN(n.level)) {
                textPaint.setTextSize(sp(11.5f));
                textPaint.setColor(COLOR_FAINT);
                canvas.drawText(displayText(Scene3D.dbText(n.level)), sx,
                        clamp(nameY + sp(13f), sp(14f), h - dp(2)), textPaint);
            }
        } else if (showName(it)) {
            // 上方空间够就放上面，否则放下面（避免贴边被切掉）
            final boolean above = sy - r - sp(18f) > dp(6);
            final float nameY = above ? sy - r - sp(4f) : sy + r + sp(13f);
            final float infoY = above ? nameY - sp(13f) : nameY + sp(13f);
            textPaint.setTextSize(sp(12.5f));
            textPaint.setColor(it.selected ? COLOR_ACCENT_HI : COLOR_TEXT);
            textPaint.setAlpha((int) Math.round(255 * Math.min(1.0, alpha + 0.15)));
            final String nameText = displayText(Scene3D.clampText(displayName(n), 18));
            canvas.drawText(nameText, clampTextX(sx, nameText),
                    clamp(nameY, sp(12f), h - dp(4)), textPaint);
            textPaint.setTextSize(sp(11.5f));
            textPaint.setColor(COLOR_FAINT);
            textPaint.setAlpha((int) Math.round(255 * alpha));
            final String info = displayText(infoText(it));
            canvas.drawText(info, clampTextX(sx, info),
                    clamp(infoY, sp(12f), h - dp(2)), textPaint);
        }
        textPaint.setAlpha(255);
        fillPaint.setAlpha(255);
    }

    private boolean showName(Item it) {
        if (it.selected) return true;
        int others = 0;
        for (Item other : frameItems) {
            if (!other.node.hub) others++;
        }
        return others <= Scene3D.CFG.LABEL_LOD || frameItems.size() < 2;
    }

    private String infoText(Item it) {
        final Node n = it.node;
        final String dist = formatMeters(it.dist) + " m";
        if (n.streamId > 0 && !Double.isNaN(n.level)) {
            return Scene3D.dbText(n.level) + " · " + dist;
        }
        return dist;
    }

    private float nodeAlpha(Node n) {
        return n.online ? 1f : 0.55f;
    }

    private int ballColor(Node n) {
        if (!n.online) return COLOR_FILL_OFF;
        if (n.hub) return COLOR_ACCENT;
        return COLOR_FILL_NODE;
    }

    private String badgeText(Node n) {
        if (n.self && n.hub) return "本机·汇聚端";
        if (n.self) return "本机";
        if (n.hub) return "汇聚端";
        return "";
    }

    /** 空图谱：在原点画一个虚线 ghost 球 + "正在等待设备…"。 */
    private void drawGhostHub(Canvas canvas) {
        final float r = hubRadiusPx();
        fillPaint.setShader(null);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(COLOR_FILL_GHOST);
        fillPaint.setAlpha((int) Math.round(255 * 0.9f));
        canvas.drawCircle(cx, cy, r, fillPaint);

        strokePaint.setColor(COLOR_STROKE_OFF);
        strokePaint.setStrokeWidth(dp(1.5f));
        strokePaint.setPathEffect(dashOffline);
        strokePaint.setAlpha((int) Math.round(255 * 0.9f));
        canvas.drawCircle(cx, cy, r, strokePaint);
        strokePaint.setPathEffect(null);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(clamp(r * 0.34f, sp(9f), sp(15f)));
        textPaint.setColor(COLOR_DIM);
        textPaint.setAlpha(255);
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText("?", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(sp(12f));
        canvas.drawText("正在等待设备…", cx, cy + r + sp(20f), textPaint);
    }

    // =====================================================================
    // 手势：只有单击 / 双击（没有任何位置编辑）
    // =====================================================================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    gestureMoved = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!gestureMoved) handleTap(event.getX(), event.getY());
                gestureMoved = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                gestureMoved = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleTap(float x, float y) {
        final Item hit = pick(x, y);
        final long now = System.currentTimeMillis();
        if (hit == null) {
            // 空白单击：没有相机复位之类的行为了；清掉双击记忆即可
            lastTapMs = 0;
            lastTapNodeId = "";
            return;
        }
        final Node n = hit.node;
        final String id = n.id != null ? n.id : "";
        selectedId = id;
        invalidate();

        final boolean near = Math.abs(x - lastTapX) <= dp(24) && Math.abs(y - lastTapY) <= dp(24);
        final boolean dbl = near && id.equals(lastTapNodeId) && now - lastTapMs <= DOUBLE_TAP_MS;
        lastTapX = x;
        lastTapY = y;
        lastTapNodeId = id;
        lastTapMs = now;
        if (dbl) {
            lastTapMs = 0;
            final OnNodeDoubleTap cb = onNodeDoubleTap;
            if (cb != null) {
                cb.onDoubleTap(n);
                return;
            }
        }
        final OnNodeTap listener = onNodeTap;
        if (listener != null) listener.onTap(n);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 屏幕坐标命中测试：后画的在上层，所以反向遍历。 */
    private Item pick(float x, float y) {
        for (int i = frameItems.size() - 1; i >= 0; --i) {
            final Item it = frameItems.get(i);
            final float hitR = Math.max(it.rPx + dp(8), dp(18));
            if (Math.hypot(x - it.sx, y - it.sy) <= hitR) return it;
        }
        return null;
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 居中文本的水平位置夹到视图内（避免贴边被切）。 */
    private float clampTextX(float x, String text) {
        final float half = textPaint.measureText(text) / 2f;
        final float min = half + dp(2);
        final float max = getWidth() - half - dp(2);
        return max > min ? clamp(x, min, max) : getWidth() / 2f;
    }

    /** 名字兜底：永不返回 null（drawText(null) 会 NPE 崩 UI 线程）。 */
    private String displayName(Node n) {
        if (n.name != null && !n.name.trim().isEmpty()) return n.name;
        if (n.id != null && !n.id.isEmpty()) return n.id;
        return "未知设备";
    }

    private String displayText(String s) {
        return s != null ? s : "";
    }

    /** 米数格式化：1 位小数就够（"2.0"）。 */
    private String formatMeters(double m) {
        return String.format(Locale.US, "%.1f", m);
    }

    /**
     * CSS oklch(L C H / alpha) → Android ARGB。oklab→线性 sRGB→gamma 编码，
     * 用于让配色与 PC 端 styles.css（深色主题）逐色一致。
     */
    private static int oklch(double l, double c, double hDeg, double alpha) {
        final double h = hDeg * Math.PI / 180.0;
        final double a = c * Math.cos(h);
        final double b = c * Math.sin(h);
        final double l_ = l + 0.3963377774 * a + 0.2158037573 * b;
        final double m_ = l - 0.1055613458 * a - 0.0638541728 * b;
        final double s_ = l - 0.0894841775 * a - 1.2914855480 * b;
        final double l3 = l_ * l_ * l_;
        final double m3 = m_ * m_ * m_;
        final double s3 = s_ * s_ * s_;
        double r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3;
        double g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3;
        double bb = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3;
        r = gamma(r);
        g = gamma(g);
        bb = gamma(bb);
        return Color.argb(
                (int) Math.round(clamp((float) alpha, 0f, 1f) * 255),
                (int) Math.round(clamp((float) r, 0f, 1f) * 255),
                (int) Math.round(clamp((float) g, 0f, 1f) * 255),
                (int) Math.round(clamp((float) bb, 0f, 1f) * 255));
    }

    private static double gamma(double c) {
        if (c <= 0.0031308) return 12.92 * c;
        return 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
    }
}