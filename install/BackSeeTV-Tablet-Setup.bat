@echo off
rem ============================================================================
rem  BackSeeTV - one-step tablet setup for Windows 10 / 11
rem
rem  Plug a freshly reset tablet in over USB and double-click this file. It gets
rem  Google's adb tools, finds the tablet, installs BackSeeTV and sets it up
rem  as a locked kiosk (device owner).
rem
rem  On its own, it downloads BackSeeTV from this script's own GitHub release. Optional,
rem  next to this file:
rem    BackSeeTV-VERSION.apk     use this app instead of downloading one
rem    BackSeeTV-Media\           your shows, one folder per show.
rem                               Without it, the script asks for a folder.
rem
rem  Every run writes BackSeeTV-setup-log.txt next to this file. Attach it to
rem  any bug report.
rem ============================================================================

rem Double-clicking runs this in a window that closes the moment the script ends,
rem including when it dies unexpectedly. Re-run inside a shell that stays open,
rem so a failure is always left on screen. (No parenthesised block here: a folder
rem name with a bracket in it, like "Program Files (x86)", would break it.)
if /i "%~1"=="--run" goto main
cmd /k ""%~f0" --run"
rem Back here once that shell is closed with "exit", e.g. after a Ctrl+C.
set "ADB=%LOCALAPPDATA%\BackSeeTV\platform-tools\adb.exe"
call :stop_adb
exit /b
:main

setlocal EnableExtensions EnableDelayedExpansion
title BackSeeTV - tablet setup

rem Where to download the app (and its tablet-side helper) from when they don't sit next to
rem this file: the release this script ships in. Bump VER with anancyVersion each release.
set "VER=0.1.0"
set "REL_URL=https://github.com/acorkran/backseetv/releases/download/v%VER%"
set "APK_URL=%REL_URL%/BackSeeTV-%VER%.apk"
set "MEDIA_SH_URL=%REL_URL%/bst-media.sh"

set "HERE=%~dp0"
set "BST_HOME=%LOCALAPPDATA%\BackSeeTV"
set "ADB=%BST_HOME%\platform-tools\adb.exe"
set "OUT=%TEMP%\backseetv-setup-%RANDOM%.txt"
set "ADMIN=app.anancy/.admin.AdminReceiver"
if not exist "%BST_HOME%" mkdir "%BST_HOME%" >nul 2>&1

rem The log goes next to this file; if that folder is read-only, into BST_HOME.
set "LOG=%HERE%BackSeeTV-setup-log.txt"
(echo.>>"%LOG%") 2>nul || set "LOG=%BST_HOME%\BackSeeTV-setup-log.txt"
>>"%LOG%" echo ============================================================
call :log "BackSeeTV tablet setup started"
>>"%LOG%" ver
>>"%LOG%" echo Script folder: %HERE%

echo.
echo  BackSeeTV - tablet setup
echo  --------------------------
echo  Before you start, on the tablet:
echo    1. Factory reset it, or use one with NO Google or Samsung account on it.
echo       During setup, skip every sign-in.
echo    2. Settings - About tablet - Software information - tap Build number 7 times.
echo    3. Settings - Developer options - turn on USB debugging.
echo    4. Plug it into this PC with a data USB cable and unlock its screen.
echo    5. The tablet asks "Allow USB debugging?".
echo       Tick "Always allow from this computer" and tap Allow.
echo       Without that, this PC can't set anything up.
echo.
pause

rem --- 1. adb --------------------------------------------------------------
echo.
echo [1/6] Getting the Android tools...
call :log "Step 1: adb"
if exist "%ADB%" goto have_adb
set "BST_ZIP=%BST_HOME%\platform-tools.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $env:BST_ZIP; Expand-Archive -Force -Path $env:BST_ZIP -DestinationPath $env:BST_HOME" >"%OUT%" 2>&1
set "RC=!errorlevel!"
type "%OUT%" >>"%LOG%"
if not "%RC%"=="0" goto no_adb
del "%BST_ZIP%" >nul 2>&1
if not exist "%ADB%" goto no_adb
:have_adb
"%ADB%" version >>"%LOG%" 2>&1
echo       Ready.

