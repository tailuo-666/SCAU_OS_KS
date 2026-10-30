#!/bin/bash
# ---------------------------------------------------------------------------
# 在 Git Bash 下运行 Maven 的包装脚本。
#
# 为什么需要它：Maven 自带的 bin/mvn 在 MINGW（Git Bash）分支里会把 /d/... 形式的
# Unix 路径传给 Windows 版 java，导致 ClassNotFoundException: Launcher。
# 这里直接用 java + classworlds 启动器，并显式传入 Windows 路径。
#
# 用法：  ./scripts/mvn.sh clean test
# ---------------------------------------------------------------------------
set -e

MAVEN_HOME="${MAVEN_HOME:-D:/develop/apache-maven-3.9.4}"

# 项目根目录（本脚本位于 <root>/scripts 下）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# Git Bash 的 pwd -W 返回 Windows 风格路径，java 才能正确解析
if PROJECT_DIR_WIN="$(cd "$PROJECT_DIR" && pwd -W 2>/dev/null)"; then
    PROJECT_DIR="$PROJECT_DIR_WIN"
fi

CLASSWORLDS_JAR="$(ls "$MAVEN_HOME"/boot/plexus-classworlds-*.jar | head -1)"

cd "$PROJECT_DIR"
exec java \
    -cp "$CLASSWORLDS_JAR" \
    -Dclassworlds.conf="$MAVEN_HOME/bin/m2.conf" \
    -Dmaven.home="$MAVEN_HOME" \
    -Dmaven.multiModuleProjectDirectory="$PROJECT_DIR" \
    org.codehaus.plexus.classworlds.launcher.Launcher "$@"
