#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自动生成 docs/CODE_INDEX.md：把每个类、每个方法所在的行号整理成索引表。

生成规则：
  1. 只索引"有意义的成员"——方法、构造器、public 常量；
  2. 成员归属由 OWNER 表决定（一个类整体归一个人，避免边界扯皮）；
  3. 行号是当前源码的真实行号，改代码后重新跑一次本脚本即可同步。

用法（在项目根目录）：python scripts/gen_index.py
"""
import pathlib
import re

ROOT = pathlib.Path("src/main/java")
TEST_ROOT = pathlib.Path("src/test/java")

# 类名 -> (成员, 角色说明)
OWNER = {
    # 成员 A：内核与总体架构
    "OperatingSystem": "A", "SystemSnapshot": "A", "EventLog": "A",
    "LogEntry": "A", "LogLevel": "A",
    "Pcb": "A", "ProcessManager": "A", "ProcessState": "A", "ProgramLoader": "A",
    "MemoryManager": "A", "MemoryPartition": "A",
    "DeviceManager": "A", "Device": "A", "DeviceType": "A", "DeviceRequest": "A",
    "Cpu": "A", "CpuOutcome": "A", "InstructionCodec": "A", "AssemblyException": "A",
    "TextUtil": "A", "Main": "A",
    # 成员 B：文件系统
    "Disk": "B", "Fat": "B", "DirEntry": "B", "FileAttribute": "B", "PathUtil": "B",
    "OpenFile": "B", "OpenFileTable": "B", "DirNode": "B", "FileSystem": "B",
    "DefaultFileTree": "B", "FsException": "B",
    # 成员 C：命令层 + 界面框架
    "Shell": "C", "CommandResult": "C", "ConfirmService": "C", "EditorService": "C",
    "MainFrame": "C", "Theme": "C", "ControlPanel": "C", "SimulationDriver": "C",
    "CardPanel": "C", "FlatButton": "C", "SnapshotAware": "C", "LogPanel": "C",
    # 成员 D：数据视图
    "ProcessPanel": "D", "MemoryPanel": "D", "DiskGridPanel": "D",
    "FileSystemPanel": "D", "DevicePanel": "D", "CommandPanel": "D", "EditorDialog": "D",
}

# 测试类归属
TEST_OWNER = {
    "FileSystemTest": "B",
    "MemoryManagerTest": "A", "DeviceManagerTest": "A", "InstructionCodecTest": "A",
    "ProcessSchedulingTest": "A", "IntegratedScenarioTest": "A", "ShellTest": "C",
}

MEMBER_TITLE = {
    "A": "成员 A · 内核调度与总体架构（后端专家）",
    "B": "成员 B · 文件系统与磁盘存储",
    "C": "成员 C · 命令层与界面框架",
    "D": "成员 D · 数据视图与交互面板",
}

MEMBER_WEIGHT = {"A": "0.40", "B": "0.20", "C": "0.20", "D": "0.20"}

METHOD_RE = re.compile(r"^\s{4}(?:public|private|protected)\s+(?!class\b)(?:static\s+)?(?:final\s+)?"
                       r"[\w<>\[\],.? ]*?\b(\w+)\s*\(")
FIELD_RE = re.compile(r"^\s{4}public\s+static\s+final\s+[\w<>\[\]]+\s+(\w+)\s*=")


def _is_decoration(text):
    """判断是不是 // ---------- 这种纯装饰行。"""
    body = text.lstrip("/").strip()
    return not body or set(body) <= set("-=*")


def brief(lines, idx):
    """优先取方法上方 Javadoc 的第一句；没有就取最近的 // 注释；都没有返回空。"""
    i = idx - 1
    # 跳过注解、空行
    while i >= 0 and (lines[i].strip().startswith("@") or not lines[i].strip()):
        i -= 1
    if i < 0:
        return ""
    s = lines[i].strip()

    if s.endswith("*/"):
        end = i
        while i >= 0 and "/**" not in lines[i]:
            i -= 1
        if i < 0:
            return ""
        for ln in lines[i + 1:end]:
            t = ln.strip().lstrip("*").strip()
            if not t or t.startswith("@") or t.startswith("<") or t.startswith("|"):
                continue
            t = re.sub(r"\{@(?:link|code)\s+([^}]*)\}", r"\1", t)
            t = re.sub(r"<[^>]+>", "", t)
            return t.strip("。 ")
        return ""

    if s.startswith("//"):
        # 往上找第一条有内容的 // 注释，跳过 // ------ 装饰行
        while i >= 0:
            t = lines[i].strip()
            if t.startswith("//"):
                if not _is_decoration(t):
                    return t.lstrip("/").strip()
                i -= 1
                continue
            if t:
                break
            i -= 1
    return ""


