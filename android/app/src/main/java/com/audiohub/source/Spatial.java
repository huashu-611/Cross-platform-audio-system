package com.audiohub.source;

/**
 * 3D 空间化：把一路立体声按"声源/听者位姿"渲染成带方位与距离感的立体声。
 *
 * 这是 C++ 权威实现（include/audiohub/spatial.hpp + src/spatial.cpp）的**逐公式 Java 移植**，
 * 供 Android 汇聚端（{@link ReceiverService} 的 playLoop）使用。公式、常数、处理顺序与 C++
 * 一一对应，两端必须算出同样的结果 —— 改这里等于改跨端协议。
 *
 * 为什么单独成文件（和 C++ 侧同样的取舍）：
 *   1. 纯 DSP、不 import android.*，所以能在这台没有 Android SDK 的机器上用 JDK 8 的
 *      javac + 普通 main 直接编译运行（见 android/verify/SpatialCheck.java 与
 *      android/verify/run-spatial-check.ps1）；
 *   2. 汇聚端 C++ 与 Android 的公式只能有一份权威定义：这里逐行照抄，不"顺手优化"。
 *
 * 坐标系（世界坐标；UI 与 C++ 必须一致）：
 *   右手系，+Y 向上，1 unit ≈ 1 m。
 *   听者 yaw=0 面朝 -Z：
 *     forward = (-sin(yaw)*cos(pitch),  sin(pitch), -cos(yaw)*cos(pitch))
 *     right   = ( cos(yaw),               0,          -sin(yaw) )
 *     up      = ( sin(yaw)*sin(pitch),   cos(pitch),  cos(yaw)*sin(pitch) )
 *   world = listener.position + right*右 + up*上 + forward*前。
 *
 * 听者位置：v1.3 物理房间模型下 Android 汇聚端的听者固定在场景原点、yaw=0、pitch=0
 * （与 PC 端 SoundStage 一致）；UI 只把节点的世界坐标（米，y=0）报给 ReceiverService。
 * 本文件仍保留 {@link ListenerPose} 与 yaw/pitch 参数，是因为公式是跨端共用的，
 * 而且 desktop 侧 UI 允许相机 orbit 改 yaw（声音随视角旋转）。
 *
 * 声像：pan = sin(clamp(az*kAz, -180, 180))，等功率
 *       gL = sqrt((1-pan)/2)，gR = sqrt((1+pan)/2)（gL^2+gR^2 = 1）。
 *   sin 在整圆上给出正确的左右符号与两侧对称：az=±90 最偏、az=±135 回收到 ±0.707、
 *   az=180（正后方）回到中置 —— 后半球不会被饱和成硬左/硬右，正后方只靠后向线索区分。
 *
 * 前后（v0.46，四线索）：立体声两声道没有 HRTF 卷积时无法可靠区分前/后。
 *   本轮把两条弱线索扩成四条，全部走同一个后向因子 rear = max(0, -cos az)：
 *   ① 电平：后向增益 kRearGain=0.7（约 -3.1 dB）；
 *   ② 闷化：一阶低通从 20 kHz 随 rear 降到 6 kHz（v0.46 由 4 kHz 放宽，
 *      4 kHz 会把"身后"糊成同一团低频，后 vs 后右 反而更难分）；
 *   ③ 耳廓陷波：二阶带通并联出的宽带凹陷，中心随 rear 从 6 kHz 抬到 8 kHz，
 *      正后方挖 15 dB（Q=1.6，-3 dB 带宽约 5 kHz）；
 *   ④ 早期反射：两路延迟副本（7.5~11 ms / 12.4~18.2 ms），随 rear 变长变响，
 *      合计约 +3.5 dB 湿声，模拟墙面/肩部反射。
 *   另外 rear 本身做了对比度拉伸（kRearContrastExponent=0.55），否则 135° 只有
 *   0.707 的力度、与 45° 几乎无法区分。
 *
 *   rear=0（正前/正侧）时后向链**逐位透明**：beta=0、dull=0、湿声增益=0，
 *   输出与"没有后向链"完全一致 —— 前方音色一点不动。
 *
 *   仍做不到：锥形混淆（前上 vs 后下）、俯仰定位、个体耳廓差异、单声道兼容
 *   （后向线索在 mono 折叠时会部分抵消）。真要前后定位需要双耳 HRTF 卷积或
 *   多声道输出，本轮不做。
 */
public final class Spatial {

    private Spatial() {
        // 纯静态工具类：纯函数挂在这里，每源状态机是嵌套的 SpatialState。
    }

    // ------------------------------------------------ 常数（跨端一致；与 C++ 逐字一致）
    //
    // 注意：这些数都是 C++ `constexpr` 的镜像，**数值不许改**。SpatialCheck 会把它们
    // 逐条断言一遍，确保将来没人"顺手调一下"。

