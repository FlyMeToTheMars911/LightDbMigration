@echo off
setlocal
chcp 65001 >nul
title Light DB Migration
cd /d "%~dp0"

set "APP_PORT=%~1"
if not defined APP_PORT set "APP_PORT=8088"
powershell -NoProfile -Command "$portNumber = 0; if (-not [int]::TryParse($env:APP_PORT, [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) { exit 1 }"
if errorlevel 1 (
    echo [错误] 端口必须是 1 到 65535 之间的整数。示例：start.bat 18088
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Java。请先安装 64 位 JDK 8，并把 java 加入 PATH。
    pause
    exit /b 1
)

set "APP_JAR=light-db-migration.jar"
if not exist "%APP_JAR%" set "APP_JAR=target\light-db-migration.jar"
if not exist "%APP_JAR%" (
    echo [错误] 未找到 light-db-migration.jar。
    echo 请把 start.bat 与发布 Jar 放在同一目录，或先由开发人员完成打包。
    pause
    exit /b 1
)

echo 正在启动 Light DB Migration...
echo 页面地址：http://127.0.0.1:%APP_PORT%
start "" /b powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 3; Start-Process 'http://127.0.0.1:%APP_PORT%'"
java -Dfile.encoding=UTF-8 -jar "%APP_JAR%" --server.port=%APP_PORT%

echo.
echo 服务已停止。
pause
