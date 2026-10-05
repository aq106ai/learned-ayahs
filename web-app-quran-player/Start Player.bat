@echo off
cd /d "%~dp0"
echo Starting Learned Ayahs Player...
python play_learned_ayahs.py --serve-only
if errorlevel 1 pause
