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

APP_NAME = "Zeekr Shortcut (Car Version)"
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
    out.append("Download the `.apk` below and sideload it through App Lab.")
    out.append("")

    section = changelog_section(version)
    if section:
        out.append("## What changed")
        out.append("")
        out.append(section)
        out.append("")

    out.append("## Verified on the vehicle")
    out.append("")
    out.append("Confirmed on a ZEEKR 7X: **the composite stream split into four views**, "
               "**recording**, and **saving to a USB drive**.")
    out.append("")
    out.append("> [!WARNING]")
    out.append("> Everything else - new features in particular - is **unverified on a vehicle**,")
    out.append("> and the automated tests cover the pure logic only.")
    out.append("> **Try anything new in a stationary vehicle first.**")
    out.append("> If recording misbehaves, set the recording layout back to \"Raw strip\".")
    out.append("")

    out.append("## Getting started")
    out.append("")
    out.append("Open **Settings -> Recording -> Stream configuration**, pick "
               "*ZEEKR 7X (surround-view composite)*, and restart the app.")
    out.append("")
    out.append("Something wrong? Export **Menu -> Diagnostics** and attach the report to an issue.")
    out.append("")

    out.append("## Safety")
    out.append("")
    out.append("Experimental, unofficial software. Not affiliated with, approved by, or endorsed "
               "by ZEEKR, and not certified for any vehicle safety function.")
    out.append("")
    out.append("- It does not replace the factory dash cam, reversing camera, blind-spot monitor, "
               "or any other required safety equipment")
    out.append("- Do not operate it while driving, and do not judge distances or obstacles from "
               "its picture")
    out.append("- It competes with the head unit for resources and **may** affect factory "
               "features; stop using it and uninstall if anything behaves oddly")
    out.append("- Recordings can contain faces and plate numbers - follow your local law")
    out.append("")
    out.append("Full notice: [README](" + REPO + "#readme).")
    out.append("")

    out.append("## Credits")
    out.append("")
    out.append("Released under **GPL-3.0**, with "
               "[EVCam](https://github.com/suyunkai/EVCam) (GPL-3.0, by suyunkai) as its code base.")
    out.append("")
    out.append("The dimensions, layout and platform behaviour of the ZEEKR composite stream were "
               "first documented publicly by "
               "[openavm-recorder](https://github.com/Dantenothing/openavm-recorder) "
               "(by Dantenothing, all rights reserved). "
               "**No code from that project was copied**, nor its name or icon, and there is no "
               "affiliation. See [NOTICE.md](" + REPO + "/blob/main/NOTICE.md).")

    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
