Ghost Hub server (runs on this PC)
==================================

What it does
  Shares the newest Ghost app APKs with your phone over your home Wi-Fi. The Ghost Hub app on
  your phone finds this PC automatically and downloads/updates the apps. Nothing is put on the
  internet; only devices on your Wi-Fi can reach it.

One-time setup
  1. Right-click "Allow Ghost Hub through firewall.bat" and choose "Run as administrator".
     (This lets your phone reach the PC. You only do it once.)
  2. It's set to start automatically when you log in to Windows. To start it now without
     rebooting, double-click "Start Ghost Hub server.bat".
  3. Make sure the phone and this PC are on the same Wi-Fi.

How updates flow
  - Every time an app is built (build.sh), it copies its new APK here and updates index.json.
  - The Ghost Hub app checks on open and a few times a day, and shows Update when a newer
    version is here. You tap Update, then confirm Android's install screen.

Files
  ghost_hub_server.py   The server (HTTP on 8765, discovery on 8766).
  publish.py            Copies the newest APKs into www/ and writes www/index.json.
  apps_meta.json        The list of apps, their descriptions and where each APK is built.
  www/                  What the phone downloads (index.json + apps/*.apk).
  server.log            A short log of what connected.

To add a new Ghost app later: add it to apps_meta.json and run publish.py (or just build it).

To stop it starting with Windows: press Win+R, type  shell:startup  , delete the
"Ghost Hub server" shortcut there.
