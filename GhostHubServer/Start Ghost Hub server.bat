@echo off
rem Starts the Ghost Hub server in the background (no window). Safe to run twice: a second copy
rem can't take the same port and just exits.
start "" "C:\Users\iamve\AppData\Local\Programs\Python\Python311\pythonw.exe" "%~dp0ghost_hub_server.py"