    public static final float kPi = 3.14159265358979323846f;
    public static final float kDegToRad = kPi / 180.0f;
    public static final float kRadToDeg = 180.0f / kPi;

    /** 节点默认圆环半径（只给 defaultScenePlacement 用）。 */
    public static final float kSceneRadius = 1.0f;
    /**
     * 距离衰减的参考距离 r0（v0.45 从 kSceneRadius 拆出，v0.46 由 0.1 m 改为 0.3 m）。
     * 以前与 kSceneRadius 共用一个 1.0，改衰减会顺带把默认摆位圆环也改掉。
     */
    public static final float kDistanceRefM = 0.3f;
    /** UI 默认相机距离（只给 UI 用，DSP 不读它）。 */
    public static final float kDefaultCameraRadius = 2.6f;
    /** 人最大双耳时间差 ≈0.63 ms @48k ≈30.2 帧。 */
    public static final float kMaxItdSeconds = 0.00063f;
    /** 方位增强系数（1.0 = 物理正确；本布局不需要增强）。 */
    public static final float kAzimuthBoost = 1.0f;
    /** √2：单声道化后每声道补 -3 dB。 */
    public static final float kMonoMakeup = 1.41421356f;
    /** 正后方增益 ≈ -3.1 dB（弱线索）。 */
    public static final float kRearGain = 0.7f;
    /** 正前方一阶低通截止。 */
    public static final float kRearLpFrontHz = 20000.0f;
    /**
     * 正后方一阶低通截止。v0.46：4 kHz 放宽到 6 kHz。
     * 4 kHz 的一阶低通把后向压得太闷，所有"身后"声音都变成同一团低频糊，
     * "后 vs 后右"反而更难分；放宽后判决权交给陷波（③）与反射（④）。
     */
    public static final float kRearLpBackHz = 6000.0f;
    /**
     * v0.46 ③ 耳廓陷波：二阶带通并联出的宽带凹陷（{@code y = x + beta*bp(x)}），
     * 中心随 rear 从 6 kHz 抬到 8 kHz，正后方挖 15 dB。这是耳廓梳状响应随入射角
     * 移动的主瓣位置 —— "越低越偏后"是选这条映射的听感依据。
     */
    public static final float kRearNotchFrontHz = 6000.0f;
    public static final float kRearNotchBackHz = 8000.0f;
    /** v0.46b：12 -> 15 dB（斜后方也要听得出来）。 */
    public static final float kRearNotchDepthDb = 15.0f;
    /**
     * v0.46b：Q 2.5 -> 1.6。Q=2.5 的坑只有 ±1.2 kHz 宽，宽带音乐里很容易被相邻
     * 频段填满；放宽后是一条"presence 区整体发闷"的宽带凹陷，更像耳廓效应、也更抗掩蔽。
     */
    public static final float kRearNotchQ = 1.6f;
    /**
     * v0.46b ④ 后向对比度拉伸。裸 rear = max(0, -cos az) 在「斜后方」只有 0.707（135°），
     * 于是陷波/反射/闷化全都只按七成力度生效 —— 实测 135° 与 45° 只差约 2 dB，人耳分不出来。
     * 指数 0.55 把 135° 抬到 0.83、120° 抬到 0.68，两端（0°/180°）保持不变。
     */
    public static final float kRearContrastExponent = 0.55f;
    /**
     * v0.46 ④ 早期反射：两路延迟副本，rear 越大越长、越响。
     * 第一路是"同侧墙面/肩部"近反射，第二路是"对侧墙"远反射；两路不成简单整数比，
     * 避免并入同一个梳状峰。
     */
    public static final float kRearEr1DelayFrontMs = 7.5f;
    public static final float kRearEr1DelayBackMs = 11.0f;
    public static final float kRearEr2DelayFrontMs = 12.4f;
    public static final float kRearEr2DelayBackMs = 18.2f;
    /** v0.46b：0.26 -> 0.34（反射更强）。 */
    public static final float kRearEr1Gain = 0.34f;
    /** v0.46b：0.12 -> 0.16（两路合计 0.50，约 +3.5 dB 湿声）。 */
    public static final float kRearEr2Gain = 0.16f;
    /** 反射路径的一阶低通（墙面/肩部吸高频）。 */
    public static final float kRearErLpHz = 2500.0f;
    /** 增益/声像平滑时间常数（50 ms）。 */
    public static final float kSmoothTauSec = 0.05f;
    /** ITD 每块（5 ms）最多变 2 帧，防爆音。 */
    public static final float kMaxDelaySlewFrames = 2.0f;
    /**
     * 每源历史。v0.46：64 -> 1024。最长反射 18.2 ms @48k ≈ 874 帧，还要 2 帧插值余量；
     * 取 1024（2 的幂，取模是掩码级开销）。状态从 512 B 涨到 8 KB/源，可忽略。
     */
    public static final int kSpatialHistoryFrames = 1024;
    /** P0 只用 [0]（单声道化后）；[1] 保留 side 通路。 */
    public static final int kSpatialHistoryChannels = 2;
    /** 距离下限，防除零（= r0，靠得再近也不再变响）。 */
    public static final float kMinDistanceM = 0.3f;
    /** 100% 封顶（v0.45 取消原来的 +6 dB 近场升压，r0 处正好满）。 */
    public static final float kMaxDistanceGain = 1.0f;
    /**
     * 距离衰减指数（v0.46）：C++ 的 kDistanceExponent，跨端必须一致。
     * 口径：0.3 m = 100%，2.0 m = 1% → p = ln(0.01)/ln(0.3/2.0) = 2.4274533。
     */
    public static final float kDistanceExponent = 2.4274533f;
    /** 世界坐标绝对值上限 ±320 m（= POS 帧 ±32000 cm；与内核/协议双重校验一致）。 */
    public static final float kSpatialCoordLimit = 320.0f;
    /** 内部采样率（与 C++ kSampleRate、PacketProtocol.SAMPLE_RATE 一致）。 */
    public static final int kSampleRate = 48000;