rem --- 2. the app ----------------------------------------------------------
echo.
echo [2/6] Finding the app and your shows...
call :log "Step 2: app"
set "APK="
for %%F in ("%HERE%BackSeeTV*.apk") do (
    set "NAME=%%~nF"
    if /i "!NAME:wear=!"=="!NAME!" set "APK=%%~fF"
)
if defined APK goto have_apk
if not defined APK_URL goto no_apk
echo       Downloading BackSeeTV %VER% from GitHub...
set "APK=%BST_HOME%\BackSeeTV-%VER%.apk"
set "BST_APK=%APK%"
set "BST_URL=%APK_URL%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing -Uri $env:BST_URL -OutFile $env:BST_APK" >"%OUT%" 2>&1
set "RC=!errorlevel!"
type "%OUT%" >>"%LOG%"
if not "%RC%"=="0" goto no_apk
:have_apk
>>"%LOG%" echo APK: %APK%
echo       Using %APK%

rem Shows: a BackSeeTV-Media folder next to this file, or one the user drags in. Asked now,
rem not at the end, so the long install can run without anyone watching.
set "SRC="
if exist "%HERE%BackSeeTV-Media\" set "SRC=%HERE%BackSeeTV-Media"
if defined SRC goto have_src
echo.
echo  Your shows: BackSeeTV plays videos (.mp4, .mkv or .m4v) from a folder called
echo  BackSeeTV-Media on the tablet or its SD card. Each folder inside it is one
echo  show, and each show becomes a playlist.
echo.
echo  To copy shows from this PC, drag their folder onto this window and press Enter.
echo  Press Enter on its own to skip. You can add shows later.
:ask_src
set "SRC="
set /p "SRC=Shows folder: "
if not defined SRC goto no_src
set "SRC=!SRC:"=!"
if "!SRC:~-1!"=="\" set "SRC=!SRC:~0,-1!"
if exist "!SRC!\" goto have_src
echo       Can't find that folder. Try again, or press Enter to skip.
goto ask_src
:no_src
call :log "No shows folder given"
goto src_done
:have_src
>>"%LOG%" echo Shows folder: !SRC!
echo       Shows from: !SRC!
:src_done

rem --- 3. find exactly one tablet -------------------------------------------
echo.
echo [3/6] Looking for the tablet...
call :log "Step 3: find the tablet"
"%ADB%" start-server >>"%LOG%" 2>&1
set /a WAITED=0
set "TOLD="
:wait_device
set /a READY=0, LOCKED=0
set "S="
"%ADB%" devices > "%OUT%" 2>nul
for /f "usebackq skip=1 tokens=1,2" %%a in ("%OUT%") do (
    if "%%b"=="device" ( set /a READY+=1 & set "S=%%a" )
    if "%%b"=="unauthorized" set /a LOCKED+=1
)
if !READY! GTR 1 goto many_devices
if !READY! EQU 1 goto found_device
if !LOCKED! GTR 0 if not defined TOLD (
    call :log "Tablet found but not authorised yet"
    echo.
    echo       The tablet is plugged in but hasn't allowed this PC yet.
    echo       Unlock its screen. On "Allow USB debugging?",
    echo       tick "Always allow from this computer" and tap Allow.
    echo       No prompt? Unplug it and plug it back in.
    set "TOLD=1"
)
set /a WAITED+=1
if !WAITED! EQU 15 call :driver_hint
timeout /t 2 /nobreak >nul
goto wait_device

:found_device
set "A="%ADB%" -s %S%"
set "MODEL="
%A% shell getprop ro.product.model > "%OUT%" 2>nul
for /f "usebackq delims=" %%m in ("%OUT%") do if not defined MODEL set "MODEL=%%m"
set "REL="
%A% shell getprop ro.build.version.release > "%OUT%" 2>nul
for /f "usebackq delims=" %%m in ("%OUT%") do if not defined REL set "REL=%%m"
>>"%LOG%" echo Tablet: %MODEL%, Android %REL%, serial %S%
echo       Found: %MODEL%  (%S%)

