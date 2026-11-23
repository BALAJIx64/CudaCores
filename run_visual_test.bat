@echo off
setlocal
title GuardianGrid - Live Scream Detection Verifier
echo =========================================================================
echo GuardianGrid: Launching Visual Scream Detection Test
echo =========================================================================
cd /d "%~dp0"

set "PYTHON_EXE=C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe"

if not exist "%PYTHON_EXE%" (
    echo [ERROR] Python embed interpreter not found at: %PYTHON_EXE%
    pause
    exit /b 1
)

echo Launching graphical UI...
"%PYTHON_EXE%" tests\live_audio_test\live_audio_test.py %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [INFO] Exited with code %ERRORLEVEL%.
    pause
)
