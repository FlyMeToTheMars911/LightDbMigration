@echo off
setlocal EnableExtensions DisableDelayedExpansion

if "%~1"=="--help" goto usage
if "%~1"=="-h" goto usage
if "%~1"=="/?" goto usage

pushd "%~dp0"
if errorlevel 1 goto invalid_directory

where.exe mvn.cmd >nul 2>nul
if errorlevel 1 goto missing_maven

echo [INFO] Building Light DB Migration in "%CD%"
echo [INFO] Maven will create the executable JAR and delivery ZIP, including the Java runtime.
call mvn.cmd -B clean package -DskipTests %*
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if not "%BUILD_EXIT_CODE%"=="0" goto build_failed

echo [INFO] Build completed.
echo [INFO] JAR: "%CD%\target\light-db-migration.jar"
echo [INFO] ZIP: "%CD%\target\light-db-migration-*.zip"
popd
exit /b 0

:build_failed
echo [ERROR] Maven build failed. Exit code: %BUILD_EXIT_CODE%
popd
exit /b %BUILD_EXIT_CODE%

:missing_maven
echo [ERROR] Maven was not found. Install Maven 3.6.3 or newer and add its bin directory to PATH.
echo [ERROR] Use JDK 8 for the build and configure JAVA_HOME if required by Maven.
popd
exit /b 1

:invalid_directory
echo [ERROR] Could not enter the project directory: "%~dp0"
exit /b 1

:usage
echo Usage: build.bat [Maven options]
echo.
echo Requires JDK 8 and Maven 3.6.3 or newer on PATH.
echo Runs: mvn.cmd -B clean package -DskipTests [Maven options]
echo WARNING: clean removes the project's target directory.
echo.
echo Examples:
echo   build.bat
echo   build.bat "-Dbundled.java.home=D:\delivery\jdk8"
echo   build.bat -o
echo.
echo Choose a bundled Java runtime matching the delivery OS and CPU architecture.
exit /b 0
