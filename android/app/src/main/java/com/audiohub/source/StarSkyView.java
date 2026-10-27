package com.audiohub.source;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 星空背景（star sky）。v1.7 起整个 App 的底色从「深蓝仪表盘」换成「深空星野」：
 *
 *   · 深空垂直渐变：#070b1b → #05070f → #03040a；
 *   · 4 团星云（softGlow 径向渐变叠色：蓝紫 / 紫 / 青 / 品红）；
 *   · 一条斜贯屏幕的银河带（旋转的竖向渐变 + 620 颗沿带分布的微星）；
 *   · 三层主星（340 / 120 / 34 颗，越小越暗越多）+ 12 颗十字星芒亮星 +
 *     最亮 5 颗之间的星座连线 + 一颗流星 + 180 颗尘埃；
 *   · 四角暗角（veil），让底部卡片更压得住。
 *
 * 性能：星野是静态的（固定随机种子、不随刷新变化），所以整张星空一次性画进
 * 缓存 Bitmap，onDraw 只 drawBitmap；尺寸变化（横竖屏切换/分屏）才重建。
 * 种子固定 → 每次启动星图一致，不会闪。
 *
 * 颜色表按 docs/starfield-ui-preview 的设计令牌（--bg-0 #04060f 等）。
 */
public class StarSkyView extends View {

    /** 固定种子：保证每次启动的星空一样（也方便对照效果图）。 */
    private static final int SEED = 20251027;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap sky;
    private int skyW;
    private int skyH;

    public StarSkyView(Context context) {
        super(context);
        init();
    }

    public StarSkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 纯背景层：不拦截触摸（触摸要落到上面的图/卡片上）
        setClickable(false);
        setFocusable(false);
        bitmapPaint.setFilterBitmap(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild(w, h);
    }

