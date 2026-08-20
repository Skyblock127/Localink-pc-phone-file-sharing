@echo off
rem Puts Localink in the Start menu. Safe to run again after an update.

setlocal
set "HERE=%~dp0"
set "LNK=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Localink.lnk"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%LNK%');" ^
  "$s.TargetPath='%HERE%Localink.vbs';" ^
  "$s.WorkingDirectory='%HERE%';" ^
  "$s.IconLocation='%HERE%Localink.ico';" ^
  "$s.Description='Send files between this laptop and your phone';" ^
  "$s.Save()"

if errorlevel 1 (
  echo Could not create the shortcut.
  pause
  exit /b 1
)

echo Localink is now in your Start menu.
echo Search for it, and pin it if you like.
