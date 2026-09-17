#!/usr/bin/env python3
"""
Build the GitHub Release body for a given version.

Takes the matching section out of CHANGELOG.md so each release shows its own
changes instead of the same boilerplate every time, then appends the standing
safety / attribution text.

A stable version (no suffix) is introduced by its changelog section as a whole: that
section carries its own headings, written as ### in CHANGELOG.md and promoted to ## here.
Test builds (-alpha, -beta) list the section under "What changed" and keep the warning
that most of what is new has not been checked on a vehicle.

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


def promote_headings(section):
    """### in CHANGELOG.md (so the section parser keeps them) become ## in the release."""
    return "\n".join("## " + line[4:] if line.startswith("### ") else line
                     for line in section.split("\n"))


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "unknown"
    stable = "-" not in version

    out = []
    out.append("## " + APP_NAME + " " + version)
    out.append("")

    section = changelog_section(version)
    if stable:
        if section:
            out.append(promote_headings(section))
            out.append("")
    else:
        out.append("Download the `.apk` below and sideload it through App Lab.")
        out.append("")
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
        out.append("")

    out.append("## Getting started")
    out.append("")
    out.append("1. Plug in a USB drive.")
    out.append("2. Open **Settings \u2192 Recording \u2192 Stream profile**, pick "
               "*Zeekr 7X (surround composite)* or *Zeekr 7X (surround + front and rear cabin)*, "
               "and restart the app.")
    out.append("")
    out.append("Something wrong? Export a report from **Settings \u2192 System \u2192 Diagnostics** "
               "and attach it to an issue.")
    out.append("")

    out.append("## Safety")
    out.append("")
    out.append("Experimental, unofficial software. Not affiliated with, approved by, or endorsed "
               "by ZEEKR, and not certified for any vehicle safety function.")
    out.append("")
    out.append("- It does not replace the factory dash cam, reversing camera or blind-spot monitor.")
    out.append("- Do not operate it while driving, and do not judge distances or obstacles from "
               "its picture.")
    if not stable:
        out.append("- It competes with the head unit for resources and **may** affect factory "
                   "features; stop using it and uninstall if anything behaves oddly.")
    out.append("- Recordings on the USB drive are not encrypted and can contain faces and plate "
               "numbers. Follow your local law.")
    out.append("")

    out.append("## Credits")
    out.append("")
    out.append("Released under **GPL-3.0**, based on "
               "[EVCam](https://github.com/suyunkai/EVCam) by suyunkai.")
    out.append("")
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
