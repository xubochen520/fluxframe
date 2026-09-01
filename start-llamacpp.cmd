@echo off
rem ============================================================
rem  llama.cpp 本地视觉识别服务 一键启动
rem  用法：
rem   1. 从 https://github.com/ggml-org/llama.cpp/releases 下载
rem      llama-*-bin-win-avx2-x64.zip（纯CPU）或
rem      llama-*-bin-win-cuda-x64.zip（NVIDIA显卡，推荐）
rem      解压后把 llama-server.exe 放到本脚本同目录或 models\ 下
rem   2. 从 Hugging Face 下载 GGUF 模型 + mmproj 文件放入 models\
rem      例如：qwen2.5-vl-7b-instruct-q4_k_m.gguf
rem            qwen2.5-vl-7b-instruct-mmproj-f16.gguf
rem   3. 双击本脚本
rem  启动后在本系统设置页填写：
rem      AI 接口地址：http://127.0.0.1:8080/v1
rem ============================================================
setlocal enabledelayedexpansion
set SCRIPT_DIR=%~dp0
set MODEL_DIR=%SCRIPT_DIR%models
set PORT=8080
set CTX=8192

if not exist "%SCRIPT_DIR%llama-server.exe" (
  if exist "%SCRIPT_DIR%models\llama-server.exe" (
    set SERVER=%SCRIPT_DIR%models\llama-server.exe
  ) else (
    echo [错误] 未找到 llama-server.exe。
    echo 请从 llama.cpp Releases 下载对应版本，解压后放到：
    echo   %SCRIPT_DIR%
    echo 或：%MODEL_DIR%
    echo.
    pause
    exit /b 1
  )
) else (
  set SERVER=%SCRIPT_DIR%llama-server.exe
)

if not exist "%MODEL_DIR%" mkdir "%MODEL_DIR%"

set FOUND_MODEL=
set FOUND_MMPROJ=
for %%f in ("%MODEL_DIR%\*.gguf") do (
  echo %%~nxf | findstr /i "mmproj" >nul
  if errorlevel 1 (
    if not defined FOUND_MODEL set FOUND_MODEL=%%~f
  ) else (
    if not defined FOUND_MMPROJ set FOUND_MMPROJ=%%~f
  )
)

if not defined FOUND_MODEL (
  echo [错误] models\ 目录下没有找到模型 GGUF 文件。
  echo 请先下载，例如：
  echo   https://huggingface.co/Qwen/Qwen2.5-VL-7B-Instruct-GGUF
  echo   qwen2.5-vl-7b-instruct-q4_k_m.gguf ^(模型^)
  echo   qwen2.5-vl-7b-instruct-mmproj-f16.gguf ^(视觉投影^)
  pause
  exit /b 1
)
if not defined FOUND_MMPROJ (
  echo [错误] models\ 目录下没有找到 mmproj 视觉投影文件。
  echo 请从同一模型的 GGUF 仓库下载 mmproj 文件放到 models\。
  pause
  exit /b 1
)

echo ============================================================
echo  llama.cpp 视觉识别服务
echo  模型:   %FOUND_MODEL%
echo  投影:   %FOUND_MMPROJ%
echo  地址:   http://127.0.0.1:%PORT%
echo  管理台: http://127.0.0.1:%PORT%  ^(浏览器打开^)
echo  ^(按 Ctrl+C 停止服务^)
echo ============================================================
echo.

"%SERVER%" -m "%FOUND_MODEL%" --mmproj "%FOUND_MMPROJ%" -c %CTX% -fa --port %PORT% %*

echo.
echo 服务已退出。
pause
endlocal
