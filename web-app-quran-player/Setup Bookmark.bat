@echo off
setlocal
rem Registers the quranplayer: URL protocol for THIS folder's location.
rem Run once per Windows user account. No administrator rights needed.
rem After running, bookmark  quranplayer:open  in your browser.

set "VBS=%~dp0launch_quran_player.vbs"

if not exist "%VBS%" (
  echo ERROR: launch_quran_player.vbs not found next to this script.
  echo Make sure this .bat stays in the web-app-quran-player folder.
  pause
  exit /b 1
)

reg add "HKCU\Software\Classes\quranplayer" /ve /d "URL:Quran Player Protocol" /f >nul
reg add "HKCU\Software\Classes\quranplayer" /v "URL Protocol" /d "" /f >nul
reg add "HKCU\Software\Classes\quranplayer\shell\open\command" /ve /d "wscript.exe \"%VBS%\" \"%%1\"" /f >nul

if errorlevel 1 (
  echo Registration FAILED.
  pause
  exit /b 1
)

echo Done. The quranplayer: protocol now points at:
echo   %VBS%
echo.
echo Next: add a browser bookmark with the address  quranplayer:open
echo (first click shows a one-time "Open wscript?" prompt - allow it.)
echo.
pause
