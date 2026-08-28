#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Java 源文件的结构性检查。

开发机上没有 JDK / Android SDK，编译只能靠 GitHub Actions。一个漏掉的花括号
要浪费一整轮 CI —— 这个脚本在推送前就能挡住那一类错误。

它不是编译器，只检查括号 / 引号 / 注释的配平，以及顶层结构是否合理。
能过这个脚本不代表能编译，但过不了就一定编译不了。

用法：
    python tools/check_java_syntax.py [路径...]
默认检查 app/src 下所有 .java。
"""
from __future__ import print_function

import io
import os
import sys

PAIRS = {')': '(', ']': '[', '}': '{'}
OPENERS = set('([{')


def check(path):
    """返回该文件的问题列表。"""
    with io.open(path, encoding='utf-8') as handle:
        src = handle.read()

    problems = []
    stack = []          # (字符, 行号)
    line = 1
    i = 0
    n = len(src)

    while i < n:
        ch = src[i]

        if ch == '\n':
            line += 1
            i += 1
            continue

        # 行注释
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue

        # 块注释
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            if j < 0:
                problems.append('第 %d 行：块注释没有闭合' % line)
                break
            line += src.count('\n', i, j)
            i = j + 2
            continue

        # 字符串 / 字符字面量
        if ch in '"\'':
            quote = ch
            j = i + 1
            closed = False
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '\n':
                    break       # 字面量不能跨行
                if src[j] == quote:
                    closed = True
                    break
                j += 1
            if not closed:
                problems.append('第 %d 行：%s 引号没有闭合' % (line, quote))
                i += 1
                continue
            i = j + 1
            continue

        if ch in OPENERS:
            stack.append((ch, line))
        elif ch in PAIRS:
            if not stack:
                problems.append('第 %d 行：多出一个 %s' % (line, ch))
            elif stack[-1][0] != PAIRS[ch]:
                opener, opener_line = stack[-1]
                problems.append('第 %d 行：%s 与第 %d 行的 %s 不匹配'
                                % (line, ch, opener_line, opener))
                stack.pop()
            else:
                stack.pop()
        i += 1

    for opener, opener_line in stack:
        problems.append('第 %d 行的 %s 没有闭合' % (opener_line, opener))

    return problems


def collect(paths):
    files = []
    for path in paths:
        if os.path.isfile(path):
            files.append(path)
            continue
        for root, _dirs, names in os.walk(path):
            for name in names:
                if name.endswith('.java'):
                    files.append(os.path.join(root, name))
    return sorted(files)


def main(argv):
    targets = argv[1:] or ['app/src']
    files = collect(targets)
    if not files:
        print('没有找到 .java 文件')
        return 1

    failed = 0
    for path in files:
        problems = check(path)
        if problems:
            failed += 1
            print('FAIL %s' % path)
            for problem in problems:
                print('       %s' % problem)

    print('检查 %d 个文件，%d 个有问题' % (len(files), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