rem --- 4. is it safe to make this tablet a kiosk? ----------------------------
echo.
echo [4/6] Checking the tablet...
call :log "Step 4: check the tablet"
call :need_device
set "ALREADY="
%A% shell dumpsys device_policy > "%OUT%" 2>&1
findstr /c:"admin=ComponentInfo{app.anancy" "%OUT%" >nul && set "ALREADY=1"
if defined ALREADY (
    call :log "Already device owner: updating"
    echo       Already set up as a BackSeeTV kiosk. Updating it.
    goto confirmed
)
call :need_device
set "ACCTS="
%A% shell dumpsys account > "%OUT%" 2>&1
for /f "tokens=2" %%n in ('findstr /c:"Accounts:" "%OUT%"') do if not defined ACCTS set "ACCTS=%%n"
>>"%LOG%" echo Accounts on the tablet: %ACCTS%
if defined ACCTS if not "%ACCTS%"=="0" goto has_accounts
echo.
echo  This will turn %MODEL% into a locked BackSeeTV kiosk.
echo  You can undo it later in the app: Settings - Remove device owner.
echo.
set "OK="
set /p "OK=Type Y and press Enter to continue: "
if /i not "%OK%"=="Y" goto cancelled
:confirmed

rem --- 5. install and take ownership -----------------------------------------
echo.
echo [5/6] Installing BackSeeTV (this can take a minute)...
call :log "Step 5: install"
call :need_device
%A% install -r "%APK%" > "%OUT%" 2>&1
type "%OUT%" >>"%LOG%"
findstr /c:"Success" "%OUT%" >nul || goto install_failed
if defined ALREADY goto owned
call :need_device
%A% shell dpm set-device-owner %ADMIN% > "%OUT%" 2>&1
type "%OUT%" >>"%LOG%"
findstr /c:"Success" "%OUT%" >nul || goto owner_failed
:owned
call :need_device
%A% shell appops set app.anancy MANAGE_EXTERNAL_STORAGE allow >>"%LOG%" 2>&1
rem Granted up front so nothing waits for a tap on the tablet. Each Android version
rem uses a different set; the ones this version doesn't use are noted, not errors.
for %%P in (BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE ACCESS_FINE_LOCATION POST_NOTIFICATIONS) do call :grant %%P
%A% shell cmd bluetooth_manager enable >>"%LOG%" 2>&1
echo       Done.

rem --- 6. shows, lock, start -------------------------------------------------
echo.
echo [6/6] Copying shows and finishing up...
call :log "Step 6: shows and start"
call :scan_existing
set "MEDIA_SH=%HERE%bst-media.sh"
if exist "%MEDIA_SH%" goto have_media_sh
set "MEDIA_SH=%BST_HOME%\bst-media.sh"
set "BST_URL=%MEDIA_SH_URL%"
set "BST_APK=%MEDIA_SH%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing -Uri $env:BST_URL -OutFile $env:BST_APK" >>"%LOG%" 2>&1
if not exist "%MEDIA_SH%" exit /b 0
:have_media_sh
echo       Looking for videos already on the tablet...
call :need_device
%A% push "%MEDIA_SH%" /data/local/tmp/bst-media.sh >nul 2>&1
set "FOUNDLIST=%TEMP%\bst-found.txt"
%A% shell sh /data/local/tmp/bst-media.sh list > "%FOUNDLIST%" 2>nul
set /a FOUND=0
for /f "usebackq tokens=1-4 delims=|" %%a in ("%FOUNDLIST%") do set /a FOUND+=1
>>"%LOG%" echo Folders with videos already on the tablet: %FOUND%
type "%FOUNDLIST%" >>"%LOG%"
if %FOUND%==0 goto scan_done
echo.
echo       There are videos on the tablet outside BackSeeTV-Media. BackSeeTV only plays
echo       what's in BackSeeTV-Media, so it can move them there. It's a move on the same
echo       card: instant, nothing copied. Only video files move; nothing is overwritten.
for /f "usebackq tokens=1-4 delims=|" %%a in ("%FOUNDLIST%") do call :ask_move %%a %%b "%%c" %%d
echo.
:scan_done
del "%FOUNDLIST%" >nul 2>&1
exit /b 0

