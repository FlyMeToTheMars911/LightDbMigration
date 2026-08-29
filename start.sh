#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

APP_PORT="${1:-8088}"
case "$APP_PORT" in
    *[!0-9]*|'')
        echo "端口必须是 1 到 65535 之间的整数。示例：sh start.sh 18088"
        exit 1
        ;;
esac
if [ "$APP_PORT" -lt 1 ] || [ "$APP_PORT" -gt 65535 ]; then
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

echo "正在启动 Light DB Migration：http://127.0.0.1:$APP_PORT"
exec java -Dfile.encoding=UTF-8 -jar "$APP_JAR" "--server.port=$APP_PORT"