    private static final float kHalfPi = kPi * 0.5f;
    private static final float kSqrtHalf = 0.70710678f;

    // ------------------------------------------------ 纯数据类型

    /** 世界坐标向量（米）。C++ 侧是可变 struct；Java 侧做成不可变，跨线程读更安全。 */
    public static final class Vec3 {
        public final float x;
        public final float y;
        public final float z;

        public Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static final Vec3 ZERO = new Vec3(0.0f, 0.0f, 0.0f);

        @Override
        public String toString() {
            return "Vec3(" + x + ", " + y + ", " + z + ")";
        }
    }

    /** 听者位姿。P0 = 相机位姿（眼位）；Android 汇聚端固定用 {@link #defaultListener()}。 */
    public static final class ListenerPose {
        public final Vec3 position;
        /** 0 = 面朝 -Z；俯视时顺时针为正。 */
        public final float yawDeg;
        /** + = 抬头。 */
        public final float pitchDeg;

        public ListenerPose(Vec3 position, float yawDeg, float pitchDeg) {
            this.position = position != null ? position : Vec3.ZERO;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
        }

        @Override
        public String toString() {
            return "ListenerPose(" + position + ", yaw=" + yawDeg + ", pitch=" + pitchDeg + ")";
        }
    }

    /** 极坐标几何量：方位角/仰角/距离。 */
    public static final class Geo {
        /** -180..180，+ = 右。 */
        public final float azimuthDeg;
        /** -90..90，+ = 上。 */
        public final float elevationDeg;
        /** 3D 欧氏距离（米）。 */
        public final float distance;

        public Geo(float azimuthDeg, float elevationDeg, float distance) {
            this.azimuthDeg = azimuthDeg;
            this.elevationDeg = elevationDeg;
            this.distance = distance;
        }

        @Override
        public String toString() {
            return "Geo(az=" + azimuthDeg + ", el=" + elevationDeg + ", d=" + distance + ")";
        }
    }

    /** DSP 参数组（与 C++ SpatialParams 默认值一致）。 */
    public static final class SpatialParams {
        public final float azimuthBoost;
        public final float smoothTauSec;
        public final float maxDelaySlewFrames;

        public SpatialParams() {
            this(kAzimuthBoost, kSmoothTauSec, kMaxDelaySlewFrames);
        }

        public SpatialParams(float azimuthBoost, float smoothTauSec, float maxDelaySlewFrames) {
            this.azimuthBoost = azimuthBoost;
            this.smoothTauSec = smoothTauSec;
            this.maxDelaySlewFrames = maxDelaySlewFrames;
        }
    }

    /** 缺省参数（避免 process 里每块 new 一个）。 */
    private static final SpatialParams DEFAULT_PARAMS = new SpatialParams();

    // ------------------------------------------------ 纯函数（全部可单测，不碰 DSP 状态）

    /** 世界坐标 -> 听者局部坐标 (x=右, y=上, z=前)。C++: ToListenerFrame。 */
    public static Vec3 toListenerFrame(Vec3 world, ListenerPose listener) {
        final float yaw = listener.yawDeg * kDegToRad;
        final float pitch = listener.pitchDeg * kDegToRad;
        final float sy = (float) Math.sin(yaw);
        final float cy = (float) Math.cos(yaw);
        final float sp = (float) Math.sin(pitch);
        final float cp = (float) Math.cos(pitch);

        final float rightX = cy;
        final float rightY = 0.0f;
        final float rightZ = -sy;
        final float upX = sy * sp;
        final float upY = cp;
        final float upZ = cy * sp;
        final float forwardX = -sy * cp;
        final float forwardY = sp;
        final float forwardZ = -cy * cp;

        final Vec3 rel = new Vec3(world.x - listener.position.x,
                world.y - listener.position.y,
                world.z - listener.position.z);
        return new Vec3(rel.x * rightX + rel.y * rightY + rel.z * rightZ,
                rel.x * upX + rel.y * upY + rel.z * upZ,
                rel.x * forwardX + rel.y * forwardY + rel.z * forwardZ);
    }

