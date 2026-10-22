@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Light DB Migration
cd /d "%~dp0"

set "DEFAULT_PORT=8088"
set "MIN_PORT=1"
set "MAX_PORT=65535"
set "APP_PORT=%~1"
if not defined APP_PORT set "APP_PORT=%DEFAULT_PORT%"
set "PORT_REMAINDER=x%APP_PORT%"
set "PORT_REMAINDER=%PORT_REMAINDER:0=%"
set "PORT_REMAINDER=%PORT_REMAINDER:1=%"
set "PORT_REMAINDER=%PORT_REMAINDER:2=%"
set "PORT_REMAINDER=%PORT_REMAINDER:3=%"
set "PORT_REMAINDER=%PORT_REMAINDER:4=%"
set "PORT_REMAINDER=%PORT_REMAINDER:5=%"
set "PORT_REMAINDER=%PORT_REMAINDER:6=%"
set "PORT_REMAINDER=%PORT_REMAINDER:7=%"
set "PORT_REMAINDER=%PORT_REMAINDER:8=%"
set "PORT_REMAINDER=%PORT_REMAINDER:9=%"
if not "%PORT_REMAINDER%"=="x" goto invalid_port
if not "%APP_PORT:~5,1%"=="" goto invalid_port
rem Prefixing five padded digits avoids cmd interpreting leading zeroes as octal.
set "PORT_DECIMAL=00000%APP_PORT%"
set /a "APP_PORT=1%PORT_DECIMAL:~-5% - 100000" >nul
if %APP_PORT% LSS %MIN_PORT% goto invalid_port
if %APP_PORT% GTR %MAX_PORT% goto invalid_port

set "APP_JAR=light-db-migration.jar"
if not exist "%APP_JAR%" set "APP_JAR=target\light-db-migration.jar"
if not exist "%APP_JAR%" goto missing_jar

if exist "%~dp0runtime" goto bundled_java
if defined JAVA_HOME goto java_home
set "JAVA_EXE=java.exe"
where java.exe >nul 2>nul
if errorlevel 1 goto missing_java
goto java_ready

:bundled_java
set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not exist "%JAVA_EXE%" goto invalid_runtime
goto java_ready

:java_home
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" goto missing_java

:java_ready
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 goto invalid_java
echo Starting Light DB Migration...
echo Java: "%JAVA_EXE%"
echo Page: http://127.0.0.1:%APP_PORT%
echo Open the page after the service has started.
if not defined LIGHT_DB_MIGRATION_NO_BROWSER start "" "http://127.0.0.1:%APP_PORT%"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%APP_JAR%" --server.port=%APP_PORT%
set "APP_EXIT_CODE=%ERRORLEVEL%"
echo.
echo Service stopped. Exit code: %APP_EXIT_CODE%
if not defined LIGHT_DB_MIGRATION_NO_PAUSE pause
exit /b %APP_EXIT_CODE%

:invalid_port
echo [ERROR] Port must be an integer from %MIN_PORT% to %MAX_PORT% with at most five digits.
echo Example: start.bat 18088
goto failed

:missing_jar
echo [ERROR] light-db-migration.jar was not found beside the script or in target.
echo Extract the complete delivery ZIP, or build the application first.
goto failed

:invalid_runtime
echo [ERROR] The bundled runtime is incomplete or belongs to another operating system.
echo Expected: "%JAVA_EXE%"
echo Extract the complete delivery ZIP for Windows.
goto failed

:missing_java
echo [ERROR] Java was not found. Use the complete delivery ZIP with its runtime directory.
echo Developers can alternatively set JAVA_HOME or add Java to PATH.
goto failed

:invalid_java
echo [ERROR] Java could not run: "%JAVA_EXE%"
echo Use a runtime matching this operating system and CPU architecture.

:failed
if not defined LIGHT_DB_MIGRATION_NO_PAUSE pause
exit /b 1
