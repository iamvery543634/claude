Ghost Hub server (runs on this PC)
==================================

What it does
  Publishes the newest Ghost app APKs in two places:
    - GitHub (a public "builds" repo): the Ghost Hub app on your phone reads index.json there and
      downloads the APKs from GitHub Releases. Works anywhere: mobile data, school, a friend's Wi-Fi.
    - This PC over home Wi-Fi (the original way): the phone finds the PC automatically. Ghost Hub
      uses it as a fallback when GitHub can't be reached, or always if you pick "PC only" in its settings.
  Only the APKs go to GitHub. Source code and signing keys stay on this PC.

One-time setup (Wi-Fi part)
  1. Right-click "Allow Ghost Hub through firewall.bat" and choose "Run as administrator".
     (This lets your phone reach the PC. You only do it once.)
  2. It's set to start automatically when you log in to Windows. To start it now without
     rebooting, double-click "Start Ghost Hub server.bat".
  3. Make sure the phone and this PC are on the same Wi-Fi.

One-time setup (GitHub part)
  See PC_STEPS.md in the code repo: make the public builds repo, make a fine-grained token with
  Contents read/write on that repo only, save it to  %USERPROFILE%\.ghosthub\github_token  (or the env
  var GHOSTHUB_GITHUB_TOKEN), and check github.json. Never put the token in a repo or an APK.

How updates flow
  - Every time an app is built (build.sh), publish.py copies its new APK into www/ and updates
    www/index.json. If a GitHub token is set up it then runs publish_github.py, which uploads any
    build GitHub doesn't have yet as a Release (tag like com.ghost.volume-v5) and updates index.json
    in the builds repo. Without a token, only the Wi-Fi Hub is updated.
  - Old releases are kept on purpose: GitHub can serve a phone an index.json up to ~5 minutes old,
    and that index must still point at a file that exists.
  - The Ghost Hub app checks on open and a few times a day, and shows Update when a newer
    version is there. You tap Update, then confirm Android's install screen.

Files
  ghost_hub_server.py   The Wi-Fi server (HTTP on 8765, discovery on 8766).
  publish.py            Copies the newest APKs into www/, writes www/index.json, then calls publish_github.py.
  publish_github.py     Uploads new builds to GitHub Releases and updates index.json in the builds repo.
                        Run it by hand to (re)publish everything:  python publish_github.py
  github.json           Owner, repo and branch of the builds repo (not secret).
  apps_meta.json        The list of apps, their descriptions and where each APK is built.
  www/                  What the phone downloads over Wi-Fi (index.json + apps/*.apk).
  server.log            A short log of what connected.

To add a new Ghost app later: add it to apps_meta.json and run publish.py (or just build it).
The Hub app also needs an icon, an iconFor case and a <queries> entry for the new package.

To stop the Wi-Fi server starting with Windows: press Win+R, type  shell:startup  , delete the
"Ghost Hub server" shortcut there.