    /**
     * 便捷重载：听者位置固定原点（Android 汇聚端 P0 用法）。
     * 与 C++ 的 ToListenerFrame(world, DefaultListener() + yaw/pitch) 等价。
     */
    public static Vec3 toListenerFrame(Vec3 world, float yawDeg, float pitchDeg) {
        return toListenerFrame(world, new ListenerPose(Vec3.ZERO, yawDeg, pitchDeg));
    }

    /** 世界坐标 -> {方位角, 仰角, 距离}。C++: AnalyzePosition。 */
    public static Geo analyzePosition(Vec3 world, ListenerPose listener) {
        final Vec3 l = toListenerFrame(world, listener);
        final float distance = (float) Math.sqrt(l.x * l.x + l.y * l.y + l.z * l.z);
        final float horiz = (float) Math.sqrt(l.x * l.x + l.z * l.z);
        final float azimuthDeg = (float) (Math.atan2(l.x, l.z) * kRadToDeg);
        final float elevationDeg;
        if (horiz > 1e-6f) {
            elevationDeg = (float) (Math.atan2(l.y, horiz) * kRadToDeg);
        } else {
            elevationDeg = l.y >= 0.0f ? 90.0f : -90.0f;
        }
        return new Geo(azimuthDeg, elevationDeg, distance);
    }

    /** 便捷重载：听者固定在原点、面朝 -Z（Android 汇聚端默认）。 */
    public static Geo analyzePosition(Vec3 world) {
        return analyzePosition(world, defaultListener());
    }

    /** 便捷重载：听者固定在原点，只给朝向。 */
    public static Geo analyzePosition(Vec3 world, float yawDeg, float pitchDeg) {
        return analyzePosition(world, new ListenerPose(Vec3.ZERO, yawDeg, pitchDeg));
    }

    /** pan = sin(clamp(az*kAz, -180, 180)) ∈ [-1, 1]。C++: PanValue。 */
    public static float panValue(float azimuthDeg, float azimuthBoost) {
        // 整圆 clamp 到 ±180，再取 sin：az=±90 最偏，az=±135 回收到 ±0.707，az=180 回中置。
        final float a = clampDeg(azimuthDeg * azimuthBoost);
        return (float) Math.sin(a * kDegToRad);
    }

    /**
     * 等功率声像增益：gL=sqrt((1-pan)/2)，gR=sqrt((1+pan)/2)，0..1。
     * 写入 outLR[0]=gL、outLR[1]=gR；outLR 为 null 或太短时忽略（对应 C++ 允许空指针）。
     */
    public static void panGains(float azimuthDeg, float azimuthBoost, float[] outLR) {
        final float pan = panValue(azimuthDeg, azimuthBoost);
        if (outLR != null && outLR.length >= 2) {
            outLR[0] = (float) Math.sqrt(0.5f * (1.0f - pan));
            outLR[1] = (float) Math.sqrt(0.5f * (1.0f + pan));
        }
    }

    /** 等功率声像增益的数组返回形式（测试/UI 用，热路径请用 outLR 重载避免分配）。 */
    public static float[] panGains(float azimuthDeg, float azimuthBoost) {
        final float[] out = new float[2];
        panGains(azimuthDeg, azimuthBoost, out);
        return out;
    }

    /**
     * 距离衰减（v0.46 口径，与 C++ DistanceGain 逐位一致）。
     *   g = min((r0 / max(d, kMinDistanceM))^p, kMaxDistanceGain)
     *   r0 = kDistanceRefM = 0.3 m，p = 2.4274533，封顶 1.0
     *   d≤0.3 -> 100%(0 dB)；d=0.5 -> 28.9%(-10.8 dB)；d=0.75 -> 10.8%(-19.3 dB)；
     *   d=1.0 -> 5.4%(-25.4 dB)；d=2.0 -> 1.0%(-40.0 dB)；d=3.0 -> 0.37%(-48.6 dB)。
     */
    public static float distanceGain(float distance) {
        final float d = Math.max(distance, kMinDistanceM);
        // 距离即音量：只有近场封顶，没有远场下限（见 C++ spatial.hpp）。
        return Math.min((float) Math.pow(kDistanceRefM / d, kDistanceExponent), kMaxDistanceGain);
    }

    /**
     * 双耳时间差（秒，正 = 声源在右，右耳领先；正后方 sin(180°)=0 回落到 0，整圆连续）。
     */
    public static float itdSeconds(float azimuthDeg) {
        // 整圆连续：sin(±90°)=±1 最大；sin(180°)=0，正后方 ITD 回落（不要截到 90°）。
        return (float) Math.sin(clampDeg(azimuthDeg) * kDegToRad) * kMaxItdSeconds;
    }