def scan(path):
    lines = path.read_text(encoding="utf-8").split("\n")
    items = []
    for i, ln in enumerate(lines):
        m = METHOD_RE.match(ln)
        if m:
            name = m.group(1)
            if name in ("if", "for", "while", "switch", "catch", "return", "new"):
                continue
            items.append((i + 1, name + "()", brief(lines, i)))
    for i, ln in enumerate(lines):
        m = FIELD_RE.match(ln)
        if m:
            items.append((i + 1, m.group(1), brief(lines, i)))
    items.sort()
    # 去重（同名重载只保留第一次出现）
    seen, unique = set(), []
    for row in items:
        if row[1] in seen:
            continue
        seen.add(row[1])
        unique.append(row)
    return lines, unique


def main():
    groups = {k: [] for k in "ABCD"}
    total_lines = 0
    for path in sorted(ROOT.rglob("*.java")):
        cls = path.stem
        owner = OWNER.get(cls, "?")
        lines, items = scan(path)
        n = len(lines)
        if lines and lines[-1] == "":
            n -= 1
        total_lines += n
        groups[owner].append((cls, str(path).replace("\\", "/"), n, items))

    tests = []
    for path in sorted(TEST_ROOT.rglob("*.java")):
        cls = path.stem
        lines, items = scan(path)
        n = len(lines) - (1 if lines and lines[-1] == "" else 0)
        tests.append((cls, str(path).replace("\\", "/"), n, TEST_OWNER.get(cls, "?")))

    out = []
    out.append("# SCAU-OS 代码索引（按负责人）\n")
    out.append("> 本文件由 `target/tools/gen_index.py` 扫描源码自动生成，行号与当前代码一一对应。\n")
    out.append("> 修改代码后重新执行 `python scripts/gen_index.py` 即可刷新。\n")
    out.append("> 行号含义：该成员定义（方法 / 常量）在文件中的起始行，便于直接跳转。\n")

    out.append("\n## 一、总览\n")
    test_lines = {k: 0 for k in "ABCD"}
    for _cls, _rel, n, owner in tests:
        test_lines[owner] += n
    grand = total_lines + sum(test_lines.values())
    out.append("| 成员 | 角色 | 权重 | 类数 | 主代码行 | 测试行 | 合计 | 占比 |")
    out.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for k in "ABCD":
        n_cls = len(groups[k])
        n_line = sum(g[2] for g in groups[k])
        n_all = n_line + test_lines[k]
        out.append("| %s | %s | %s | %d | %d | %d | %d | %.1f%% |" % (
            k, MEMBER_TITLE[k].split("·")[1].strip(), MEMBER_WEIGHT[k],
            n_cls, n_line, test_lines[k], n_all, n_all * 100.0 / grand))
    out.append("| 合计 | — | 1.00 | %d | %d | %d | %d | 100%% |" % (
        sum(len(groups[k]) for k in "ABCD"), total_lines,
        sum(test_lines.values()), grand))

    for k in "ABCD":
        out.append("\n---\n")
        out.append("## %s　权重 %s\n" % (MEMBER_TITLE[k], MEMBER_WEIGHT[k]))
        for cls, rel, n, items in groups[k]:
            out.append("### `cn.scau.os.%s`　%s 行　`%s`" % (cls, n, rel))
            out.append("")
            out.append("| 行号 | 成员 | 一句话说明 |")
            out.append("| --- | --- | --- |")
            for line_no, name, desc in items:
                out.append("| %d | `%s` | %s |" % (line_no, name, desc or "—"))
            out.append("")

    out.append("\n---\n")
    out.append("## 附：测试代码索引\n")
    out.append("| 测试类 | 行数 | 负责成员 | 路径 |")
    out.append("| --- | --- | --- | --- |")
    for cls, rel, n, owner in tests:
        out.append("| `%s` | %d | %s | `%s` |" % (cls, n, owner, rel))

    pathlib.Path("docs/CODE_INDEX.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print("已生成 docs/CODE_INDEX.md：%d 个类，%d 行代码" % (
        sum(len(groups[k]) for k in "ABCD"), total_lines))


if __name__ == "__main__":
    main()
