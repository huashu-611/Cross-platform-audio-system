#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成双端统一的应用图标（星空主题）。

母题：深空圆角方 → 中心暖金恒星（日冕/星芒/白热核心）+ 两颗行星 + 稀疏星点。
PC 与 Android 共用同一套几何，只按平台派生不同尺寸/形状/安全区：

  ui/icon.png                     512×512 母版（窗口图标 / 文档用）
  ui/icon.ico                     16,20,24,32,40,48,64,128,256（每档独立渲染，BMP 帧）
  android/.../mipmap-*/ic_launcher.png           传统方形（48/72/96/144/192）
  android/.../mipmap-*/ic_launcher_round.png     传统圆形（同上）
  android/.../mipmap-anydpi-v26/ic_launcher_foreground.png  自适应前景 432×432（透明，安全区 66%）

用法：python tools/gen-icon.py
依赖：Pillow（本机 12.x）。
改设计只需要改本文件的 STAR/PLANET 常量，然后重跑；两端图标会一起更新，避免"手机和桌面不像一套"。
"""

import io
import math
import os
import random
import struct

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')

SS = 4                      # 超采样倍数：先在 4 倍画布上画，再 LANCZOS 缩到目标尺寸
RADIUS_RATIO = 0.225        # 圆角方半径（Win10 风格 squircle 近似）
BG_CENTER = (13, 21, 50)    # 深空底色（中心）
BG_EDGE = (4, 6, 16)        # 深空底色（边缘）
GOLD = (255, 196, 104)
WHITE_HOT = (255, 255, 255)
PLANET_A = ((32, 96, 152), (150, 226, 255))     # 青蓝色行星：基色 / 受光色
PLANET_B = ((86, 62, 148), (206, 180, 255))     # 紫色行星


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def rounded_mask(size, radius):
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def circle_mask(size):
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def radial(size, c0, c1, cx, cy, r):
    """低成本径向渐变：96px 小图逐像素算完再放大。"""
    n = 96
    img = Image.new('RGB', (n, n))
    px = img.load()
    for y in range(n):
        for x in range(n):
            t = min(1.0, math.hypot((x + .5) / n - cx, (y + .5) / n - cy) / r)
            px[x, y] = lerp(c0, c1, t ** 0.85)
    return img.resize((size, size), Image.BICUBIC)


def soft_glow(size, cx, cy, r, color, alpha):
    layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    steps = 26
    for i in range(steps, 0, -1):
        t = i / steps
        a = int(alpha * (1 - t) ** 1.6)
        if a <= 0:
            continue
        rr = r * t
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=color + (a,))
    return layer


def draw_design(img, size, safe=1.0):
    """在透明 RGBA 画布上画星图母题；safe<1 时整体缩进中心（自适应图标安全区）。"""
    d = ImageDraw.Draw(img, 'RGBA')
    k = size * safe
    cx, cy = size * 0.5, size * 0.47
    small = size <= 32 * SS * 0.75

    # 稀疏星点：只在够大的画布上画，避免 16px 变雪花
    if size >= 32 * SS:
        rnd = random.Random(7)
        for _ in range(max(7, int(size * 0.055))):
            x, y = rnd.uniform(0.05, 0.95) * size, rnd.uniform(0.05, 0.95) * size
            if math.hypot(x - cx, y - cy) < k * 0.24:
                continue        # 不抢主体
            rr = rnd.uniform(0.0032, 0.0065) * size
            d.ellipse([x - rr, y - rr, x + rr, y + rr],
                      fill=(206, 226, 255, rnd.randint(35, 100)))

    # ---- 恒星：日冕 → 星芒 → 日面 → 白热核心
    img.alpha_composite(soft_glow(size, cx, cy, k * 0.40, (255, 196, 92), 150))
    img.alpha_composite(soft_glow(size, cx, cy, k * 0.155, (255, 236, 190), 170))

    def spike(angle, length, half_w, color, alpha):
        a = math.radians(angle)
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        d.polygon([
            (cx + px * half_w, cy + py * half_w),
            (cx + ux * length, cy + uy * length),
            (cx - px * half_w, cy - py * half_w)
        ], fill=color + (alpha,))

    long_len = k * (0.255 if not small else 0.30)
    for ang in (0, 90, 180, 270):
        spike(ang, long_len, k * 0.026, (255, 214, 140), 215)
    for ang in (45, 135, 225, 315):
        spike(ang, k * 0.135, k * 0.020, (255, 224, 170), 150)

    # 日面：径向渐变（白热 → 暖金 → 橙边），边缘羽化，避免硬边"甜甜圈"
    core = k * 0.135
    n = 220
    disc = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    dp = disc.load()
    stops = [(0.00, WHITE_HOT), (0.30, (255, 249, 228)), (0.55, (255, 226, 158)),
             (0.78, (255, 191, 108)), (0.93, (255, 158, 74)), (1.00, (255, 138, 58))]
    for yy in range(n):
        for xx in range(n):
            rr = math.hypot((xx + .5) / n - .5, (yy + .5) / n - .5) * 2.0
            if rr > 1.0:
                continue
            col = stops[-1][1]
            for i in range(1, len(stops)):
                if rr <= stops[i][0]:
                    t = (rr - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0] or 1)
                    col = lerp(stops[i - 1][1], stops[i][1], t)
                    break
            a = 255 if rr <= 0.93 else int(255 * (1 - (rr - 0.93) / 0.07) ** 1.2)
            dp[xx, yy] = col + (a,)
    disc = disc.resize((max(2, int(core * 2)), max(2, int(core * 2))), Image.LANCZOS)
    img.alpha_composite(disc, (int(cx - disc.width / 2), int(cy - disc.height / 2)))
    img.alpha_composite(soft_glow(size, cx, cy, core * 0.72, (255, 255, 250), 210))

    # ---- 行星：朝恒星受光（径向渐变里把亮面朝恒星方向偏移）
    def planet(px, py, r, base, hi):
        img.alpha_composite(soft_glow(size, px, py, r * 2.3, base, 110))
        ang = math.atan2(cy - py, cx - px)
        side = 80
        tex = Image.new('RGBA', (side, side), (0, 0, 0, 0))
        tp = tex.load()
        lx, ly = 0.5 + math.cos(ang) * 0.28, 0.5 + math.sin(ang) * 0.28
        for yy in range(side):
            for xx in range(side):
                dx, dy = (xx + .5) / side, (yy + .5) / side
                rr = math.hypot(dx - .5, dy - .5)
                if rr > .5:
                    continue
                lit = max(0.0, 1.0 - math.hypot(dx - lx, dy - ly) / 0.82)
                c = lerp(base, hi, lit ** 1.5)
                edge = max(0.0, 1 - (0.5 - rr) / 0.10)
                tp[xx, yy] = lerp(c, (4, 6, 14), edge * 0.85) + (255,)
        tex = tex.resize((max(2, int(r * 2)), max(2, int(r * 2))), Image.LANCZOS)
        img.alpha_composite(tex, (int(px - tex.width / 2), int(py - tex.height / 2)))

    planet(cx - k * 0.315, cy - k * 0.185, k * (0.085 if small else 0.072), *PLANET_A)
    planet(cx + k * 0.325, cy + k * 0.225, k * (0.095 if small else 0.084), *PLANET_B)


def render(size, bg=True, safe=1.0, circle=False):
    """渲染一张图标；bg=True 时带深空底+圆角方/圆遮罩，False 时透明（自适应前景用）。"""
    canvas = size * SS
    img = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
    if bg:
        img.paste(radial(canvas, BG_CENTER, BG_EDGE, 0.5, 0.42, 0.95), (0, 0))
        # 左上冷光，让底色不是死板的纯渐变
        halo = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
        ImageDraw.Draw(halo).ellipse(
            [-canvas * .35, -canvas * .55, canvas * .75, canvas * .45], fill=(60, 88, 190, 46))
        img.alpha_composite(halo.filter(ImageFilter.GaussianBlur(canvas * 0.02)))
        # 暗角
        vig = Image.new('L', (canvas, canvas), 0)
        ImageDraw.Draw(vig).ellipse(
            [-canvas * .18, -canvas * .18, canvas * 1.18, canvas * 1.18], fill=255)
        vig = vig.filter(ImageFilter.GaussianBlur(canvas * 0.06))
        dark = Image.new('RGBA', (canvas, canvas), (2, 4, 12, 90))
        img = Image.composite(img, Image.alpha_composite(img, dark), vig)

    draw_design(img, canvas, safe=safe)

    if bg:
        img.putalpha(circle_mask(canvas) if circle else rounded_mask(canvas, int(canvas * RADIUS_RATIO)))
    return img.resize((size, size), Image.LANCZOS)


# ------------------------------------------------------------------ ICO 写出
# 不用 Pillow 的 ICO 存盘（它只会把 256 母版缩一遍，小尺寸会糊）：
# 每档尺寸独立渲染，再自己拼 ICO（BMP 帧，Windows 全版本兼容）。

def _bmp_frame(im):
    w, h = im.size
    px = im.convert('RGBA').load()
    rows = []
    for y in range(h - 1, -1, -1):          # DIB 自下而上
        row = bytearray()
        for x in range(w):
            r, g, b, a = px[x, y]
            row += bytes((b, g, r, a))      # BGRA
        rows.append(bytes(row))
    xor = b''.join(rows)
    mask_row = ((w + 31) // 32) * 4
    and_mask = bytes(mask_row * h)          # 32bpp 用 alpha，AND 掩码全 0
    header = struct.pack('<IiiHHIIiiII', 40, w, h * 2, 1, 32, 0, len(xor) + len(and_mask), 0, 0, 0, 0)
    return header + xor + and_mask


def write_ico(path, sizes):
    frames = [(s, render(s)) for s in sizes]
    n = len(frames)
    offset = 6 + 16 * n
    entries, blobs = b'', []
    for size, im in frames:
        data = _bmp_frame(im)
        blobs.append(data)
        entries += struct.pack('<BBBBHHII', 0 if size >= 256 else size,
                               0 if size >= 256 else size, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    with open(path, 'wb') as fh:
        fh.write(struct.pack('<HHH', 0, 1, n) + entries + b''.join(blobs))


# ------------------------------------------------------------------ 输出

def main():
    # PC：512 母版 + 多尺寸 ICO
    render(512).save(os.path.join(ROOT, 'ui', 'icon.png'))
    write_ico(os.path.join(ROOT, 'ui', 'icon.ico'),
              [16, 20, 24, 32, 40, 48, 64, 128, 256])
    print('ui/icon.png, ui/icon.ico')

    # Android：传统方形/圆形（按密度）
    legacy = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for bucket, size in legacy.items():
        folder = os.path.join(ANDROID_RES, 'mipmap-' + bucket)
        render(size).save(os.path.join(folder, 'ic_launcher.png'))
        render(size, circle=True).save(os.path.join(folder, 'ic_launcher_round.png'))
        print('mipmap-' + bucket + '/ic_launcher(_round).png ' + str(size))

    # Android：自适应图标前景（108dp 画布 ×4 = 432；内容缩进到中心 66% 安全区）
    fg = render(432, bg=False, safe=0.72)
    fg.save(os.path.join(ANDROID_RES, 'mipmap-anydpi-v26', 'ic_launcher_foreground.png'))

    # Android：自适应图标背景色（与 StarSkyView 顶端同色）
    bg_xml = os.path.join(ANDROID_RES, 'values', 'ic_launcher_background.xml')
    with open(bg_xml, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<resources>\n'
                 '    <color name="ic_launcher_background">#070B1B</color>\n'
                 '</resources>\n')
    print('mipmap-anydpi-v26/ic_launcher_foreground.png, values/ic_launcher_background.xml')

    # 预览对照图（白底，16~256）
    sheet = Image.new('RGBA', (16 + 24 + 32 + 48 + 64 + 128 + 256 + 9 * 8, 280), (245, 246, 250, 255))
    x = 8
    for size in (16, 24, 32, 48, 64, 128):
        sheet.alpha_composite(render(size), (x, 8))
        x += size + 8
    sheet.alpha_composite(render(256), (x, 8))
    sheet.save(os.path.join(ROOT, 'docs', 'starfield-ui-preview', 'app-icon.png'))
    print('docs/starfield-ui-preview/app-icon.png')


if __name__ == '__main__':
    main()
