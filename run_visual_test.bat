@echo off
title GuardianGrid - Visual Scream Detection Verifier
cd /d "%~dp0"
echo =====================================================================
echo   Launching GuardianGrid Visual Scream Detection Verifier (GUI)
echo   Model: Google MediaPipe YAMNet (yamnet.tflite)
echo =====================================================================
echo.
"C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests\live_audio_test\live_audio_test.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Application exited with error code %ERRORLEVEL%.
    pause
)
