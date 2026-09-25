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
import re
import sys

PAIRS = {')': '(', ']': '[', '}': '{'}

# 资源引用。子包里用 R.string.xxx 必须自己 import com.kooo.evcam.R ——
# 括号配对、语法也挑不出毛病，只有编译器会说 "package R does not exist"，
# 而这台机器上没有 JDK，于是这种错只能等 CI 红了才发现。
R_REFERENCE = re.compile(
    r'(?<![\w.])R\.(string|color|dimen|style|drawable|layout|id|array|xml|raw|plurals)\.')
R_IMPORT = 'import com.kooo.evcam.R;'
PACKAGE = re.compile(r'package\s+([\w.]+);')
STRING_LITERAL = re.compile(r'"(?:[^"\\\n]|\\.)*"')

# 带限定的 this（{@code Outer.this}）前面不能再挂东西：{@code v.Outer.this} 不是
# 任何合法写法。批量把 getContext() 换成 Outer.this 时很容易漏掉「前面还有个变量」
# 的那一处，而括号是配对的、语法也挑不出毛病，只有编译器会说 "package v does not exist"。
BAD_QUALIFIED_THIS = re.compile(r'\b[A-Za-z_]\w*\.[A-Z]\w*\.this\b')


def check_qualified_this(src):
    """{@code x.Outer.this} 这种残骸，返回问题列表。"""
    problems = []
    for number, raw_line in enumerate(src.split('\n'), start=1):
        line = raw_line.strip()
        if line.startswith('//') or line.startswith('*'):
            continue
        hit = BAD_QUALIFIED_THIS.search(STRING_LITERAL.sub('""', raw_line))
        if hit:
            problems.append('第 %d 行：%s 前面不该有东西（Outer.this 不能再加前缀）'
                            % (number, hit.group(0)))
    return problems


def check_override_target(src):
    """@Override 后面跟的必须是方法签名。

    1.31.0 往 @Override 和 onStartCommand 中间插了一个字段，注解落到了字段上 ——
    括号配对、语法都挑不出毛病，只有编译器会说 "annotation type not applicable"。
    """
    problems = []
    lines = src.split('\n')
    for number, raw_line in enumerate(lines, start=1):
        if raw_line.strip() != '@Override':
            continue
        in_javadoc = False
        for follow in lines[number:]:
            line = follow.strip()
            if in_javadoc:
                if '*/' in line:
                    in_javadoc = False
                continue
            if line.startswith('/*'):
                in_javadoc = '*/' not in line
                continue
            if not line or line.startswith('//') or line.startswith('@'):
                continue
            if '(' not in line:
                problems.append('第 %d 行：@Override 后面不是方法签名（"%s"）' % (number, line[:50]))
            break
    return problems


def check_r_import(path, src):
    """子包里用了 R 却没 import 的，返回一条问题；同包（com.kooo.evcam）不需要。"""
    if 'src/test' in path.replace(os.sep, '/'):
        return []          # 测试不碰资源
    package = PACKAGE.search(src)
    if not package or package.group(1) == 'com.kooo.evcam':
        return []
    # 去掉字符串字面量再找：把 "R.style.xxx" 当文本比对的那种不算数
    if not R_REFERENCE.search(STRING_LITERAL.sub('""', src)) or R_IMPORT in src:
        return []
    return ['用了 R.xxx 但没有 %s（编译会报 package R does not exist）' % R_IMPORT]
OPENERS = set('([{')


def check(path):
    """返回该文件的问题列表。"""
    with io.open(path, encoding='utf-8') as handle:
        src = handle.read()

    problems = check_r_import(path, src)
    problems.extend(check_qualified_this(src))
    problems.extend(check_override_target(src))
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