rem Only a number goes back to the tablet, never a folder name: a name on an SD card can hold
rem characters a shell would run. The name shown here is already cleaned up on the tablet
rem (letters, digits, space and . _ - ( ) ' only).
:ask_move
set "NUM=%~1"
for /f "delims=0123456789" %%x in ("%NUM%") do exit /b 0
set "WHERE=on the tablet"
if /i "%~2"=="sd" set "WHERE=on the SD card"
set "ANS="
set /p "ANS=      Move %~3 (%~4 videos, %WHERE%) into BackSeeTV-Media? [Y/N]: "
if /i "%ANS%"=="Y" goto ask_move_yes
>>"%LOG%" echo Left folder %NUM% where it is
exit /b 0
:ask_move_yes
call :need_device
%A% shell sh /data/local/tmp/bst-media.sh move %NUM% >>"%LOG%" 2>&1
set "MOVED=1"
echo       Moved.
exit /b 0

rem Copy the shows folder into BackSeeTV-Media on the tablet, if it fits. --sync skips files
rem already there, so a re-run only copies what's new.
:copy_shows
if not defined SRC exit /b 0
echo       Checking there's room on the tablet...
call :need_device
set "NEEDMB=0"
rem Only videos count: BackSeeTV plays .mp4, .mkv and .m4v, and anything else is skipped below.
powershell -NoProfile -ExecutionPolicy Bypass -Command "[math]::Ceiling(((Get-ChildItem -LiteralPath $env:SRC -Recurse -File -Include *.mp4,*.mkv,*.m4v -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum)/1MB)" > "%OUT%" 2>nul
set /p NEEDMB=<"%OUT%"
set "FREEKB=0"
%A% shell df -k /sdcard > "%OUT%" 2>&1
for /f "usebackq skip=1 tokens=4" %%k in ("%OUT%") do set "FREEKB=%%k"
set "HAVEKB=0"
%A% shell du -sk /sdcard/BackSeeTV-Media > "%OUT%" 2>nul
for /f "usebackq tokens=1" %%k in ("%OUT%") do set "HAVEKB=%%k"
set /a FREEMB=FREEKB/1024, HAVEMB=HAVEKB/1024
set /a ADDMB=NEEDMB-HAVEMB
if %ADDMB% LSS 0 set /a ADDMB=0
>>"%LOG%" echo Shows: %NEEDMB% MB on this PC, %HAVEMB% MB already on the tablet, %FREEMB% MB free there
rem Keep 1 GB spare: a full tablet stops recording, updating and even booting cleanly.
set /a SPARE=FREEMB-ADDMB-1024
if %SPARE% GEQ 0 goto copy_go
set /a NEEDGB=(ADDMB+1023)/1024, FREEGB=FREEMB/1024
echo.
echo       Not enough room: the shows need about %NEEDGB% GB, and the tablet has
echo       %FREEGB% GB free (keeping 1 GB spare). Nothing was copied.
echo       Pick fewer shows, or put them on a microSD card in a folder called BackSeeTV-Media.
call :log "Shows not copied: not enough room"
exit /b 0
:copy_go
%A% shell mkdir -p /sdcard/BackSeeTV-Media >>"%LOG%" 2>&1
set /a SUBS=0
for /d %%D in ("%SRC%\*") do set /a SUBS+=1
set "PUSHFAIL="
rem No ( ) blocks around %SRC%: a bracket in the folder name would break them.
if %SUBS% GTR 0 goto copy_each
rem A folder of videos with no sub-folders is one show, named after the folder.
call :push_one "%SRC%"
goto copy_check
:copy_each
for /d %%D in ("%SRC%\*") do call :push_one "%%~fD"
for %%F in ("%SRC%\*.mp4" "%SRC%\*.mkv" "%SRC%\*.m4v") do call :push_one "%%~fF"
:copy_check
if not defined PUSHFAIL exit /b 0
echo       Some files did not copy. Run this again to retry; it skips what's done.
call :log "Some shows failed to copy"
exit /b 0

:push_one
rem A folder with no videos in it (music, photos...) isn't a show: say so and don't copy it.
if not exist "%~1\" goto push_go
dir /s /b "%~1\*.mp4" "%~1\*.mkv" "%~1\*.m4v" >nul 2>&1
if not errorlevel 1 goto push_go
echo       Skipping %~nx1: no videos in it (BackSeeTV plays .mp4, .mkv and .m4v files).
>>"%LOG%" echo Skipped %~nx1: no .mp4, .mkv or .m4v files
exit /b 0
:push_go
echo       Copying %~nx1...
call :need_device
%A% push --sync "%~1" /sdcard/BackSeeTV-Media/ >>"%LOG%" 2>&1
if errorlevel 1 set "PUSHFAIL=1"
exit /b 0

rem Ask the running app to re-read its media folders. Through the adb-only role
rem alias: the app's network control port answers paired remotes only.
:rescan
%A% shell am start -n app.anancy/.AdbSetRole --es role player --ez rescan true >>"%LOG%" 2>&1
call :log "Asked the app to rescan its shows"
exit /b 0

rem How many show folders the app will find: internal storage and any SD card.
:count_shows
set "SHOWS=0"
call :need_device
rem Only folders with a video in them: a folder of music would count, and the app skips it.
%A% shell "for d in /sdcard/BackSeeTV-Media/*/ /storage/*/BackSeeTV-Media/*/; do [ -d \"$d\" ] && find \"$d\" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) 2>/dev/null | head -1 | grep -q . && echo \"$d\"; done" > "%OUT%" 2>nul
for /f "usebackq delims=" %%l in ("%OUT%") do set /a SHOWS+=1
>>"%LOG%" echo Show folders on the tablet: %SHOWS%
type "%OUT%" >>"%LOG%"
exit /b 0

rem Before each step: make sure the tablet is still connected AND still allows
rem this PC. Android can drop the USB link and ask "Allow USB debugging?" again;
rem without this every later step fails in silence.
:need_device
set "NTOLD="
:need_loop
set "ST=missing"
"%ADB%" devices > "%OUT%" 2>nul
for /f "usebackq skip=1 tokens=1,2" %%a in ("%OUT%") do if "%%a"=="%S%" set "ST=%%b"
if "%ST%"=="device" (
    if defined NTOLD (
        echo       Connected again. Carrying on.
        call :log "Tablet back"
    )
    exit /b 0
)
if not defined NTOLD (
    call :log "Tablet not ready: !ST!"
    echo.
    if "!ST!"=="unauthorized" (
        echo       The tablet is asking again whether to allow this PC.
        echo       Unlock it. On "Allow USB debugging?",
        echo       tick "Always allow from this computer" and tap Allow.
    ) else (
        echo       Lost the tablet. Check the cable is still plugged in and the
        echo       tablet's screen is unlocked.
    )
    echo       Waiting for it. Press Ctrl+C to give up.
    set "NTOLD=1"
)
timeout /t 2 /nobreak >nul
goto need_loop

:driver_hint
call :log "No tablet after 30 s"
echo.
echo       Still can't see a tablet. Check that:
echo         - USB debugging is on and the cable carries data, not just charge.
echo         - The tablet's screen is unlocked, and you tapped Allow on it.
echo         - Samsung tablets may need the Samsung USB driver:
echo           https://developer.samsung.com/android-usb-driver
echo       Still looking. Press Ctrl+C to give up.
echo.
exit /b 0

rem --- problems --------------------------------------------------------------
:no_adb
echo.
echo  Couldn't download the Android tools. Check the internet connection and try again.
call :log "FAILED: adb download"
goto fail
:no_apk
echo.
echo  Couldn't find the app. Put BackSeeTV-^<version^>.apk in the same folder as this file.
call :log "FAILED: no APK"
goto fail
:many_devices
echo.
echo  More than one Android device is plugged in. Unplug everything except the tablet
echo  you want to set up, then run this again.
call :log "FAILED: more than one device"
"%ADB%" devices >>"%LOG%" 2>&1
goto fail
:has_accounts
echo.
echo  This tablet still has an account on it [%ACCTS%], and Android won't allow a kiosk
echo  while one exists. Remove every account in Settings - Accounts, or factory reset
echo  and skip sign-in, then run this again.
call :log "FAILED: accounts on the tablet"
goto fail
:cancelled
echo.
echo  Cancelled. Nothing was changed.
call :log "Cancelled by the user"
goto fail
:install_failed
echo.
echo  The app didn't install. Android said:
type "%OUT%"
call :log "FAILED: install"
goto fail
:owner_failed
echo.
echo  The app installed, but Android refused to make it the device owner:
type "%OUT%"
echo.
echo  This usually means an account is still on the tablet. Remove it or factory reset,
echo  then run this again.
call :log "FAILED: set-device-owner"
goto fail
:fail
call :stop_adb
echo.
echo  A log of this run is in: %LOG%
echo  If you report a problem, attach that file.
echo.
del "%OUT%" >nul 2>&1
pause
exit
