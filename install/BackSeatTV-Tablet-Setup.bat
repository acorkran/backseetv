@echo off
rem ============================================================================
rem  BackSeat TV - one-step tablet setup for Windows 10 / 11
rem
rem  Plug a freshly reset tablet in over USB and double-click this file. It gets
rem  Google's adb tools, finds the tablet, installs BackSeat TV and sets it up
rem  as a locked kiosk (device owner).
rem
rem  Put next to this file:
rem    BackSeatTV-VERSION.apk     the app (or set APK_URL below)
rem    Anancy\                    optional: your shows, one folder per show
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
title BackSeat TV - tablet setup

rem Where to download the app from when no APK sits next to this file.
set "APK_URL="

set "HERE=%~dp0"
set "BST_HOME=%LOCALAPPDATA%\BackSeatTV"
set "ADB=%BST_HOME%\platform-tools\adb.exe"
set "OUT=%TEMP%\backseat-setup-%RANDOM%.txt"
set "ADMIN=app.anancy/.admin.AdminReceiver"

echo.
echo  BackSeat TV - tablet setup
echo  --------------------------
echo  Before you start, on the tablet:
echo    1. Factory reset it, or use one with NO Google or Samsung account on it.
echo       During setup, skip every sign-in.
echo    2. Settings - About tablet - Software information - tap Build number 7 times.
echo    3. Settings - Developer options - turn on USB debugging.
echo    4. Plug it into this PC with a data USB cable.
echo.
pause

rem --- 1. adb --------------------------------------------------------------
echo.
echo [1/6] Getting the Android tools...
if exist "%ADB%" goto have_adb
if not exist "%BST_HOME%" mkdir "%BST_HOME%"
set "BST_ZIP=%BST_HOME%\platform-tools.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $env:BST_ZIP; Expand-Archive -Force -Path $env:BST_ZIP -DestinationPath $env:BST_HOME"
if errorlevel 1 goto no_adb
del "%BST_ZIP%" >nul 2>&1
if not exist "%ADB%" goto no_adb
:have_adb
echo       Ready.

rem --- 2. the app ----------------------------------------------------------
echo.
echo [2/6] Finding the app...
set "APK="
for %%F in ("%HERE%BackSeatTV*.apk") do (
    set "NAME=%%~nF"
    if /i "!NAME:wear=!"=="!NAME!" set "APK=%%~fF"
)
if defined APK goto have_apk
if not defined APK_URL goto no_apk
set "APK=%BST_HOME%\BackSeatTV.apk"
set "BST_APK=%APK%"
set "BST_URL=%APK_URL%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing -Uri $env:BST_URL -OutFile $env:BST_APK"
if errorlevel 1 goto no_apk
:have_apk
echo       Using %APK%

rem --- 3. find exactly one tablet -------------------------------------------
echo.
echo [3/6] Looking for the tablet...
"%ADB%" start-server >nul 2>&1
set /a WAITED=0
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
    echo       Look at the tablet and tap Allow on the USB debugging prompt.
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
echo       Found: %MODEL%  (%S%)

rem --- 4. is it safe to make this tablet a kiosk? ----------------------------
echo.
echo [4/6] Checking the tablet...
set "ALREADY="
%A% shell dumpsys device_policy > "%OUT%" 2>&1
findstr /c:"admin=ComponentInfo{app.anancy" "%OUT%" >nul && set "ALREADY=1"
if defined ALREADY (
    echo       Already set up as a BackSeat TV kiosk. Updating it.
    goto confirmed
)
set "ACCTS="
%A% shell dumpsys account > "%OUT%" 2>&1
for /f "tokens=2" %%n in ('findstr /c:"Accounts:" "%OUT%"') do if not defined ACCTS set "ACCTS=%%n"
if defined ACCTS if not "%ACCTS%"=="0" goto has_accounts
echo.
echo  This will turn %MODEL% into a locked BackSeat TV kiosk.
echo  You can undo it later in the app: Settings - Remove device owner.
echo.
set "OK="
set /p "OK=Type Y and press Enter to continue: "
if /i not "%OK%"=="Y" goto cancelled
:confirmed

rem --- 5. install and take ownership -----------------------------------------
echo.
echo [5/6] Installing BackSeat TV (this can take a minute)...
%A% install -r "%APK%" > "%OUT%" 2>&1
findstr /c:"Success" "%OUT%" >nul || goto install_failed
if defined ALREADY goto owned
%A% shell dpm set-device-owner %ADMIN% > "%OUT%" 2>&1
findstr /c:"Success" "%OUT%" >nul || goto owner_failed
:owned
%A% shell appops set app.anancy MANAGE_EXTERNAL_STORAGE allow >nul 2>&1
rem Granted up front so nothing waits for a tap on the tablet. Permissions an older Android lacks just fail.
for %%P in (BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE ACCESS_FINE_LOCATION POST_NOTIFICATIONS) do (
    %A% shell pm grant app.anancy android.permission.%%P >nul 2>&1
)
%A% shell cmd bluetooth_manager enable >nul 2>&1
echo       Done.

rem --- 6. shows, lock, start -------------------------------------------------
echo.
echo [6/6] Finishing up...
if exist "%HERE%Anancy\" (
    echo       Copying your shows to the tablet. Big libraries take a while...
    %A% push "%HERE%Anancy" /sdcard/ || echo       Some files did not copy. Run this again to retry.
)
%A% shell settings put global anancy_kiosk 1 >nul 2>&1
%A% shell am start -n app.anancy/.AdbSetRole --es role player >nul 2>&1

del "%OUT%" >nul 2>&1
echo.
echo  All done. The tablet is now a BackSeat TV.
echo    - Grown-up menu: tap with four fingers, four times, within 3 seconds.
echo    - On your phone, install the app and choose "This is a Remote".
echo    - You can unplug the tablet.
echo.
pause
exit /b 0

rem --- problems --------------------------------------------------------------
:driver_hint
echo.
echo       Still can't see a tablet. Check that:
echo         - USB debugging is on and the cable carries data, not just charge.
echo         - Samsung tablets may need the Samsung USB driver:
echo           https://developer.samsung.com/android-usb-driver
echo       Still looking. Press Ctrl+C to give up.
echo.
exit /b 0

:no_adb
echo.
echo  Couldn't download the Android tools. Check the internet connection and try again.
goto fail
:no_apk
echo.
echo  Couldn't find the app. Put BackSeatTV-^<version^>.apk in the same folder as this file.
goto fail
:many_devices
echo.
echo  More than one Android device is plugged in. Unplug everything except the tablet
echo  you want to set up, then run this again.
goto fail
:has_accounts
echo.
echo  This tablet still has an account on it [%ACCTS%], and Android won't allow a kiosk
echo  while one exists. Remove every account in Settings - Accounts, or factory reset
echo  and skip sign-in, then run this again.
goto fail
:cancelled
echo.
echo  Cancelled. Nothing was changed.
goto fail
:install_failed
echo.
echo  The app didn't install. Android said:
type "%OUT%"
goto fail
:owner_failed
echo.
echo  The app installed, but Android refused to make it the device owner:
type "%OUT%"
echo.
echo  This usually means an account is still on the tablet. Remove it or factory reset,
echo  then run this again.
goto fail
:fail
echo.
del "%OUT%" >nul 2>&1
pause
exit /b 1
