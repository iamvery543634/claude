@echo off
rem Lets your phone reach the Ghost Hub server on your home network.
rem RIGHT-CLICK this file and choose "Run as administrator" (you only do this once).

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Please right-click this file and choose "Run as administrator".
    echo.
    pause
    exit /b
)

netsh advfirewall firewall delete rule name="Ghost Hub" >nul 2>&1
netsh advfirewall firewall add rule name="Ghost Hub" dir=in action=allow protocol=TCP localport=8765 profile=any
netsh advfirewall firewall add rule name="Ghost Hub" dir=in action=allow protocol=UDP localport=8766 profile=any

echo.
echo Done. Your phone can now reach Ghost Hub on this Wi-Fi.
echo.
pause
