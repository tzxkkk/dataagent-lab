@echo off
setlocal
set "VITE_USE_MOCK=false"
cd /d "%~dp0frontend"
call npm.cmd run dev