    /** 后向因子：0 = 正前，1 = 正后（v0.46b 带对比度拉伸）。 */
    public static float rearFactor(float azimuthDeg) {
        final float c = clamp(-((float) Math.cos(clampDeg(azimuthDeg) * kDegToRad)), 0.0f, 1.0f);
        // v0.46b：对比度拉伸，让"斜后方"也拿到足够的线索强度（见 kRearContrastExponent）。
        //   两端不动：az=0 -> 0，az=180 -> 1；az=135 从 0.707 抬到 0.826。
        return (float) Math.pow(c, kRearContrastExponent);
    }

    /** 后向低通截止：rear=0 -> 20 kHz，rear=1 -> 6 kHz（在 log 频率上插值）。 */
    public static float rearCutoffHz(float rear) {
        final float r = clamp(rear, 0.0f, 1.0f);
        // 在 log 频率上插值，听感更均匀。
        return kRearLpFrontHz * (float) Math.pow(kRearLpBackHz / kRearLpFrontHz, r);
    }

    /** 耳廓陷波中心：rear=0 -> 6 kHz，rear=1 -> 8 kHz（log 插值，约 1/3 倍频程）。 */
    public static float rearNotchHz(float rear) {
        final float r = clamp(rear, 0.0f, 1.0f);
        return kRearNotchFrontHz * (float) Math.pow(kRearNotchBackHz / kRearNotchFrontHz, r);
    }

    /** 早期反射延迟（秒）：stage=0 走 Er1 那组，否则 Er2；随 rear 线性变长。 */
    public static float rearReflectionDelaySeconds(float rear, int stage) {
        final float r = clamp(rear, 0.0f, 1.0f);
        final float frontMs = stage == 0 ? kRearEr1DelayFrontMs : kRearEr2DelayFrontMs;
        final float backMs = stage == 0 ? kRearEr1DelayBackMs : kRearEr2DelayBackMs;
        return (frontMs + (backMs - frontMs) * r) * 0.001f;
    }

    /**
     * RBJ cookbook 二阶带通（constant 0 dB peak）系数。b1 恒 0、b2 = -b0，
     * 所以陷波用「并联」形式 y = x + beta*bp(x)：极点在 a1/a2 里，beta 怎么扫都不破坏稳定，
     * 且 beta=0 时 y 逐位等于 x（rear=0 时后向链完全透明）。
     */
    public static void bandPassCoeffs(float sampleRate, float freqHz, float q,
            float[] out) {
        final float f = clamp(freqHz, 20.0f, 0.45f * sampleRate);
        final float w0 = 2.0f * kPi * f / sampleRate;
        final float cw = (float) Math.cos(w0);
        final float alpha = (float) Math.sin(w0) / (2.0f * q);
        final float inv = 1.0f / (1.0f + alpha);   // 1/a0
        out[0] = alpha * inv;                      // b0
        out[1] = 0.0f;                             // b1
        out[2] = -alpha * inv;                     // b2
        out[3] = (-2.0f * cw) * inv;               // a1
        out[4] = (1.0f - alpha) * inv;             // a2
    }

