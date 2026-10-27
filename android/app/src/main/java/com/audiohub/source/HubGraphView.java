package com.audiohub.source;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 星图（star graph）：俯视平面的只读图谱，v1.7 起从「深蓝仪表盘」改成「星空」语义。
 *
 *   · 汇聚端 = 中心<b>恒星</b>：暖金核心 + 日冕光晕 + 4 长 4 短星芒 + 日珥 + 总线电平弧；
 *   · 其他设备 = 围绕它分布的<b>行星</b>：静止（不做公转/自转动画），朝恒星的一面受光，
 *     有大气辉光 + 背光面 + 受光边缘弧；离线行星灰暗、虚线外圈（并可选一条行星环）；
 *   · 恒星↔行星之间是渐变<b>光束</b>：在线亮、有声音更亮、离线虚线；
 *   · 整页星空背景由 {@link StarSkyView} 在最底层画，本类再叠一层局部星尘 + 中心金色星云。
 *
 * 几何仍是原来的正交俯视映射，世界坐标 (wx, wz) 米 →
 * {@code sx = cx + wx * ppu}、{@code sy = cy + wz * ppu}；屏幕上方 = -Z = 房间正前方，
 * 屏幕右 = +X = 右耳方向。视野自适应（{@link GraphFit}）：遍历所有节点的 wx/wz，算出让
 * 「最远节点 + 球半径 + 标签」也落在画布内的 ppu，近处不小于 2.6 m 视野，远处最多 640 m。
 * 环/RING 按真实比例（半径米数 × ppu），缩得太小就自动省略。完全只读：没有轨道旋转、
 * 没有捏合缩放、没有拖动节点；保留点击（选中/音量面板/切汇聚端确认）与双击（切汇聚端）。
 *
 * 位置权威来自 hub 同步的 POS 帧（{@link PlacementStore}）；没有人工摆位的节点由
 * MainActivity 用 Scene3D 的确定性自动槽位兜底，本类只负责画。
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
    // 色板：docs/starfield-ui-preview 设计令牌（深空底 #04060f / 面板 #0b1224 /
    // 主文字 #eaf1ff / 次 #a3b3d1 / 弱 #6e7d9d / 青 #6fe0ff / 金 #ffd27a / 琥珀 #ffd166）
    // =====================================================================

    private static final int COLOR_TEXT = 0xFFEAF1FF;
    private static final int COLOR_DIM = 0xFFA3B3D1;
    private static final int COLOR_FAINT = 0xFF6E7D9D;
    private static final int COLOR_CYAN = 0xFF6FE0FF;
    private static final int COLOR_GOLD = 0xFFFFD27A;
    private static final int COLOR_AMBER = 0xFFFFD166;
    private static final int COLOR_LINE = 0x2E7896C8;        // rgba(120,150,200,.18)
    private static final int COLOR_GROUND = 0x2696B4EB;      // rgba(150,180,235,.15)
    private static final int COLOR_GROUND_DIM = 0x1496B4EB;  // rgba(150,180,235,.08)
    private static final int COLOR_CHIP_BG = 0xCC0A1224;     // 顶部同步状态胶囊
    private static final int COLOR_CHIP_STROKE = 0x3AA3B3D1;
    private static final int COLOR_HUB_NAME = 0xFFFFE8BD;
    private static final int COLOR_HUB_BUS = 0xB3FFDCA0;     // rgba(255,220,160,.70)
    private static final int COLOR_HUB_BADGE = 0xEBFFD27A;   // rgba(255,210,122,.92)
    private static final int COLOR_HALO = 0xC804060F;        // 文字描边底（深空色）
    private static final int COLOR_PLANET_NAME_ON = 0xFFDFE9FB;
    private static final int COLOR_PLANET_NAME_OFF = 0xFF9AA7BD;
    private static final int COLOR_PLANET_INFO = 0xFF8E9DB8;
    private static final int COLOR_PLANET_INFO_STREAM = 0xFF9FDCF4;
    private static final int COLOR_PLANET_INFO_OFF = 0xFF7D879C;

    /** 行星配色（hi 受光面 / base 本体 / dark 背光面 / glow 大气辉光 RGB）。 */
    private static final class Palette {
        final int hi;
        final int base;
        final int dark;
        final int glowR;
        final int glowG;
        final int glowB;

        Palette(int hi, int base, int dark, int glowR, int glowG, int glowB) {
            this.hi = hi;
            this.base = base;
            this.dark = dark;
            this.glowR = glowR;
            this.glowG = glowG;
            this.glowB = glowB;
        }
    }

    private static final Palette[] PALETTES = {
            new Palette(0xFFE2FBFF, 0xFF59C6EA, 0xFF0A2A42, 111, 224, 255),  // cyan 青
            new Palette(0xFFEFE9FF, 0xFF9B86FF, 0xFF241650, 170, 150, 255),  // violet 紫
            new Palette(0xFFFFE6F1, 0xFFE486B5, 0xFF4A1734, 255, 156, 198),  // rose 玫
            new Palette(0xFFFFF4D6, 0xFFE8B45E, 0xFF4A2D0C, 255, 200, 110),  // amber 琥珀
            new Palette(0xFFEEF5FF, 0xFFA8BAD4, 0xFF2A3648, 160, 185, 215),  // ice 冰
    };

    private static final Palette PALETTE_OFF =
            new Palette(0xFFC7D0E0, 0xFF5A6579, 0xFF1A202C, 140, 155, 180);

    /** 标签占用的像素盒（名称/信息行/角标），拟合缩放时算进边距。 */
    private static final float LABEL_HALF_W_DP = 60f;
    private static final float LABEL_V_DP = 30f;
    /** 环/方位字低于这些像素尺寸就不画：否则在恒星上糊成一团。 */
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
    private final Path spikePath = new Path();
    private final DashPathEffect dashGround = new DashPathEffect(new float[]{4, 7}, 0);
    private final DashPathEffect dashOffline = new DashPathEffect(new float[]{5, 5}, 0);
    private final DashPathEffect dashRingOffline = new DashPathEffect(new float[]{3.5f, 5}, 0);
    private final DashPathEffect dashSelected = new DashPathEffect(new float[]{2, 6}, 0);
    private final DashPathEffect dashBeam = new DashPathEffect(new float[]{3, 7}, 0);

    // 当前帧（onDraw 里算好，onTouchEvent 复用做命中测试；顺序 = 绘制顺序，越后越在上层）
    private final List<Item> frameItems = new ArrayList<>();

    // 局部星尘（固定种子，尺寸变化时重算；让星图区域也有近处星点，不只靠底层背景）
    private float[] dustX = new float[0];
    private float[] dustY = new float[0];
    private float[] dustR = new float[0];
    private float[] dustA = new float[0];
    private RadialGradient skyGold;
    private RadialGradient skyViolet;
    private RadialGradient centerScrim;
    private RadialGradient[] cornerScrims;
    private int skyW;
    private int skyH;

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
        Palette palette;
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
        buildSky(w, h);
    }

    /** 局部星空：中心金色星云（恒星照亮周围）+ 一点紫色星云 + 固定种子星尘。尺寸不变就复用。 */
    private void buildSky(int w, int h) {
        if (skyGold != null && w == skyW && h == skyH) return;
        skyW = w;
        skyH = h;
        final float max = Math.max(w, h);
        skyGold = new RadialGradient(cx, cy, max * 0.42f,
                new int[]{0x0FFFBA60, 0x05FFF0C0, 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        skyViolet = new RadialGradient(cx + w * 0.22f, cy - h * 0.16f, Math.max(dp(40), w * 0.30f),
                new int[]{0x0D8C82FF, 0x048C82FF, 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);

        // 星图区域压暗：以中心为圆心的径向暗化 + 四角暗角。只压暗不铺实心色，
        // 星空仍从缝里透出来，恒星/行星/文字因此更浮得出来。
        centerScrim = new RadialGradient(cx, cy, max * 0.58f,
                new int[]{0x2402040A, 0x1202040A, 0x0002040A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        final float cornerR = Math.min(w, h) * 0.78f;
        cornerScrims = new RadialGradient[]{
                new RadialGradient(0, 0, cornerR,
                        new int[]{0x6E02040A, 0x0002040A}, new float[]{0f, 1f}, Shader.TileMode.CLAMP),
                new RadialGradient(w, 0, cornerR,
                        new int[]{0x6E02040A, 0x0002040A}, new float[]{0f, 1f}, Shader.TileMode.CLAMP),
                new RadialGradient(0, h, cornerR,
                        new int[]{0x6E02040A, 0x0002040A}, new float[]{0f, 1f}, Shader.TileMode.CLAMP),
                new RadialGradient(w, h, cornerR,
                        new int[]{0x6E02040A, 0x0002040A}, new float[]{0f, 1f}, Shader.TileMode.CLAMP),
        };

        final StarSkyView.Rng rng = new StarSkyView.Rng(0x5EED17);
        final int count = 120;
        dustX = new float[count];
        dustY = new float[count];
        dustR = new float[count];
        dustA = new float[count];
        for (int i = 0; i < count; ++i) {
            dustX[i] = rng.next() * w;
            dustY[i] = rng.next() * h;
            dustR[i] = (0.5f + rng.next() * 1.3f) * getResources().getDisplayMetrics().density;
            dustA[i] = 0.10f + rng.next() * 0.42f;
        }
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

        // ---- 局部星空（底层 StarSkyView 已画整页星野，这里只补星图周围的近景）----
        drawLocalSky(canvas, w, h);

        // ---- 顶部同步状态：小胶囊 chip ----
        drawStatusChip(canvas, w);

        // ---- 环（1 m 实线内圈、2.4 m 虚线房间边界 + 前/右/后/左方位 + 距离标注）----
        drawGround(canvas);

        final List<Item> items = buildItems(w, h);
        if (items.isEmpty()) {
            drawGhostHub(canvas);
            return;
        }

        // ---- 光束（恒星 → 每颗行星，表示音频流走向）----
        drawBeams(canvas, items);

        // ---- 行星先画，恒星最后画（压在中心，保证可读）----
        for (Item it : items) {
            if (!it.node.hub) drawPlanet(canvas, it, w, h);
        }
        for (Item it : items) {
            if (it.node.hub) drawStar(canvas, it, w, h);
        }
        // 有音频流时让光束上的星尘缓慢流动（~8fps，够顺滑又省电）
        if (hasStreaming(items)) postInvalidateDelayed(120);
    }

    private void drawLocalSky(Canvas canvas, int w, int h) {
        if (skyGold == null || skyViolet == null) buildSky(w, h);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(skyGold);
        canvas.drawRect(0, 0, w, h, fillPaint);
        fillPaint.setShader(skyViolet);
        canvas.drawRect(0, 0, w, h, fillPaint);

        // 中心径向暗化 + 四角暗角
        if (centerScrim != null) {
            fillPaint.setShader(centerScrim);
            canvas.drawRect(0, 0, w, h, fillPaint);
        }
        if (cornerScrims != null) {
            for (RadialGradient g : cornerScrims) {
                fillPaint.setShader(g);
                canvas.drawRect(0, 0, w, h, fillPaint);
            }
        }
        fillPaint.setShader(null);

        for (int i = 0; i < dustX.length; ++i) {
            if (dustX[i] > w || dustY[i] > h) continue;
            fillPaint.setColor(Color.argb((int) (dustA[i] * 255), 224, 236, 255));
            canvas.drawCircle(dustX[i], dustY[i], dustR[i], fillPaint);
        }
    }

    /** 顶部同步状态：半透明小胶囊 + 青色圆点（不再是一行裸文字）。 */
    private void drawStatusChip(Canvas canvas, int w) {
        if (statusText.isEmpty()) return;
        final String text = displayText(statusText);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(sp(9.5f));
        final float tw = textPaint.measureText(text);
        final float chipH = dp(18);
        final float chipW = Math.min(dp(23) + tw + dp(9), w - dp(12));
        final float left = (w - chipW) / 2f;
        final float top = dp(4);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(COLOR_CHIP_BG);
        canvas.drawRoundRect(new RectF(left, top, left + chipW, top + chipH),
                chipH / 2f, chipH / 2f, fillPaint);
        strokePaint.setPathEffect(null);
        strokePaint.setColor(COLOR_CHIP_STROKE);
        strokePaint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(new RectF(left, top, left + chipW, top + chipH),
                chipH / 2f, chipH / 2f, strokePaint);
        fillPaint.setColor(0xCC6FE0FF);
        canvas.drawCircle(left + dp(9), top + chipH / 2f, dp(1.8f), fillPaint);
        textPaint.setColor(COLOR_DIM);
        textPaint.setAlpha(240);
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(text, left + dp(15), top + chipH / 2f - (fm.ascent + fm.descent) / 2f,
                textPaint);
        textPaint.setAlpha(255);
    }

    /** 俯视正交映射 + 每帧品。行星半径按距离缩放（远小近大），恒星固定居中。 */
    private List<Item> buildItems(int w, int h) {
        final List<Item> items = new ArrayList<>(nodes.size());
        int planetIndex = 0;
        for (Node n : nodes) {
            final Item it = new Item();
            it.node = n;
            it.selected = !n.id.isEmpty() && n.id.equals(selectedId);
            it.sx = cx + (float) n.wx * ppu;
            it.sy = cy + (float) n.wz * ppu;
            it.dist = (float) Math.hypot(n.wx, n.wz);
            n.azDeg = Scene3D.wrap360(Math.atan2(n.wx, -n.wz) / Scene3D.DEG);
            if (n.hub) {
                it.rPx = hubRadiusPx(w, h);
                it.palette = PALETTES[0];
            } else {
                it.rPx = planetRadiusPx(it.dist);
                it.palette = n.online ? PALETTES[planetIndex % PALETTES.length] : PALETTE_OFF;
                planetIndex++;
            }
            items.add(it);
        }
        frameItems.clear();
        frameItems.addAll(items);
        return items;
    }

    /**
     * 恒星半径：屏幕短边的 10.5%（约屏宽 1/5 直径），夹在 26~46 dp。
     * 但不要顶到 1 m 环上（节点离得近时 ppu 大、环也大）：最多占 1 m 环半径的 85%，
     * 这样环还能从日冕里露出来；节点离得远（环缩成 0 像素）时才退回基径。
     */
    private float hubRadiusPx(int w, int h) {
        final float base = Math.min(w, h) * 0.105f;
        final float ringPx = (float) Scene3D.CFG.RING_R * ppu;
        final float cap = Math.max(ringPx * 0.85f, dp(30));
        return clamp(Math.min(base, cap), dp(26), dp(46));
    }

    /** 行星半径：基径 23 dp × 距离系数（越远越小，0.70~1.32 倍），夹在 15~28 dp。 */
    private float planetRadiusPx(float dist) {
        final double sizeScale = clampDouble(Math.pow(1.0 / Math.max(dist, 0.2), 0.5), 0.70, 1.32);
        return clamp((float) (dp(23) * sizeScale), dp(15), dp(28));
    }

    /** 地面：R=1 m 实线、R=2.4 m 虚线 + 方位 + 45° 距离标注 + 原点 + 左下比例尺。 */
    private void drawGround(Canvas canvas) {
        final float ringPx = (float) Scene3D.CFG.RING_R * ppu;
        final float roomPx = (float) Scene3D.CFG.ROOM_R * ppu;
        // 环始终按真实比例（半径米数 × ppu），不单独缩放；缩得太小就干脆不画，避免糊在恒星上。
        if (roomPx >= dp(ROOM_MIN_PX_DP)) {
            drawRing(canvas, (float) Scene3D.CFG.ROOM_R, true, COLOR_GROUND_DIM);
            if (ringPx >= dp(RING_MIN_PX_DP)) {
                drawRing(canvas, (float) Scene3D.CFG.RING_R, false, COLOR_GROUND);
            }
        }

        // 方位标注（两个环中间 1.7 m 处）：离中心太近会压在恒星上，缩到 60 dp 以内就不画
        final float markR = (float) ((Scene3D.CFG.RING_R + Scene3D.CFG.ROOM_R) / 2.0);
        final float markRpx = markR * ppu;
        if (markRpx >= dp(MARK_MIN_PX_DP)) {
            final String[] marks = {"前", "右", "后", "左"};
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(sp(9f));
            textPaint.setColor(COLOR_FAINT);
            textPaint.setAlpha((int) Math.round(255 * 0.55f));
            final float[][] positions = {
                    {cx, cy - markRpx},
                    {cx + markRpx, cy},
                    {cx, cy + markRpx},
                    {cx - markRpx, cy},
            };
            for (int i = 0; i < marks.length; ++i) {
                drawHaloText(canvas, marks[i], clampTextX(positions[i][0], marks[i]),
                        positions[i][1] + sp(3.5f), textPaint);
            }
        }

        // 距离标注：两个环各在右上（-45°）标 "1.0 m" / "2.4 m"；环本身有足够像素才标（避开恒星下方胶囊）
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(sp(9f));
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha((int) Math.round(255 * 0.50f));
        final float k = (float) Math.sin(45 * Scene3D.DEG);
        final float[] ringRs = {(float) Scene3D.CFG.RING_R, (float) Scene3D.CFG.ROOM_R};
        for (float rr : ringRs) {
            final float rrPx = rr * ppu;
            if (rrPx < dp(RING_LABEL_MIN_PX_DP)) continue;   // 环太小，标了也糊
            final String label = formatMeters(rr) + " m";
            final float labelX = Math.min(cx + rrPx * k + dp(6), getWidth() - dp(22));
            final float labelY = Math.max(cy - rrPx * k + sp(8f), sp(12f));
            canvas.drawText(label, labelX, labelY, textPaint);
        }
        textPaint.setAlpha(255);

        // 原点（听者位置）小圆点
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(0x8096B4EB);
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
        textPaint.setTextSize(sp(9f));
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha((int) Math.round(255 * 0.45f));
        canvas.drawText("视野 " + formatMeters(viewRadiusM) + " m", dp(6), h - dp(6), textPaint);
        textPaint.setAlpha(255);
    }

    private void drawRing(Canvas canvas, float radiusM, boolean dashed, int color) {
        linePaint.setPathEffect(dashed ? dashGround : null);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(dp(0.8f));
        canvas.drawCircle(cx, cy, radiusM * ppu, linePaint);
        linePaint.setPathEffect(null);
    }

    // ---------------------------------------------------------------------
    // 光束
    // ---------------------------------------------------------------------

    /** 光束：恒星 → 每颗行星的渐变线；在线亮、有声音更亮、离线虚线；出声时线上有缓慢流动的星尘。 */
    private void drawBeams(Canvas canvas, List<Item> items) {
        Item hub = null;
        for (Item it : items) {
            if (it.node.hub) {
                hub = it;
                break;
            }
        }
        if (hub == null) return;
        final float phase = (SystemClock.uptimeMillis() % 5200L) / 5200f;
        for (Item it : items) {
            if (it.node.hub) continue;
            final Node n = it.node;
            final float dx = it.sx - hub.sx;
            final float dy = it.sy - hub.sy;
            final float len = (float) Math.hypot(dx, dy);
            if (len < 1f) continue;
            final float ux = dx / len;
            final float uy = dy / len;
            final float x1 = hub.sx + ux * hub.rPx * 1.20f;
            final float y1 = hub.sy + uy * hub.rPx * 1.20f;
            final float x2 = it.sx - ux * (it.rPx + dp(2));
            final float y2 = it.sy - uy * (it.rPx + dp(2));
            if ((float) Math.hypot(x2 - x1, y2 - y1) < dp(2)) continue;

            final boolean streaming = n.streamId > 0 && n.online;
            final int tr = it.palette.glowR;
            final int tg = it.palette.glowG;
            final int tb = it.palette.glowB;
            final float boost = (float) levelBoost(n);
            final float width = streaming ? dp(1.9f) + boost
                    : (n.online ? dp(1.35f) : dp(1.2f));
            final int a1 = streaming ? 235 : (n.online ? 168 : 88);
            final int a2 = streaming ? 120 : (n.online ? 82 : 38);
            final int a3 = streaming ? 18 : (n.online ? 14 : 8);

            linePaint.setPathEffect(n.online ? null : dashBeam);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setShader(null);
            // 柔和外发光（宽而淡的一层）
            if (n.online) {
                linePaint.setColor(Color.argb(streaming ? 30 : 18, tr, tg, tb));
                linePaint.setStrokeWidth(width * 4.2f);
                canvas.drawLine(x1, y1, x2, y2, linePaint);
            }
            // 渐变芯线：恒星端略偏暖白，行星端用行星自己的辉光色
            linePaint.setShader(new LinearGradient(x1, y1, x2, y2,
                    new int[]{Color.argb(a1, 255, 246, 226), Color.argb(a2, tr, tg, tb),
                            Color.argb(a3, tr, tg, tb)},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
            linePaint.setStrokeWidth(width);
            canvas.drawLine(x1, y1, x2, y2, linePaint);
            linePaint.setShader(null);
            linePaint.setPathEffect(null);

            // 出声时沿光束撒 3 颗星尘，位置随时间缓慢前移（不是随机闪烁）
            if (streaming) {
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setShader(null);
                for (int i = 0; i < 3; ++i) {
                    final float t = (phase + i / 3f) % 1f;
                    final float fade = (float) Math.sin(Math.PI * t);
                    final float px = x1 + (x2 - x1) * t;
                    final float py = y1 + (y2 - y1) * t;
                    glow(canvas, px, py, dp(4.2f), tr, tg, tb, 0.20f * fade, 0.04f * fade);
                    fillPaint.setColor(Color.argb((int) (170 * fade), 235, 248, 255));
                    canvas.drawCircle(px, py, Math.max(dp(1.0f), width * 0.55f), fillPaint);
                }
            }
        }
    }

    /** 本帧是否有正在出声的行星（有的话需要短间隔重绘，让星尘流动）。 */
    private boolean hasStreaming(List<Item> items) {
        for (Item it : items) {
            if (!it.node.hub && it.node.streamId > 0 && it.node.online) return true;
        }
        return false;
    }

    private double levelBoost(Node n) {
        if (n.streamId <= 0 || Double.isNaN(n.level)) return 0;
        return Scene3D.levelPct(n.level) / 26.0;
    }

    // ---------------------------------------------------------------------
    // 恒星（汇聚端）
    // ---------------------------------------------------------------------

    /** 中心恒星：收敛的三层日冕 + 4 长 4 短星芒 + 轻日珥 + 色球层 + 径向渐变核心 + 总线电平弧 + 胶囊文字。 */
    private void drawStar(Canvas canvas, Item it, int w, int h) {
        final Node n = it.node;
        final float sx = it.sx;
        final float sy = it.sy;
        final float r = it.rPx;

        // 1) 日冕：三层柔光，收敛到 3.6r（不再有 5.6r 的巨型光柱）
        glow(canvas, sx, sy, r * 3.6f, 255, 188, 96, 0.26f, 0.09f);
        glow(canvas, sx, sy, r * 2.4f, 255, 212, 138, 0.30f, 0.11f);
        glow(canvas, sx, sy, r * 1.7f, 255, 236, 190, 0.28f, 0.09f);

        // 2) 4 长星芒（上/下/左/右，约 2.7r，锥形细针不再像光柱）
        drawSpike(canvas, sx, sy, 0f, r * 2.75f, r * 0.20f, 255, 232, 180, 0.85f);
        drawSpike(canvas, sx, sy, 90f, r * 2.65f, r * 0.20f, 255, 236, 196, 0.85f);
        drawSpike(canvas, sx, sy, 180f, r * 2.75f, r * 0.20f, 255, 232, 180, 0.85f);
        drawSpike(canvas, sx, sy, 270f, r * 2.65f, r * 0.20f, 255, 236, 196, 0.85f);
        // 3) 4 短星芒（四条对角线，约 1.55r）
        drawSpike(canvas, sx, sy, 45f, r * 1.55f, r * 0.12f, 255, 224, 160, 0.62f);
        drawSpike(canvas, sx, sy, 135f, r * 1.55f, r * 0.12f, 255, 224, 160, 0.62f);
        drawSpike(canvas, sx, sy, 225f, r * 1.55f, r * 0.12f, 255, 224, 160, 0.62f);
        drawSpike(canvas, sx, sy, 315f, r * 1.55f, r * 0.12f, 255, 224, 160, 0.62f);

        // 4) 轻日珥：两条短弧
        arc(canvas, sx, sy, r * 1.10f, -148f, 52f, 255, 172, 104, 0.30f, r * 0.04f);
        arc(canvas, sx, sy, r * 1.07f, 28f, 44f, 255, 172, 104, 0.30f, r * 0.04f);

        // 5) 色球层：贴边一圈淡金
        arc(canvas, sx, sy, r * 1.015f, 0f, 360f, 255, 226, 160, 0.62f, r * 0.025f);

        // 6) 核心：偏心径向渐变（#fff → #fff9e2 → #ffe8a8 → #ffd077 → #ffab4f → #ff8a3c）
        shaderPaint.setStyle(Paint.Style.FILL);
        shaderPaint.setShader(new RadialGradient(
                sx - r * 0.20f, sy - r * 0.24f, r * 1.08f,
                new int[]{0xFFFFFFFF, 0xFFFFF9E2, 0xFFFFE8A8, 0xFFFFD077, 0xFFFFAB4F, 0xFFFF8A3C},
                new float[]{0.16f, 0.40f, 0.68f, 0.88f, 0.96f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(sx, sy, r, shaderPaint);
        shaderPaint.setShader(null);

        // 7) 白热内核
        glow(canvas, sx - r * 0.10f, sy - r * 0.12f, r * 0.60f, 255, 255, 255, 0.42f, 0.10f);

        // 8) 总线电平弧（正上方起顺时针扫过）
        if (n.streamId > 0 && !Double.isNaN(n.level)) {
            final double pct = Scene3D.levelPct(n.level);
            final float busR = r * 1.55f;
            strokeArcGlow(canvas, sx, sy, busR, 0x66FFCE74, Math.max(dp(1.2f), r * 0.10f));
            arcPaint.setColor(COLOR_HUB_BUS);
            arcPaint.setStrokeWidth(Math.max(dp(1.5f), r * 0.045f));
            arcPaint.setPathEffect(null);
            canvas.drawArc(new RectF(sx - busR, sy - busR, sx + busR, sy + busR),
                    -90f, (float) (pct * 360), false, arcPaint);
        }

        // 9) 选中：虚线青色星环
        if (it.selected) {
            final float selR = r + Math.max(dp(7), r * 0.34f);
            glow(canvas, sx, sy, selR * 1.6f, 111, 224, 255, 0.13f, 0.03f);
            strokePaint.setPathEffect(dashSelected);
            strokePaint.setColor(0xD9C4F4FF);
            strokePaint.setStrokeWidth(dp(1.4f));
            canvas.drawCircle(sx, sy, selR, strokePaint);
            strokePaint.setPathEffect(null);
        }

        // 10) 角标（恒星上方）
        final String badge = badgeText(n);
        if (!badge.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(sp(9f));
            textPaint.setColor(COLOR_HUB_BADGE);
            textPaint.setAlpha(220);
            drawHaloText(canvas, badge, sx,
                    clamp(sy - r - sp(22f), sp(12f), h - dp(4)), textPaint);
        }

        // 11) 名称 / 汇聚总线：恒星下方半透明胶囊托底（对齐效果图）
        final String busText = (n.streamId > 0 && !Double.isNaN(n.level))
                ? "汇聚总线 · " + Scene3D.dbText(n.level) : null;
        final String nameText = displayText(Scene3D.clampText(displayName(n), 16));
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(sp(11.5f));
        float pillW = textPaint.measureText(nameText);
        if (busText != null) {
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(sp(10f));
            pillW = Math.max(pillW, textPaint.measureText(busText));
        }
        pillW = Math.min(pillW + dp(18), w - dp(16));
        final float pillH = busText != null ? dp(38) : dp(24);
        final float pillLeft = clamp(sx - pillW / 2f, dp(8), Math.max(dp(8), w - dp(8) - pillW));
        final float pillTop = clamp(sy + r + dp(8), sp(12f), h - dp(6) - pillH);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(0x8C05070F);
        canvas.drawRoundRect(new RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH),
                pillH / 2f, pillH / 2f, fillPaint);
        strokePaint.setPathEffect(null);
        strokePaint.setColor(0x33FFD27A);
        strokePaint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(new RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH),
                pillH / 2f, pillH / 2f, strokePaint);

        final float pillCx = pillLeft + pillW / 2f;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(sp(11.5f));
        textPaint.setColor(COLOR_HUB_NAME);
        textPaint.setAlpha(255);
        canvas.drawText(nameText, pillCx, pillTop + dp(17), textPaint);
        if (busText != null) {
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(sp(10f));
            textPaint.setColor(COLOR_HUB_BUS);
            canvas.drawText(busText, pillCx, pillTop + dp(30), textPaint);
        }
    }

    /**
     * 星芒：锥形柔光 + 细针 + 白热内针，从 (sx,sy) 沿 angleDeg 方向单向射出。
     * drawStar 按 0/90/180/270 调 4 条长芒、45/135/225/315 调 4 条短芒。
     */
    private void drawSpike(Canvas canvas, float sx, float sy, float angleDeg, float len,
                           float width, int r, int g, int b, float alpha) {
        if (len <= 0f || width <= 0f) return;
        canvas.save();
        canvas.rotate(angleDeg, sx, sy);
        final float half = width * 1.5f;
        // 1) 外层柔光锥
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(new LinearGradient(sx, sy, sx + len * 0.9f, sy,
                new int[]{Color.argb((int) (alpha * 0.30f * 255), r, g, b),
                        Color.argb((int) (alpha * 0.18f * 255), r, g, b),
                        Color.argb(0, r, g, b)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        spikePath.reset();
        spikePath.moveTo(sx, sy - half);
        spikePath.lineTo(sx + len, sy);
        spikePath.lineTo(sx, sy + half);
        spikePath.close();
        canvas.drawPath(spikePath, fillPaint);
        fillPaint.setShader(null);
        // 2) 细针
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setPathEffect(null);
        linePaint.setShader(new LinearGradient(sx, sy, sx + len, sy,
                new int[]{Color.argb((int) (alpha * 0.80f * 255), 255, 251, 238),
                        Color.argb((int) (alpha * 0.42f * 255), r, g, b),
                        Color.argb(0, r, g, b)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        linePaint.setStrokeWidth(Math.max(dp(1f), width * 0.32f));
        canvas.drawLine(sx, sy, sx + len, sy, linePaint);
        linePaint.setShader(null);
        // 3) 白热内针
        linePaint.setShader(new LinearGradient(sx, sy, sx + len * 0.4f, sy,
                new int[]{Color.argb((int) (alpha * 255), 255, 255, 250),
                        Color.argb(0, 255, 255, 250)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        linePaint.setStrokeWidth(Math.max(dp(1f), width * 0.16f));
        canvas.drawLine(sx, sy, sx + len * 0.4f, sy, linePaint);
        linePaint.setShader(null);
        canvas.restore();
    }

    /** 画一段弧（角度：0=右，顺时针）。 */
    private void arc(Canvas canvas, float sx, float sy, float radius, float startDeg, float sweepDeg,
                     int r, int g, int b, float alpha, float width) {
        arcPaint.setPathEffect(null);
        arcPaint.setColor(Color.argb((int) (alpha * 255), r, g, b));
        arcPaint.setStrokeWidth(Math.max(dp(1f), width));
        canvas.drawArc(new RectF(sx - radius, sy - radius, sx + radius, sy + radius),
                startDeg, sweepDeg, false, arcPaint);
    }

    /** 弧的外发光：宽而淡的同色弧。 */
    private void strokeArcGlow(Canvas canvas, float sx, float sy, float radius, int color,
                               float width) {
        arcPaint.setPathEffect(null);
        arcPaint.setColor(color);
        arcPaint.setStrokeWidth(width * 4.0f);
        arcPaint.setAlpha(42);
        canvas.drawCircle(sx, sy, radius, arcPaint);
        arcPaint.setAlpha(255);
    }

    // ---------------------------------------------------------------------
    // 行星（普通设备）
    // ---------------------------------------------------------------------

    /**
     * 行星：朝恒星受光的径向渐变 + 大气辉光 + 背光面 + 受光边缘弧 + 可选行星环（离线）+
     * 电平弧 + 选中虚线星环 + 离线灰暗虚线外圈 + 名称/电平/距离/角标。
     */
    private void drawPlanet(Canvas canvas, Item it, int w, int h) {
        final Node n = it.node;
        final Palette p = it.palette != null ? it.palette : PALETTE_OFF;
        final float sx = it.sx;
        final float sy = it.sy;
        final float r = it.rPx;
        final boolean offline = !n.online;
        final boolean streaming = n.streamId > 0 && n.online;
        final float alpha = offline ? 0.62f : 1f;

        // 恒星方向（受光方向）与其反方向（背光方向）
        final float lx = cx - sx;
        final float ly = cy - sy;
        final float llen = (float) Math.hypot(lx, ly);
        final float ux = llen > 0.5f ? lx / llen : 0f;
        final float uy = llen > 0.5f ? ly / llen : -1f;

        // 1) 大气辉光
        glow(canvas, sx, sy, r * 2.35f, p.glowR, p.glowG, p.glowB,
                streaming ? 0.28f : (offline ? 0.10f : 0.15f), 0.04f);

        // 2) 行星环（只给离线设备）：后半圈先画，本体后面再画前半圈
        if (offline) {
            drawPlanetRing(canvas, sx, sy, r, p, true);
        }

        // 3) 本体底色（防止渐变边缘露出透明缝）
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(p.dark);
        fillPaint.setAlpha(255);
        canvas.drawCircle(sx, sy, r, fillPaint);

        // 4) 朝恒星受光的径向渐变
        shaderPaint.setStyle(Paint.Style.FILL);
        shaderPaint.setShader(new RadialGradient(
                sx + ux * r * 0.45f, sy + uy * r * 0.45f, r * 1.08f,
                new int[]{p.hi, p.base, p.dark, 0xFF05070F},
                new float[]{0f, 0.34f, 0.78f, 1f},
                Shader.TileMode.CLAMP));
        shaderPaint.setAlpha((int) (255 * alpha));
        canvas.drawCircle(sx, sy, r, shaderPaint);
        shaderPaint.setShader(null);

        // 5) 表面条纹（球够大才画，旋转一点避免太规整）
        if (r > dp(12)) {
            canvas.save();
            canvas.rotate(-14f, sx, sy);
            strokePaint.setPathEffect(null);
            strokePaint.setColor(0x1AFFFFFF);
            strokePaint.setStrokeWidth(Math.max(dp(1), r * 0.09f));
            for (int i = -1; i <= 1; ++i) {
                final float yy = sy + i * r * 0.44f;
                final float rx = r * 0.88f;
                final float ry = r * 0.20f;
                canvas.drawOval(new RectF(sx - rx, yy - ry, sx + rx, yy + ry), strokePaint);
            }
            canvas.restore();
        }

        // 6) 背光面：反方向的暗色径向渐变压在球上
        shaderPaint.setStyle(Paint.Style.FILL);
        shaderPaint.setShader(new RadialGradient(
                sx - ux * r * 1.25f, sy - uy * r * 1.25f, r * 1.6f,
                new int[]{0xB802040C, 0x4002040C, 0x0002040C},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(sx, sy, r, shaderPaint);
        shaderPaint.setShader(null);

        // 7) 受光边缘弧（朝恒星那侧）
        final float litDeg = (float) (Math.atan2(uy, ux) * 180.0 / Math.PI);
        arcPaint.setPathEffect(null);
        arcPaint.setColor(Color.argb((int) (0.58f * 255), 255, 246, 220));
        arcPaint.setStrokeWidth(Math.max(dp(1.2f), r * 0.06f));
        arcPaint.setAlpha((int) (255 * alpha));
        canvas.drawArc(new RectF(sx - r * 0.985f, sy - r * 0.985f,
                sx + r * 0.985f, sy + r * 0.985f), litDeg - 66f, 132f, false, arcPaint);

        // 8) 行星环前半圈（压在本体下缘之上）
        if (offline) {
            drawPlanetRing(canvas, sx, sy, r, p, false);
        }

        // 9) 电平弧（有音频流且电平有效才画；过载时转琥珀色）
        if (streaming && !Double.isNaN(n.level)) {
            final double pct = Scene3D.levelPct(n.level);
            final float arcR = r + Math.max(dp(4), r * 0.16f);
            final RectF oval = new RectF(sx - arcR, sy - arcR, sx + arcR, sy + arcR);
            if (pct > 0.8) {
                arcPaint.setColor(COLOR_AMBER);
                arcPaint.setStrokeWidth(Math.max(dp(1.8f), r * 0.09f));
                arcPaint.setAlpha(235);
                canvas.drawArc(oval, -90f, (float) (pct * 360), false, arcPaint);
            } else {
                arcPaint.setColor(Color.argb(220, p.glowR, p.glowG, p.glowB));
                arcPaint.setStrokeWidth(Math.max(dp(1.6f), r * 0.08f));
                arcPaint.setAlpha(230);
                canvas.drawArc(oval, -90f, (float) (pct * 360), false, arcPaint);
            }
            arcPaint.setAlpha(255);
        }

        // 10) 离线：灰暗虚线外圈
        if (offline) {
            strokePaint.setPathEffect(dashRingOffline);
            strokePaint.setColor(Color.argb(107, 150, 165, 190));   // rgba(...,.42)
            strokePaint.setStrokeWidth(Math.max(dp(1.2f), r * 0.055f));
            canvas.drawCircle(sx, sy, r + Math.max(dp(4), r * 0.20f), strokePaint);
            strokePaint.setPathEffect(null);
        }

        // 11) 选中：虚线青色星环
        if (it.selected) {
            final float selR = r + Math.max(dp(7), r * 0.34f);
            glow(canvas, sx, sy, selR * 1.7f, 111, 224, 255, 0.18f, 0.04f);
            strokePaint.setPathEffect(dashSelected);
            strokePaint.setColor(0xD9C4F4FF);
            strokePaint.setStrokeWidth(dp(1.4f));
            canvas.drawCircle(sx, sy, selR, strokePaint);
            strokePaint.setPathEffect(null);
        }

        // 12) 文字：标签放在远离恒星的一侧（恒星在上 → 标签放下方，反之放上方，避免压住恒星）
        final boolean spaceAbove = sy - r - sp(20f) > dp(6);
        final boolean above = sy <= cy && spaceAbove;
        final float nameY = above ? sy - r - sp(6f) : sy + r + sp(16f);
        final float infoY = above ? nameY - sp(12f) : nameY + sp(14f);

        // 12a) 角标：在线设备与名称同一行右侧；离线设备贴左侧中部（按效果图）
        final String badge = badgeText(n);
        if (!badge.isEmpty()) {
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(sp(9f));
            textPaint.setColor(COLOR_HUB_BADGE);
            textPaint.setAlpha((int) (245 * alpha));
            final float textWidth = textPaint.measureText(badge);
            final float gap = r + dp(9);
            // 离线角标按效果图放左侧；左侧放不下翻到右侧；右侧也放不下就贴屏幕边。
            boolean left = offline;
            float bx = left ? sx - gap : sx + gap;
            if (left && bx - textWidth < dp(2)) {
                left = false;
                bx = sx + gap;
            }
            if (!left && bx + textWidth > w - dp(4)) {
                left = true;
                bx = sx - gap;
            }
            if (left && bx - textWidth < dp(2)) {
                bx = textWidth + dp(2);
            }
            final float by = offline
                    ? clamp(sy - r * 0.62f + sp(2f), sp(12f), h - dp(4))
                    : clamp(nameY, sp(12f), h - dp(4));
            textPaint.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            drawHaloText(canvas, badge, bx, by, textPaint);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        if (showName(it)) {
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(sp(11.5f));
            textPaint.setColor(it.selected ? COLOR_CYAN
                    : (offline ? COLOR_PLANET_NAME_OFF : COLOR_PLANET_NAME_ON));
            textPaint.setAlpha((int) Math.round(255 * Math.min(1.0, alpha + 0.15)));
            final String nameText = displayText(Scene3D.clampText(displayName(n), 12));
            drawHaloText(canvas, nameText, clampTextX(sx, nameText),
                    clamp(nameY, sp(12f), h - dp(4)), textPaint);
            textPaint.setTextSize(sp(10.5f));
            textPaint.setColor(offline ? COLOR_PLANET_INFO_OFF
                    : (streaming ? COLOR_PLANET_INFO_STREAM : COLOR_PLANET_INFO));
            textPaint.setAlpha((int) (255 * alpha));
            final String info = displayText(infoText(it));
            drawHaloText(canvas, info, clampTextX(sx, info),
                    clamp(infoY, sp(11f), h - dp(2)), textPaint);
        }
        textPaint.setAlpha(255);
        fillPaint.setAlpha(255);
    }

    /**
     * 行星环：旋转的扁椭圆。back=true 画后半圈（上半弧，在本体之前画 → 被本体挡住中段），
     * back=false 画前半圈（下半弧，在本体之后画 → 压在球体下缘，像环从球后绕到球前）。
     */
    private void drawPlanetRing(Canvas canvas, float sx, float sy, float r, Palette p,
                                boolean back) {
        canvas.save();
        canvas.rotate(-20.6f, sx, sy);
        final float rx = r * 2.0f;
        final float ry = r * 0.62f;
        final RectF oval = new RectF(sx - rx, sy - ry, sx + rx, sy + ry);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setPathEffect(null);
        // 环本体：后半圈更淡、前半圈更亮
        linePaint.setColor(Color.argb(back ? 58 : 104, p.glowR, p.glowG, p.glowB));
        linePaint.setStrokeWidth(Math.max(dp(1.2f), r * 0.10f));
        canvas.drawArc(oval, back ? 180f : 0f, 180f, false, linePaint);
        // 环内的一条细亮线（行星环的「冰晶」高光）
        linePaint.setColor(Color.argb(back ? 20 : 40, 255, 255, 255));
        linePaint.setStrokeWidth(Math.max(dp(0.8f), r * 0.035f));
        canvas.drawArc(oval, back ? 180f : 0f, 180f, false, linePaint);
        canvas.restore();
    }

    // ---------------------------------------------------------------------
    // 文本 / 角标 / 兜底
    // ---------------------------------------------------------------------

    /** 文字带深空色描边（星空背景下没有卡片托底也能读清）。 */
    private void drawHaloText(Canvas canvas, String text, float x, float y, Paint p) {
        final int savedColor = p.getColor();
        final Paint.Style savedStyle = p.getStyle();
        final float savedWidth = p.getStrokeWidth();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(clamp(p.getTextSize() * 0.18f, dp(1.4f), dp(2.6f)));
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Color.argb((int) (0.80f * 255), 4, 6, 15));
        canvas.drawText(text, x, y, p);
        p.setStyle(savedStyle);
        p.setStrokeWidth(savedWidth);
        p.setColor(savedColor);
        canvas.drawText(text, x, y, p);
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

    private String badgeText(Node n) {
        if (n.hub) {
            if (n.self) return "本机·汇聚端";
            return "汇聚端";
        }
        if (!n.online) return "离线";
        if (n.self) return "本机";
        return "";
    }

    /** 空图谱：原点画一个虚线恒星轮廓（柔光 + 虚线星芒）+ "?" + "正在等待设备…"。 */
    private void drawGhostHub(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        final float r = hubRadiusPx(w, h);
        glow(canvas, cx, cy, r * 2.3f, 210, 176, 120, 0.09f, 0.025f);

        // 还没点亮的恒星核心：只是一团很淡的金色
        fillPaint.setShader(null);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x14FFD27A);
        canvas.drawCircle(cx, cy, r * 0.95f, fillPaint);

        // 虚线星芒：4 长 + 4 短（轮廓感，避免像实心球）
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setPathEffect(dashOffline);
        linePaint.setColor(0x59FFD27A);
        linePaint.setStrokeWidth(dp(1.1f));
        for (int i = 0; i < 4; ++i) {
            final float ang = (float) (i * Math.PI / 2);
            final float dx = (float) Math.cos(ang);
            final float dy = (float) Math.sin(ang);
            canvas.drawLine(cx + dx * r * 0.60f, cy + dy * r * 0.60f,
                    cx + dx * r * 1.85f, cy + dy * r * 1.85f, linePaint);
        }
        for (int i = 0; i < 4; ++i) {
            final float ang = (float) (Math.PI / 4 + i * Math.PI / 2);
            final float dx = (float) Math.cos(ang);
            final float dy = (float) Math.sin(ang);
            canvas.drawLine(cx + dx * r * 0.55f, cy + dy * r * 0.55f,
                    cx + dx * r * 1.25f, cy + dy * r * 1.25f, linePaint);
        }
        linePaint.setPathEffect(null);

        // 虚线轮廓圆
        strokePaint.setPathEffect(dashOffline);
        strokePaint.setColor(0x73FFD27A);
        strokePaint.setStrokeWidth(dp(1.3f));
        canvas.drawCircle(cx, cy, r, strokePaint);
        strokePaint.setPathEffect(null);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(clamp(r * 0.30f, sp(9f), sp(13f)));
        textPaint.setColor(COLOR_DIM);
        textPaint.setAlpha(210);
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText("?", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(sp(11.5f));
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha(220);
        drawHaloText(canvas, "正在等待设备…", cx, cy + r + sp(20f), textPaint);
        textPaint.setAlpha(255);
    }

    // ---------------------------------------------------------------------
    // 光晕
    // ---------------------------------------------------------------------

    /** 径向柔光（peak@圆心 → mid@42% → 透明）。 */
    private void glow(Canvas canvas, float x, float y, float radius, int r, int g, int b,
                      float peak, float mid) {
        if (radius <= 0f) return;
        shaderPaint.setStyle(Paint.Style.FILL);
        shaderPaint.setShader(new RadialGradient(x, y, radius,
                new int[]{Color.argb((int) (peak * 255), r, g, b),
                        Color.argb((int) (mid * 255), r, g, b), 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        shaderPaint.setAlpha(255);
        canvas.drawCircle(x, y, radius, shaderPaint);
        shaderPaint.setShader(null);
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

    private static double clampDouble(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 居中文本的水平位置夹到视图内（避免贴边被切）。 */
    private float clampTextX(float x, String text) {
        final float half = textPaint.measureText(text) / 2f;
        final float min = half + dp(6);
        final float max = getWidth() - half - dp(6);
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
}
