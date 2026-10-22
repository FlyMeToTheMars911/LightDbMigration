#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

DEFAULT_PORT=8088
MIN_PORT=1
MAX_PORT=65535
APP_PORT="${1:-$DEFAULT_PORT}"
case "$APP_PORT" in
    *[!0-9]*|'')
        echo "端口必须是 1 到 65535 之间的整数。示例：sh start.sh 18088"
        exit 1
        ;;
esac
if [ "${#APP_PORT}" -gt 5 ]; then
    echo "端口最多使用五位数字。示例：sh start.sh 18088"
    exit 1
fi
while [ "${APP_PORT#0}" != "$APP_PORT" ]; do
    APP_PORT=${APP_PORT#0}
done
APP_PORT=${APP_PORT:-0}
if [ "$APP_PORT" -lt "$MIN_PORT" ] || [ "$APP_PORT" -gt "$MAX_PORT" ]; then
    echo "端口必须是 1 到 65535 之间的整数。"
    exit 1
fi

APP_JAR="./light-db-migration.jar"
if [ ! -f "$APP_JAR" ]; then
    APP_JAR="./target/light-db-migration.jar"
fi
if [ ! -f "$APP_JAR" ]; then
    echo "未找到 light-db-migration.jar，请把脚本与发布 Jar 放在同一目录。"
    exit 1
fi

if [ -e "./runtime" ]; then
    JAVA_EXE="$SCRIPT_DIR/runtime/bin/java"
    if [ ! -x "$JAVA_EXE" ]; then
        echo "自带 Java 运行环境不完整、无执行权限或不适用于当前操作系统：$JAVA_EXE"
        echo "请完整解压适用于当前操作系统和 CPU 架构的交付包。"
        exit 1
    fi
elif [ -n "${JAVA_HOME:-}" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVA_EXE" ]; then
        echo "JAVA_HOME 下未找到可执行的 Java：$JAVA_EXE"
        exit 1
    fi
else
    JAVA_EXE=$(command -v java || true)
    if [ -z "$JAVA_EXE" ]; then
        echo "未找到 Java，请使用包含 runtime 目录的完整交付包。"
        exit 1
    fi
fi
if ! "$JAVA_EXE" -version >/dev/null 2>&1; then
    echo "Java 无法运行，请检查交付包的操作系统和 CPU 架构：$JAVA_EXE"
    exit 1
fi

echo "Java：$JAVA_EXE"
echo "正在启动 Light DB Migration：http://127.0.0.1:$APP_PORT"
exec "$JAVA_EXE" -Dfile.encoding=UTF-8 -jar "$APP_JAR" "--server.port=$APP_PORT"