    /**
     * 默认摆放：hub 在场景中心；其它节点在 XZ 圆环 R=1 上，角度由节点 id 哈希得到
     * （稳定：节点进出不改变其他节点的默认位置，也不依赖图谱顺序）。
     * C++ 对 std::string 的每个字节做 FNV-1a；Java 侧先按 UTF-8 取字节，保证同一 id 同结果。
     */
    public static Vec3 defaultScenePlacement(String nodeId, boolean isHub) {
        if (isHub) return new Vec3(0.0f, 0.0f, 0.0f);
        int h = (int) 2166136261L;   // FNV-1a offset basis（uint32）
        if (nodeId != null && !nodeId.isEmpty()) {
            final byte[] bytes = nodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte b : bytes) {
                h = (h ^ (b & 0xFF)) * 16777619;   // Java int 溢出回绕 == C++ uint32 乘法
            }
        }
        final long u = h & 0xFFFFFFFFL;            // C++ 的 h % 10000u 是无符号取模
        final float frac = (float) (u % 10000L) / 10000.0f;
        final float angle = -kHalfPi + 2.0f * kPi * frac;   // 从 -Z（正前）起绕一圈
        return new Vec3(kSceneRadius * (float) Math.cos(angle), 0.0f,
                kSceneRadius * (float) Math.sin(angle));
    }

    /** 默认听者：固定在场景原点、面朝 -Z（屏幕上方 = 远处 = 听者正前方）。 */
    public static ListenerPose defaultListener() {
        return new ListenerPose(Vec3.ZERO, 0.0f, 0.0f);
    }

    // ------------------------------------------------ 小工具

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampDeg(float deg) {
        return clamp(deg, -180.0f, 180.0f);
    }

    /** 一阶平滑系数：tau<=0 视为"立即到位"（与 C++ SmoothCoef 一致）。 */
    private static float smoothCoef(float dtSec, float tauSec) {
        if (tauSec <= 0.0f) return 1.0f;
        return 1.0f - (float) Math.exp(-dtSec / tauSec);
    }

    // ------------------------------------------------ 每源 DSP 状态机

    /**
     * 每源 DSP 状态（C++ SpatialState 的镜像）。挂在 ReceiverService 的每一路流上：
     *   - {@link #process} 只由播放线程调用；
     *   - {@link #setTargetPosition} 由播放线程每块设置（位置表是并发 Map，读到的是一份
     *     不可变的 float[3] 快照），因此这里不需要额外加锁。
     *
     * 处理顺序（与 C++ 严格一致，顺序本身是行为的一部分）：
     *   目标参数(标量) -> ITD 每块最多变 2 帧 -> 距离/后向 50 ms 平滑 ->
     *   逐样本写 64 帧历史 -> 双耳分数延时线性插值读历史 -> 单声道化 0.5*(L+R) ->
     *   后向一阶低通 lp += alpha*(mid-lp)，dull = sm_rear*(lp-mid) 干湿相加 ->
     *   块内线性增益斜坡 -> 写回 interleaved。
     */
    public static final class SpatialState {

        private float sampleRate = (float) kSampleRate;
        private Vec3 target = Vec3.ZERO;
        private boolean hasTarget = false;
        private boolean enabled = true;

        // 平滑后的中间量（只由播放线程读写）
        private float smDist = 1.0f;    // 距离增益
        private float smRear = 0.0f;    // 0..1
        private float smDelay = 0.0f;   // 带符号 ITD（帧），+ = 右耳领先
        private float lp = 0.0f;        // 后向一阶低通状态

        // ---- v0.46 ③ 耳廓陷波：并联带通 y = x + beta*bp(x)。
        //   bp 的极点只由 notchA1/notchA2 决定，beta 怎么扫都不破坏稳定；
        //   beta=0 时 y 逐位等于 x（rear=0 完全透明）。串联 peaking 的 b0 会带 1 ulp 误差，
        //   破坏"正前方逐位不变"的跨端断言，所以选并联形式。
        private float notchB0 = 0.0f;   // bp 的 b0（b1 恒 0，b2 = -b0）
        private float notchA1 = 0.0f;
        private float notchA2 = 0.0f;
        private float notchBeta = 0.0f; // -(1 - 10^(-depth*rear/20))，rear=0 时为 0
        private float notchZ1 = 0.0f;   // TDF-II 状态
        private float notchZ2 = 0.0f;
        // ---- v0.46 ④ 早期反射：两路一阶低通状态
        private final float[] erLp = new float[2];
        /** bandPassCoeffs 的暂存（构造时分配；process 里不 new）。 */
        private final float[] bpCoeffs = new float[5];
        /** 两路反射的延迟/强度暂存（同上，避免每块分配）。 */
        private final float[] erDelay = new float[2];
        private final float[] erGain = new float[2];

        // ITD 历史：单声道化后的信号。hist[0] 是 P0 用的；hist[1] 留给 P1 的 side 通路。
        private final float[][] hist = new float[kSpatialHistoryChannels][kSpatialHistoryFrames];
        private int histPos = 0;

        // 上一块结束时的输出参数，用来做块内线性过渡（避免 200 Hz 台阶感/爆音）
        private float gainL = kMonoMakeup * kSqrtHalf;
        private float gainR = kMonoMakeup * kSqrtHalf;
        private float delayL = 0.0f;
        private float delayR = 0.0f;

        /** 复位到"刚创建"的状态（采样率 48k）。注意：不清 enabled（与 C++ 一致）。 */
        public void reset() {
            reset((float) kSampleRate);
        }

        /** 复位（采样率单位 Hz；<=0 回落到 48k）。 */
        public void reset(float sampleRateHz) {
            sampleRate = sampleRateHz > 0.0f ? sampleRateHz : (float) kSampleRate;
            target = Vec3.ZERO;
            hasTarget = false;
            smDist = 1.0f;
            smRear = 0.0f;
            smDelay = 0.0f;
            lp = 0.0f;
            // v0.46：陷波与反射状态一并清零（与 C++ Reset 逐行对应）
            notchB0 = 0.0f;
            notchA1 = 0.0f;
            notchA2 = 0.0f;
            notchBeta = 0.0f;
            notchZ1 = 0.0f;
            notchZ2 = 0.0f;
            erLp[0] = 0.0f;
            erLp[1] = 0.0f;
            for (int ch = 0; ch < kSpatialHistoryChannels; ++ch) {
                for (int i = 0; i < kSpatialHistoryFrames; ++i) hist[ch][i] = 0.0f;
            }
            histPos = 0;
            gainL = kMonoMakeup * kSqrtHalf;
            gainR = kMonoMakeup * kSqrtHalf;
            delayL = 0.0f;
            delayR = 0.0f;
        }

        /** 目标世界坐标（播放线程每块设置）。 */
        public void setTargetPosition(Vec3 world) {
            target = world != null ? world : Vec3.ZERO;
            hasTarget = true;
        }

        /** 目标世界坐标（米），便捷重载。 */
        public void setTargetPosition(float x, float y, float z) {
            setTargetPosition(new Vec3(x, y, z));
        }

        /**
         * 每源旁路：false 时 process 原样返回，立体声不被单声道化，调用方按旧路径乘
         * user_gain/32768。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean hasTarget() {
            return hasTarget;
        }

        public Vec3 lastTarget() {
            return target;
        }

        // 单测/调试用的观测口（C++ 同名 getter 的镜像）
        public float gainL() {
            return gainL;
        }

        public float gainR() {
            return gainR;
        }

        public float delayLFrames() {
            return delayL;
        }

        public float delayRFrames() {
            return delayR;
        }

        /** 分数延时读取：hist 是环形缓冲，histPos 指向"下一次写入位置"，所以最新样本在 -1。 */
        private float readHistory(float delayFrames) {
            final int n = kSpatialHistoryFrames;
            final float limit = (float) (n - 3);
            final float d = clamp(delayFrames, 0.0f, limit);
            final int i = (int) d;                       // C++ static_cast<int> 截断
            final float frac = d - (float) i;
            final int pos0 = (histPos - 1 + n) % n;
            final int i0 = (pos0 - i + n * 4) % n;
            final int i1 = (i0 - 1 + n) % n;
            final float s0 = hist[0][i0];
            final float s1 = hist[0][i1];
            return s0 + frac * (s1 - s0);
        }

        /**
         * 原地把交错立体声（frames*2）改写成空间化结果。
         *
         * userGain = 界面上的每源音量（0..4），**已经折进每耳增益**：调用方随后必须把该路
         * 的混音增益置 1（直接相加），否则音量会乘两次（与 C++ MixInput.gain=1 同理）。
         *
         * @param interleaved 长度 &gt;= frames*2 的 float 缓冲，采样值域 [-1,1]（调用方负责
         *                    把 int16 /32768 归一化后再进来）
         * @param frames      本块帧数（Android 固定 240 = 5 ms）
         * @param listener    听者位姿（Android 固定 Spatial.defaultListener()）
         * @param params      平滑/ITD 参数，null = 默认参数
         * @param userGain    每源用户音量（0..4，1 = 原样）
         */
        public void process(float[] interleaved, int frames, ListenerPose listener,
                SpatialParams params, float userGain) {
            if (interleaved == null || frames <= 0) return;
            if (!enabled) return;     // 每源旁路：保持原立体声直通，调用方按旧路径乘 userGain
            if (!hasTarget) return;   // 未设置位置：按原样直通，由调用方决定是否给默认位置

            final SpatialParams p = params != null ? params : DEFAULT_PARAMS;
            final ListenerPose lis = listener != null ? listener : defaultListener();
            final Geo geo = analyzePosition(target, lis);
            final float dt = (float) frames / sampleRate;
            final float k = smoothCoef(dt, p.smoothTauSec);

            // ---- 目标参数（只算标量，不进逐样本循环）
            final float pan = panValue(geo.azimuthDeg, p.azimuthBoost);
            final float panL = (float) Math.sqrt(0.5f * (1.0f - pan));
            final float panR = (float) Math.sqrt(0.5f * (1.0f + pan));
            final float distGain = distanceGain(geo.distance);
            final float rear = rearFactor(geo.azimuthDeg);
            final float cutoffHz = rearCutoffHz(rear);

            final float itdFrames = itdSeconds(geo.azimuthDeg) * sampleRate;
            final float maxSlew = p.maxDelaySlewFrames;
            smDelay += clamp(itdFrames - smDelay, -maxSlew, maxSlew);

            smDist += (distGain - smDist) * k;
            smRear += (rear - smRear) * k;

            final float rearGain = 1.0f - (1.0f - kRearGain) * smRear;   // 正后 -> 0.7
            final float rawL = panL * kMonoMakeup * smDist * userGain * rearGain;
            final float rawR = panR * kMonoMakeup * smDist * userGain * rearGain;
            // 块间平滑（50 ms）：200 Hz 状态快照 + 拖动节点时避免增益台阶。
            final float targetL = gainL + (rawL - gainL) * k;
            final float targetR = gainR + (rawR - gainR) * k;
            final float dl = smDelay > 0.0f ? smDelay : 0.0f;
            final float dr = smDelay < 0.0f ? -smDelay : 0.0f;

            // ---- v0.46 ③ 耳廓陷波：并联带通，参数按本块的 smRear 重算。
            //   beta = -(1 - 10^(-depth*rear/20))：rear=0 -> 0（y 与 x 逐位相同，纯旁路），
            //   rear=1 -> -(1 - 10^(-15/20)) = -0.822，即中心频率处压到 17.8%（-15 dB）。
            bandPassCoeffs(sampleRate, rearNotchHz(smRear), kRearNotchQ, bpCoeffs);
            final float notchBetaTarget = -(1.0f - (float) Math.pow(10.0f,
                    -kRearNotchDepthDb * smRear * 0.05f));
            // 平滑到新系数/增益：beta 与极点都朝目标走，块间不会出现参数跳变。
            notchB0 += (bpCoeffs[0] - notchB0) * k;
            notchA1 += (bpCoeffs[3] - notchA1) * k;
            notchA2 += (bpCoeffs[4] - notchA2) * k;
            notchBeta += (notchBetaTarget - notchBeta) * k;

            // ---- v0.46 ④ 两路早期反射：延迟与强度都随 smRear 走。
            //   rear=0 时强度为 0，读历史的结果乘 0 —— 但**不要**跳过循环里的读取，
            //   否则两条路径的 histPos 推进节奏会不同（这里保持结构一致，代价可忽略）。
            final float[] erDelay = this.erDelay;
            final float[] erGain = this.erGain;
            for (int s = 0; s < 2; ++s) {
                erDelay[s] = rearReflectionDelaySeconds(smRear, s) * sampleRate;
                erGain[s] = (s == 0 ? kRearEr1Gain : kRearEr2Gain) * smRear;
            }
            final float erAlpha = 1.0f
                    - (float) Math.exp(-2.0f * kPi * kRearErLpHz / sampleRate);

            // ---- 逐样本：写历史 -> 双耳分数延时读取 -> 后向闷化(干湿混合)
            //      -> 陷波(并联) + 早期反射 -> 等功率/距离增益
            final float alpha = 1.0f
                    - (float) Math.exp(-2.0f * kPi * cutoffHz / sampleRate);
            final boolean ramp = frames > 1;
            // 延时也要逐样本铺开：只在块边界改延时，等于每 5 ms 给读指针一个台阶，
            // 宽带/高频信号上听起来就是"嘶啦"的电流声（拖动节点时 ITD 每块都在变，尤其明显）。
            // 把上一块结束时的延时到本块目标延时在块内做线性插值，读指针才是连续的。
            final float dlStart = delayL;
            final float drStart = delayR;
            final float dlSpan = dl - dlStart;
            final float drSpan = dr - drStart;
            for (int i = 0; i < frames; ++i) {
                hist[0][histPos] = 0.5f * (interleaved[2 * i] + interleaved[2 * i + 1]);
                histPos = (histPos + 1) % kSpatialHistoryFrames;

                final float t = ramp ? (float) i / (float) (frames - 1) : 1.0f;
                final float l = readHistory(dlStart + dlSpan * t);
                final float r = readHistory(drStart + drSpan * t);
                // 后向链是"单声道染色"：以 mid 为输入，只产出**相对 mid 的增量** rearExtra，
                // 再原样加到两耳上。这样 ITD（l 与 r 各自的延时差）完全不被破坏 ——
                // 如果直接把染色后的单声道信号写给两耳，ITD 就被抹掉了。
                final float mid = 0.5f * (l + r);
                lp += alpha * (mid - lp);
                // 干湿混合：前方全频；后向按 smRear 混入一阶低通版本（v0.46 截止放宽到 6 kHz）。
                final float dull = smRear * (lp - mid);
                float colored = mid + dull;

                // ③ 并联陷波：bp 是二阶带通（b1=0、b2=-b0），colored + beta*bp 在 bp 中心处压 beta。
                //   TDF-II（转置直接II型）：y = b0*x + z1；z1 = -a1*y + z2；z2 = -b0*x - a2*y。
                final float bp = notchB0 * colored + notchZ1;
                notchZ1 = -notchA1 * bp + notchZ2;
                notchZ2 = -notchB0 * colored - notchA2 * bp;
                colored += notchBeta * bp;

                // ④ 两路早期反射（带一阶低通，模拟墙面/肩部吸高频），与干声并联相加。
                float wet = 0.0f;
                for (int s = 0; s < 2; ++s) {
                    final float ref = readHistory(erDelay[s]);
                    erLp[s] += erAlpha * (ref - erLp[s]);
                    wet += erGain[s] * erLp[s];
                }
                // 相对 mid 的增量：rear=0 时 dull=0、beta=0、wet=0 -> rearExtra 恒等于 0，
                // 输出 (l + 0) 与旧实现逐位相同（见 SpatialCheck 的逐位断言）。
                final float rearExtra = (colored + wet) - mid;

                final float gl = gainL + (targetL - gainL) * t;
                final float gr = gainR + (targetR - gainR) * t;
                interleaved[2 * i] = (l + rearExtra) * gl;
                interleaved[2 * i + 1] = (r + rearExtra) * gr;
            }

            gainL = targetL;
            gainR = targetR;
            delayL = dl;
            delayR = dr;
        }
    }
}