    @SuppressWarnings("deprecation")
    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (sky != null && w == skyW && h == skyH) return;
        final Bitmap next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(next);
        paintSky(c, w, h);
        final Bitmap old = sky;
        sky = next;
        skyW = w;
        skyH = h;
        if (old != null && !old.isRecycled()) old.recycle();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (sky == null || sky.isRecycled() || w != skyW || h != skyH) rebuild(w, h);
        if (sky != null && !sky.isRecycled()) {
            canvas.drawBitmap(sky, 0f, 0f, bitmapPaint);
        } else {
            canvas.drawColor(0xFF04060F);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 只释放位图；View 若被重新 attach，onDraw 会按当前尺寸重建
        final Bitmap old = sky;
        sky = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    // =====================================================================
    // 星野绘制
    // =====================================================================

    private void paintSky(Canvas c, int w, int h) {
        final float d = density();
        final Rng rnd = new Rng(SEED);
        final float max = Math.max(w, h);

        // 1) 深空底色
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF070B1B, 0xFF05070F, 0xFF03040A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // 2) 星云（蓝紫 / 紫 / 青 / 品红）
        softGlow(c, w * 0.16f, h * 0.16f, max * 0.55f, 78, 104, 196, 0.20f, 0.060f);
        softGlow(c, w * 0.82f, h * 0.10f, max * 0.46f, 126, 92, 214, 0.16f, 0.050f);
        softGlow(c, w * 0.74f, h * 0.74f, max * 0.52f, 44, 128, 168, 0.13f, 0.040f);
        softGlow(c, w * 0.30f, h * 0.84f, max * 0.42f, 150, 74, 132, 0.09f, 0.030f);

        // 3) 银河带：旋转坐标系后画一条竖向渐变，再沿带撒 620 颗微星
        final float bandW = max * 0.72f;
        c.save();
        c.translate(w * 0.52f, h * 0.46f);
        c.rotate(-0.44f * 180f / (float) Math.PI);
        paint.setShader(new LinearGradient(0, -bandW * 0.5f, 0, bandW * 0.5f,
                new int[]{0x00A0AAFF, 0x0BA0AAFF, 0x14B0BEFF, 0x14B0BEFF, 0x0BA0AAFF, 0x00A0AAFF},
                new float[]{0f, 0.22f, 0.44f, 0.56f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(-max, -bandW * 0.5f, max, bandW * 0.5f, paint);
        paint.setShader(null);
        for (int i = 0; i < 620; ++i) {
            final float x = (rnd.next() * 2f - 1f) * max;
            final float y = (rnd.next() + rnd.next() + rnd.next() - 1.5f) * bandW * 0.28f;
            final float r = 0.35f * d + rnd.next() * 0.7f * d;
            paint.setColor(Color.argb((int) (0.10f + rnd.next() * 0.42f),
                    190 + (int) (rnd.next() * 50), 205 + (int) (rnd.next() * 40), 255));
            c.drawCircle(x, y, r, paint);
        }
        c.restore();

        // 4) 三层主星：越亮越少也越大
        final int[] counts = {340, 120, 34};
        final float[] rBase = {0.45f, 0.80f, 1.25f};
        final float[] aBase = {0.45f, 0.62f, 0.85f};
        for (int layer = 0; layer < counts.length; ++layer) {
            for (int i = 0; i < counts[layer]; ++i) {
                final float x = rnd.next() * w;
                final float y = rnd.next() * h;
                final float r = (rBase[layer] * (0.55f + rnd.next() * 1.1f)) * d;
                final float a = aBase[layer] * (0.35f + rnd.next() * 0.65f);
                final int pick = (int) (rnd.next() * 10f);
                final int cr = pick < 8 ? 232 : 255;
                final int cg = pick < 8 ? 240 : 228;
                final int cb = pick < 8 ? 255 : 186;
                paint.setColor(Color.argb((int) (a * 255), cr, cg, cb));
                c.drawCircle(x, y, r, paint);
            }
        }

        // 5) 12 颗十字星芒亮星（前 5 颗还要连成星座）
        final float[] bx = new float[12];
        final float[] by = new float[12];
        for (int i = 0; i < 12; ++i) {
            bx[i] = (0.05f + rnd.next() * 0.90f) * w;
            by[i] = (0.05f + rnd.next() * 0.90f) * h;
            final float r = (1.5f + rnd.next() * 2.0f) * d;
            final float a = 0.55f + rnd.next() * 0.35f;
            drawCrossStar(c, bx[i], by[i], r, a);
        }
        // 星座连线（最亮 5 颗之间，很淡）
        linePaintForSky(Color.argb(23, 160, 190, 255), 1f * d);
        for (int i = 0; i < 4; ++i) {
            c.drawLine(bx[i], by[i], bx[i + 1], by[i + 1], paint);
        }

        // 6) 一颗流星（屏幕右上方，斜向下划）
        c.save();
        c.translate(w * 0.84f, h * 0.20f);
        c.rotate(2.58f);
        final float len = 260f * d;
        paint.setShader(new LinearGradient(-len, 0f, 0f, 0f,
                new int[]{0x00D6E4FF, 0x33DCE8FF, 0xCCEFF4FF},
                new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(-len, 0f, 0f, 0f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6F4F8FF);
        c.drawCircle(0f, 0f, 2.1f * d, paint);
        c.restore();

        // 7) 180 颗尘埃（很小的方块，制造颗粒感）
        for (int i = 0; i < 180; ++i) {
            final float x = rnd.next() * w;
            final float y = rnd.next() * h;
            final float s = 0.7f * d;
            paint.setColor(Color.argb((int) ((0.08f + rnd.next() * 0.24f) * 255), 210, 222, 255));
            c.drawRect(x, y, x + s, y + s, paint);
        }

        // 8) 四角暗角（veil）
        paint.setShader(new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w, h) * 0.55f,
                new int[]{0x00000000, 0x00000000, 0x5C02040A},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void drawCrossStar(Canvas c, float x, float y, float r, float alpha) {
        // 星点核心 + 柔和光晕
        softGlow(c, x, y, r * 5.0f, 210, 226, 255, 0.30f * alpha, 0.08f * alpha);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (0.95f * alpha * 255), 255, 255, 255));
        c.drawCircle(x, y, r * 0.75f, paint);
        // 四向星芒（横长竖短）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(r * 0.55f);
        paint.setColor(Color.argb((int) (0.55f * alpha * 255), 225, 238, 255));
        c.drawLine(x - r * 4.2f, y, x + r * 4.2f, y, paint);
        paint.setColor(Color.argb((int) (0.42f * alpha * 255), 225, 238, 255));
        c.drawLine(x, y - r * 2.6f, x, y + r * 2.6f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 径向柔光：中心 peak、42% 处 mid、边缘透明。 */
    private void softGlow(Canvas c, float x, float y, float radius, int r, int g, int b,
                          float peak, float mid) {
        if (radius <= 0f) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(x, y, radius,
                new int[]{Color.argb((int) (peak * 255), r, g, b),
                        Color.argb((int) (mid * 255), r, g, b),
                        0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
        paint.setShader(null);
    }

    private void linePaintForSky(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /**
     * mulberry32 伪随机（与 docs/starfield-ui-preview/starfield.js 同一算法）：
     * 固定种子 → 每次启动画出来的星野完全一致。Java int 乘法本身就按 32 位回绕，
     * 与 Math.imul 等价。
     */
    static final class Rng {
        private int a;

        Rng(int seed) {
            a = seed;
        }

        /** [0, 1) */
        float next() {
            a += 0x6D2B79F5;
            int t = a;
            t = (t ^ (t >>> 15)) * (t | 1);
            t = (t + (t ^ (t >>> 7)) * 61) ^ t;
            return ((t ^ (t >>> 14)) >>> 0) / 4294967296f;
        }
    }
}
