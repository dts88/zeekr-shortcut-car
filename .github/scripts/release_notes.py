#!/usr/bin/env python3
"""
Build the GitHub Release body for a given version.

Takes the matching section out of CHANGELOG.md so each release shows its own
changes instead of the same boilerplate every time, then appends the standing
safety / attribution text.

Usage:  python3 .github/scripts/release_notes.py 0.3.0-alpha > RELEASE_NOTES.md
"""
import io
import os
import re
import sys

APP_NAME = "极氪即刻（车机版）"
REPO = "https://github.com/dts88/zeekr-shortcut-car"


def changelog_section(version):
    """Return the body of the '## [<version>]' section, or None."""
    path = "CHANGELOG.md"
    if not os.path.exists(path):
        return None
    text = io.open(path, encoding="utf-8").read()
    lines = text.split("\n")

    start = None
    for i, line in enumerate(lines):
        if line.startswith("## ") and ("[" + version + "]") in line:
            start = i + 1
            break
    if start is None:
        return None

    end = len(lines)
    for i in range(start, len(lines)):
        if lines[i].startswith("## "):
            end = i
            break

    body = "\n".join(lines[start:end]).strip()
    return body if body else None


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "unknown"

    out = []
    out.append("## " + APP_NAME + " " + version)
    out.append("")
    out.append("下载下面的 `.apk`，通过 App Lab 侧载安装。")
    out.append("")

    section = changelog_section(version)
    if section:
        out.append("## 本版本更新")
        out.append("")
        out.append(section)
        out.append("")

    out.append("## 验证状态")
    out.append("")
    out.append("已在极氪 7X 实车验证：**环视合成流拆分为四画面**、**录制**、**存储到 U 盘**。")
    out.append("")
    out.append("> [!WARNING]")
    out.append("> 其余功能（尤其是每个版本的新增项）**未经实车验证**，")
    out.append("> 自动化测试只覆盖纯逻辑部分。**首次使用新功能请在静止车辆上测试。**")
    out.append("> 若录制异常，可在设置里把「录制画面排列」切回「原始长条」。")
    out.append("")

    out.append("## 使用")
    out.append("")
    out.append("安装后进入 **菜单 → 软件设置 → 车型**，选择 **「极氪7X（环视合成流）」**，")
    out.append("重启应用即可看到四宫格环视画面。")
    out.append("")
    out.append("遇到问题请打开 **菜单 → 诊断信息**，导出报告后附在 issue 里。")
    out.append("")

    out.append("## 安全须知")
    out.append("")
    out.append("实验性非官方软件，与极氪（ZEEKR）无任何关联，未经其批准或认可，")
    out.append("也未经过任何车辆功能安全认证。")
    out.append("")
    out.append("- 不可替代原厂行车记录仪、倒车影像、盲区监测或任何法定安全设备")
    out.append("- 请勿在行驶中操作，请勿依据其画面判断车距或障碍物")
    out.append("- 与车机争夺资源**可能**影响原厂功能，出现异常请立即停用并卸载")
    out.append("- 录制内容可能包含人脸、车牌等个人信息，请遵守当地法律")
    out.append("")
    out.append("完整说明见 [README](" + REPO + "#readme)。")
    out.append("")

    out.append("## 来源与致谢")
    out.append("")
    out.append("本应用以 **GPL-3.0** 发布，代码基座为 "
               "[EVCam](https://github.com/suyunkai/EVCam)（GPL-3.0，作者 suyunkai）。")
    out.append("")
    out.append("极氪合成流的尺寸、排布与平台行为等事实，来自 "
               "[openavm-recorder](https://github.com/Dantenothing/openavm-recorder) "
               "公开记录的实测结论（作者 Dantenothing，保留所有权利）。")
    out.append("**本项目未复制其任何源代码**，也未使用其名称或图标，与其没有隶属关系。")
    out.append("详见 [NOTICE.md](" + REPO + "/blob/main/NOTICE.md)。")

    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
