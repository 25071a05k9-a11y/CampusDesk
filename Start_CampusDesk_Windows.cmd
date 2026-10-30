@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title CampusDesk - Java + AGY + Jev
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start_CampusDesk_Windows.ps1"
if errorlevel 1 (
 echo.
 echo CampusDesk could not start. Please read the message above.
 pause
)
