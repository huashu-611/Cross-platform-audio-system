package com.audiohub.source;

/**
 * 俯视图「视野自适应」的纯数学部分（不 import android.*，可以用普通 JDK 单独编译/验证）。
 *
 * 输入：画布像素尺寸、顶部状态文字预留带、每个节点的世界坐标与球半径规则（米→像素，带上下限）、
 * 标签占用的像素尺寸；输出：1 米 = 多少像素（ppu）与对应的视野半径。
 *
 * 约束（必须与 {@link HubGraphView} 的绘制一致）：
 *   · 正交映射：屏幕中心是听者/hub 原点，sx = cx + wx*ppu，sy = cy + wz*ppu；
 *   · 画布四边各留约 12% 边距，最远节点「连球半径与标签」也要落在里面；
 *   · 视野半径正常 ∈ [2.6 m, 640 m]：近处不比原来的 2.6 m 更大（避免 1 m 房间巨大化），
 *     远处最多缩到 ±320 m 坐标上限的对角（≈452 m）也装得下；标签是固定像素，
 *     窄画布 + 远处节点时允许再缩一点（硬上限见 {@link #HARD_MAX_VIEW_RADIUS_M}）；
 *   · 球半径有像素上下限，所以缩放解是 ppu 的不动点，迭代几轮收敛。
 */
public final class GraphFit {

    /** 最小视野半径（米）：全都在近处时也不放大到比这更近。 */
    public static final float MIN_VIEW_RADIUS_M = 2.6f;
    /** 最大视野半径（米）：±320 m 坐标上限的对角 ≈452 m，正常缩放下限给到 640 m。 */
    public static final float MAX_VIEW_RADIUS_M = 640.0f;
    /**
     * 硬上限视野半径（米）：标签是固定像素，窄画布 + 远处节点时 ppu 可能得比 640 m 视野更小
     * 才能把标签也装进去 —— 这时以「装得下/看得见」优先，但不能无限缩，留个硬下限防病态输入。
     */
    public static final float HARD_MAX_VIEW_RADIUS_M = 4096.0f;
    /** 画布四边各留的边距比例（相对整条边）。 */
    public static final float MARGIN_FRACTION = 0.12f;

    private GraphFit() {
    }

    /** 参与拟合的一个节点：世界坐标 + 球半径（米→像素）规则。 */
    public static final class Item {
        /** 世界坐标 x（米，右正）。 */
        public final float wx;
        /** 世界坐标 z（米，屏幕下方正；正前方为 -Z）。 */
        public final float wz;
        /** 球体在世界里的半径（米）。 */
        public final float ballRadiusM;
        /** 球半径像素下限（远景别缩没了）。 */
        public final float ballMinPx;
        /** 球半径像素上限（近景别撑爆）。 */
        public final float ballMaxPx;

        public Item(float wx, float wz, float ballRadiusM, float ballMinPx, float ballMaxPx) {
            this.wx = wx;
            this.wz = wz;
            this.ballRadiusM = ballRadiusM;
            this.ballMinPx = ballMinPx;
            this.ballMaxPx = ballMaxPx;
        }

        /** 当前 ppu 下球半径的像素值（与绘制用的 clamp 一致）。 */
        public float radiusPx(float ppu) {
            final float r = ballRadiusM * ppu;
            return r < ballMinPx ? ballMinPx : (r > ballMaxPx ? ballMaxPx : r);
        }
    }

    /** 拟合结果。 */
    public static final class Result {
        /** 1 米 = 多少像素。 */
        public final float ppu;
        /** 视野半径（米，取画布较短方向，用于左下角比例尺提示）。 */
        public final float viewRadiusM;

        Result(float ppu, float viewRadiusM) {
            this.ppu = ppu;
            this.viewRadiusM = viewRadiusM;
        }
    }

    /**
     * 计算自适应缩放。
     *
     * @param widthPx       画布宽（像素）
     * @param heightPx      画布高（像素）
     * @param topReservePx  顶部状态文字预留带（像素）
     * @param items         节点列表（null/空 = 没有节点，按近景 2.6 m 视野画）
     * @param labelHalfWpx  标签水平半宽（像素，名称/角标）
     * @param labelVpx      标签垂直占用（像素，名称 + 第二行信息/角标）
     */
    public static Result fit(float widthPx, float heightPx, float topReservePx,
            Item[] items, float labelHalfWpx, float labelVpx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return new Result(1.0f, MAX_VIEW_RADIUS_M);
        }
        final float usableH = Math.max(1f, heightPx - Math.max(0f, topReservePx));
        // 四边各留 MARGIN_FRACTION（相对整条边），所以半可用长度 = 边半长 - 边距。
        final float halfW = Math.max(1f, widthPx * (0.5f - MARGIN_FRACTION));
        final float halfH = Math.max(1f, usableH * (0.5f - MARGIN_FRACTION));
        final float minHalf = Math.min(halfW, halfH);
        final float comfortable = minHalf / MIN_VIEW_RADIUS_M;      // ppu 上限：近景观感
        final float hardMinPpu = minHalf / HARD_MAX_VIEW_RADIUS_M;  // ppu 硬下限：防病态输入

        float ppu = comfortable;
        if (items != null) {
            // 球半径是 ppu 的函数（带像素上下限），做几轮不动点迭代：每轮按当前 ppu 求
            // 「恰好把该节点塞进画布」的 ppu，取最紧的那个；通常 2~3 轮就收敛。
            for (int iter = 0; iter < 8; ++iter) {
                float next = comfortable;
                for (Item it : items) {
                    if (it == null) continue;
                    final float rpx = it.radiusPx(ppu);
                    final float ax = Math.abs(it.wx);
                    final float az = Math.abs(it.wz);
                    if (ax > 1e-4f) {
                        final float allow = halfW - labelHalfWpx - rpx;
                        if (allow <= 0f) {
                            next = 0f;
                            break;
                        }
                        next = Math.min(next, allow / ax);
                    }
                    if (az > 1e-4f) {
                        final float allow = halfH - labelVpx - rpx;
                        if (allow <= 0f) {
                            next = 0f;
                            break;
                        }
                        next = Math.min(next, allow / az);
                    }
                }
                if (next <= 0f) {
                    // 画布小到连标签盒都放不下：退到硬下限，画的时候文本还有 clamp 兜底。
                    ppu = hardMinPpu;
                    break;
                }
                if (Math.abs(next - ppu) < 1e-3f) {
                    ppu = next;
                    break;
                }
                ppu = next;
            }
        }
        if (ppu > comfortable) ppu = comfortable;
        if (ppu < hardMinPpu) ppu = hardMinPpu;
        return new Result(ppu, minHalf / ppu);
    }
}