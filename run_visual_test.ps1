# GuardianGrid: Visual Scream Detection Verifier PowerShell Launcher
Set-Location $PSScriptRoot
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host "  Launching GuardianGrid Visual Scream Detection Verifier (GUI)" -ForegroundColor Cyan
Write-Host "  Model: Google MediaPipe YAMNet (yamnet.tflite)" -ForegroundColor Cyan
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host ""
& "C:\Users\Ashwin Kumar L\AppData\Local\Programs\Python311Embed\python.exe" tests\live_audio_test\live_audio_test.py
