@echo off
rem Same app as Localink.vbs, but keeps a console window so you can see errors.

setlocal
set "JAVA=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
if not exist "%JAVA%" (
  if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
)
if not exist "%JAVA%" set "JAVA=java"

"%JAVA%" -jar "%~dp0localink.jar"
echo.
echo Localink exited. Press any key to close.
pause > nul
