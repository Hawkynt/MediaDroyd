@echo off
rem Installs MediaDroyd for the desktop into your account on Windows (#497): the app into
rem %LOCALAPPDATA%\Programs\MediaDroyd and a shortcut into the Start menu. Settings stay where the app keeps them
rem (%APPDATA%\MediaDroyd) through every update and uninstall.
rem
rem   install.bat              the newest published build (a nightly until a stable one is out), the portable zip
rem   install.bat /stable      the newest stable release only
rem   install.bat /build       built from this checkout (needs a JDK 21, CMake and Visual C++ for the native library)
rem   install.bat /build /full the same with Bytedeco's GPL FFmpeg build: for your own machine only, never passed on
rem   install.bat /uninstall   removes the app and its shortcut; keeps the settings
setlocal EnableExtensions

set "REPO=Hawkynt/MediaDroyd"
set "HERE=%~dp0"
set "APP=%LOCALAPPDATA%\Programs\MediaDroyd"
set "LINK=%APPDATA%\Microsoft\Windows\Start Menu\Programs\MediaDroyd.lnk"
set "MODE=download"
set "STABLE=0"
set "FULL="

:args
if "%~1"=="" goto run
if /i "%~1"=="/build" set "MODE=build"
if /i "%~1"=="/full" set "FULL=-PfullFfmpeg"
if /i "%~1"=="/stable" set "STABLE=1"
if /i "%~1"=="/uninstall" set "MODE=uninstall"
if /i "%~1"=="/?" goto usage
shift
goto args

:usage
findstr /b /c:"rem   install" "%~f0"
exit /b 0

:run
if "%MODE%"=="uninstall" (
  if exist "%APP%" rmdir /s /q "%APP%"
  if exist "%LINK%" del "%LINK%"
  echo MediaDroyd removed; its settings stay in %APPDATA%\MediaDroyd.
  exit /b 0
)

set "WORK=%TEMP%\MediaDroyd-install-%RANDOM%"
mkdir "%WORK%" || exit /b 1

if "%MODE%"=="build" (
  if not exist "%HERE%gradlew.bat" (
    echo /build needs the MediaDroyd source checkout this script came with.
    goto fail
  )
  pushd "%HERE%"
  call gradlew.bat :desktop:createDistributable --no-daemon %FULL% || (popd & goto fail)
  popd
  set "SOURCE=%HERE%desktop\build\compose\binaries\main\app\MediaDroyd"
) else (
  if defined FULL (
    echo /full applies to /build only: published builds carry the LGPL FFmpeg.
    goto fail
  )
  rem The newest release carrying the zip, stable ones only with /stable; unpacked into the work folder.
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; [Net.ServicePointManager]::SecurityProtocol = 'Tls12';" ^
    "$want = 'MediaDroyd-windows-x64.zip';" ^
    "$all = Invoke-RestMethod 'https://api.github.com/repos/%REPO%/releases?per_page=30';" ^
    "$release = $all | Where-Object { ('%STABLE%' -eq '0' -or -not $_.prerelease) -and ($_.assets.name -contains $want) } | Select-Object -First 1;" ^
    "if (-not $release) { throw ('no release carries ' + $want + ' yet') };" ^
    "$url = ($release.assets | Where-Object name -eq $want).browser_download_url;" ^
    "Write-Host ('Downloading ' + $url);" ^
    "Invoke-WebRequest $url -OutFile '%WORK%\app.zip' -UseBasicParsing;" ^
    "Expand-Archive '%WORK%\app.zip' '%WORK%\unpacked'" || goto fail
  set "SOURCE=%WORK%\unpacked\MediaDroyd"
)

if not exist "%SOURCE%" (
  echo No app to install in %SOURCE%.
  goto fail
)

rem The app replaced whole; robocopy's exit codes below 8 mean success.
robocopy "%SOURCE%" "%APP%" /MIR /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto fail
rem Not portable once installed: settings go to %APPDATA%\MediaDroyd, untouched by the next update.
if exist "%APP%\portable" del "%APP%\portable"

set "TARGET=%APP%\MediaDroyd.exe"
if not exist "%TARGET%" set "TARGET=%APP%\MediaDroyd.bat"
set "ICON=%APP%\MediaDroyd.ico"
if not exist "%ICON%" if exist "%HERE%desktop\icons\mediadroyd.ico" copy /y "%HERE%desktop\icons\mediadroyd.ico" "%ICON%" >nul
if not exist "%ICON%" set "ICON=%TARGET%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$shell = New-Object -ComObject WScript.Shell; $link = $shell.CreateShortcut('%LINK%');" ^
  "$link.TargetPath = '%TARGET%'; $link.WorkingDirectory = '%APP%'; $link.IconLocation = '%ICON%';" ^
  "$link.Description = 'Plays the movies and shows already organised in your folders'; $link.Save()" || goto fail

rmdir /s /q "%WORK%" 2>nul
echo MediaDroyd installed in %APP% and added to the Start menu.
exit /b 0

:fail
rmdir /s /q "%WORK%" 2>nul
echo Installing MediaDroyd failed.
exit /b 1
