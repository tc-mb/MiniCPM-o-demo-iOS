@echo off
@rem Canonical Windows development environment for this Android project.
@rem Machine-specific paths may be overridden in ignored environment.local.bat.

for %%i in ("%~dp0..\..") do set "MINICPMV_WORKSPACE_ROOT=%%~fi"

if exist "%~dp0environment.local.bat" call "%~dp0environment.local.bat"

if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%MINICPMV_WORKSPACE_ROOT%\.gradle-user-home"
if not defined ANDROID_USER_HOME set "ANDROID_USER_HOME=%MINICPMV_WORKSPACE_ROOT%\.android"
set "ANDROID_PREFS_ROOT="
set "JAVA_TOOL_OPTIONS=-Duser.home=%MINICPMV_WORKSPACE_ROOT% %JAVA_TOOL_OPTIONS%"

if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined KLEIDIAI_SOURCE_DIR if exist "%MINICPMV_WORKSPACE_ROOT%\.native-deps\kleidiai-v1.24.0\CMakeLists.txt" set "KLEIDIAI_SOURCE_DIR=%MINICPMV_WORKSPACE_ROOT%\.native-deps\kleidiai-v1.24.0"

if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
if defined ANDROID_HOME set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"
