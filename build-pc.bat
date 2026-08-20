@echo off
setlocal

rem Rebuilds the laptop app. Pass "test" to run the end-to-end tests instead.

set "JBR=C:\Program Files\Android\Android Studio\jbr\bin"
if not exist "%JBR%\javac.exe" (
  if defined JAVA_HOME set "JBR=%JAVA_HOME%\bin"
)
if not exist "%JBR%\javac.exe" (
  echo Could not find javac. Install Android Studio or set JAVA_HOME.
  exit /b 1
)

cd /d "%~dp0"

if /i "%1"=="test" goto :test
if /i "%1"=="diag" goto :diag

echo Compiling laptop app...
if exist build\pc rmdir /s /q build\pc
mkdir build\pc
dir /s /b shared\src\*.java pc\src\*.java > build\sources.txt
"%JBR%\javac.exe" --release 11 -nowarn -d build\pc @build\sources.txt
if errorlevel 1 exit /b 1

echo Main-Class: fileshare.pc.App> build\manifest.txt
if not exist dist mkdir dist
copy /y pc\src\fileshare\pc\logo.png build\pc\fileshare\pc\ >nul
"%JBR%\jar.exe" cfm dist\localink.jar build\manifest.txt -C build\pc .
if errorlevel 1 exit /b 1

echo.
echo Built dist\localink.jar
echo Start it with dist\Localink.vbs
goto :eof

:diag
if exist build\test rmdir /s /q build\test
mkdir build\test
dir /s /b shared\src\*.java pc\src\*.java test\src\*.java > build\sources.txt
"%JBR%\javac.exe" --release 11 -nowarn -d build\test @build\sources.txt
if errorlevel 1 exit /b 1
"%JBR%\java.exe" -cp build\test fileshare.test.Diag
goto :eof

:test
echo Compiling tests...
if exist build\test rmdir /s /q build\test
mkdir build\test
dir /s /b shared\src\*.java pc\src\*.java test\src\*.java > build\sources.txt
"%JBR%\javac.exe" --release 11 -nowarn -d build\test @build\sources.txt
if errorlevel 1 exit /b 1
"%JBR%\java.exe" -cp build\test fileshare.test.SelfTest
