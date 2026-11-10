#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按文本找控件并点击（uiautomator dump 后算 bounds 中心）。

为什么需要它：这个项目的弹窗按钮位置会随软键盘出现/消失上下浮动
（「连接」按钮实测在 [810,729][978,855] 与 [810,1117][978,1243] 之间跳），
硬编码坐标点不中是很常见的假故障。

用法：
  python tools/tapui.py tap 连接
  python tools/tapui.py dump            # 列出所有 text 与其 bounds
"""
import re
import subprocess
import sys
import time

ADB = r"D:\tmp\ahemu\sdk\platform-tools\adb.exe"


def sh(args):
    p = subprocess.run([ADB, "shell"] + args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.stdout


UI_XML = "/sdcard/ah_tapui.xml"


def dump():
    # 星空背景是持续动画：uiautomator 常报 "ERROR: could not get idle state"，
    # 此时它根本不写文件。旧实现固定读 /sdcard/ui.xml，dump 失败时会 cat 到上一次
    # 留下的**过期**文件 —— 于是在真机上早就关掉的授权弹窗还一直"显示"在结果里，
    # tap/has 再按旧 bounds 去点，全成了幽灵操作（v0.23 验证时踩过）。
    # 修法：① 每次先删目标文件，保证 cat 到的只可能是本次 dump 写的；
    #       ② 校验内容确实是 hierarchy XML 且 dump 未报错；③ 失败重试几次，
    #          仍失败返回空串，绝不用过期数据（tap 会报"找不到"、has 报"no"，
    #          比误点安全）。看真实界面请用 screencap / dumpsys window。
    for attempt in range(4):
        sh(["rm", "-f", UI_XML])
        out = sh(["uiautomator", "dump", UI_XML])
        xml = sh(["cat", UI_XML])
        if "<hierarchy" in xml and "ERROR" not in out:
            return xml
        if attempt < 3:
            time.sleep(0.6)
    sys.stderr.write("警告：uiautomator dump 连续失败（UI 持续动画？），本次返回空结果，"
                     "不会使用过期的 ui.xml。\n")
    return ""


def nodes(xml):
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        text = m.group(1)
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
        if text:
            yield text, x1, y1, x2, y2


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cmd = sys.argv[1]
    xml = dump()
    if cmd == "dump":
        for text, x1, y1, x2, y2 in nodes(xml):
            print("%-40s | [%d,%d][%d,%d] center=(%d,%d)"
                  % (text[:40], x1, y1, x2, y2, (x1 + x2) // 2, (y1 + y2) // 2))
        return 0
    if cmd == "tap":
        want = sys.argv[2]
        hits = [(t, x1, y1, x2, y2) for t, x1, y1, x2, y2 in nodes(xml) if t == want]
        if not hits:
            print("找不到 text=%r" % want)
            return 2
        _, x1, y1, x2, y2 = hits[0]
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        sh(["input", "tap", str(cx), str(cy)])
        print("已点击 %r @ (%d,%d)" % (want, cx, cy))
        return 0
    if cmd == "has":
        want = sys.argv[2]
        print("yes" if any(t == want for t, *_ in nodes(xml)) else "no")
        return 0
    print("未知命令 %r" % cmd)
    return 1


if __name__ == "__main__":
    sys.exit(main())
