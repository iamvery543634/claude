# Ghost Hub → GitHub: everything you need

You're a cloud Claude Code session. You can see this GitHub repo, but not my Windows PC, where my Android apps are
built and signed. The last chat (running on my PC) made this bundle for you.
`ghost-hub-handoff.zip` has the full source of **Ghost Hub** (my app store on my phone) and its PC publishing
scripts. My signing keys and passwords were deliberately left out.

**Goal:** make Ghost Hub get its apps from GitHub, so it works anywhere (mobile data, school, a friend's Wi-Fi) and
not only on my home Wi-Fi from my PC. Keep the PC-on-Wi-Fi way as a fallback.

---

## 1. How to work with me

- I send short messages and read along on my phone. Keep answers short and plain.
- Don't type my passwords, create accounts or sign in for me. When something needs my GitHub account (making a repo,
  a token, settings), tell me exactly what to click and I'll do it.
- Ask me before anything goes public: making a repo public, publishing a release, and so on.
- None of my apps has been tested on a real phone. Don't claim something works on a device.
- Hard rules (they apply to any future work too):
  - Never build a Sparx homework auto-completer.
  - Never build a packet, traffic or HTTP sniffer.
  - Never create a new release signing key for an existing app (see §3).
- First, ask me for my GitHub username and what to call the repos (suggestions in §5).

## 2. First steps in this repo

1. Unzip `ghost-hub-handoff.zip` into the repo root. You'll get `GhostHub/` (the Android app),
   `GhostHubServer/` (the PC scripts) and a `.gitignore`.
2. Keep that `.gitignore`. It blocks `*.jks`, `keystore.properties`, `local.properties`, build output and APKs,
   which must never be committed.
3. Commit the source. This code repo should be **private**. Once the files are committed, the zip can be deleted
   from the repo.

## 3. Things only my PC can do (important)

- **Release signing keys stay on my PC:** `GhostHub/ghosthub-release.jks` + `GhostHub/keystore.properties`, and one
  per app. Android only installs an update if it's signed with the same key. So you **can't make a release APK my
  phone will accept**, and you must not create a new keystore to try.
- You *can* try a compile check with a **debug** build if you can install the Android SDK in this environment
  (JDK 17, compileSdk 35, build-tools 35.0.0, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1 via the wrapper). If that's
  not possible, review the code very carefully instead and tell me it wasn't compiled.
- The real release build, signing and first upload happen on my PC afterwards (§7).

My PC setup, for reference: Windows 11, Git Bash, toolchain in `C:\Users\iamve\AndroidDev` (jdk17, sdk, gradle).
Each app is its own folder there with a `build.sh` that runs `./gradlew assembleRelease`, copies the APK to
`dist/<Name>.apk`, then runs `python C:/Users/iamve/AndroidDev/GhostHubServer/publish.py`. Python 3.11 is installed.
The bundled `GhostHub/` and `GhostHubServer/` are copies of `C:\Users\iamve\AndroidDev\GhostHub` and
`C:\Users\iamve\AndroidDev\GhostHubServer`.

## 4. How Ghost Hub works today

### The apps it serves (package | Hub name | icon key | APK on my PC, under C:\Users\iamve\AndroidDev)

| package | name | icon | APK |
|---|---|---|---|
| com.ghost.hub | Ghost Hub | hub | GhostHub/dist/GhostHub.apk (v2.0, code 6) |
| com.mirage.gps | Ghost GPS | ghost | MirageGPS/dist/Ghost.apk (v2.0, code 6) |
| com.ghost.volume | Ghost Volume | volume | GhostVolume/dist/GhostVolume.apk (v2.0, code 5) |
| com.ghost.terminal | Ghost Terminal | terminal | GhostTerminal/dist/GhostTerminal.apk |
| com.ghost.weather | Ghost Weather | weather | GhostWeather/dist/GhostWeather.apk |
| com.ghost.school | Ghost School | school | GhostSchool/dist/GhostSchool.apk |
| com.ghost.editor | Ghost Image Editor | editor | GhostEditor/dist/GhostEditor.apk |
| com.ghost.vpn | Ghost VPN | vpn | GhostVPN/dist/GhostVPN.apk |
| com.ghost.browser | Ghost Browser | browser | GhostBrowser/dist/GhostBrowser.apk |
| com.ghost.video | Ghost Video | video | GhostVideo/dist/GhostVideo.apk |

### PC side (`GhostHubServer/`)

- **`apps_meta.json`** lists every app and its "what's new" notes:
  `{"server_name": "Ghost Hub", "apps": [{package, name, icon, apk, description, changes[]}]}`
- **`publish.py`** runs after every app build. For each app it:
  1. reads the version with `aapt2 dump badging` and works out the sha256
  2. copies the APK to `www/apps/<pkg>-<versionCode>-<sha10>.apk`, keeping the last 3 per app plus a plain
     `<pkg>.apk` alias
  3. writes `www/index.json`
- **`www/index.json`** is included as a real example. Its format:
  `{"server": "Ghost Hub", "updated": <unix>, "apps": [{package, name, icon, description, changes[], versionCode, versionName, size, sha256, file: "apps/<pkg>-<code>-<sha10>.apk", published}]}`
- **`ghost_hub_server.py`** serves `www/` over HTTP on port 8765. It answers UDP discovery on 8766: the phone sends
  `GHOSTHUB_DISCOVER` and gets back `GHOSTHUB 8765 <hostname>`. It starts with Windows, and a firewall .bat opens
  the ports.

### Phone app (`GhostHub/`, package com.ghost.hub, v2.0 / versionCode 6)

- Kotlin with the UI built in code, minSdk 26 / targetSdk 35.
- **`Repo.kt`:**
  - `server` is the base URL, saved in prefs "hub".
  - `discover()` finds the PC with a UDP broadcast.
  - `load()` pings the server, then GETs `$base/index.json` and compares it with the installed versions.
  - `fresh(pkg)` re-reads the index right before every download.
  - `urlFor(entry) = "$server/${entry.file}"`.
- **`Installer.kt`:**
  - downloads with `HttpURLConnection` and checks the sha256
  - on a mismatch it throws `CorruptDownload`, and MainActivity calls `fresh()` and retries once
  - then hands the file to Android's installer through FileProvider `com.ghost.hub.files`
- **`UpdateReceiver.kt`** checks about twice a day with AlarmManager and shows a notification.
- **`MainActivity.kt`** is the UI (app cards, Update all, details sheet, settings). **`Fx.kt`** has the effects
  (GhostField, ProgressRing, shimmer).
- **The manifest** has `usesCleartextTraffic="true"` (needed for the PC) and a `<queries>` entry for every Ghost
  package. Without it the Hub can't tell whether an app is installed.
- **Old bug, don't bring it back:** "download corrupted" happened when `index.json` and the APK file got out of sync.
  It was fixed with per-build file names, `fresh()` before each download and one retry. **Old builds must stay
  downloadable for a while after a new one is published.**
- **Adding an app to the Hub** takes four steps:
  1. an `apps_meta.json` entry
  2. copy its logo to `res/drawable/app_<icon>.xml`
  3. an `iconFor` case in MainActivity
  4. a `<queries>` package entry
- **Look and feel:**
  - Dark palette: bg `#0F1116`, card `#1D2129`, accent cyan `#72E2F7`, text `#EEF1F5`.
  - Rounded cards and a cute ghost logo, matching my other Ghost apps. Keep that style.

## 5. What I want built

**Two repos:**
- The **code repo** (this one): **private**, holding the source.
- A **builds repo**, e.g. `ghost-hub-builds`: **public**, holding only `index.json` plus GitHub Releases with the
  APKs.
  - My phone can't read a private repo without a token built into the app, which isn't safe, so the builds repo
    must be public.
  - Tell me plainly that anyone with the link could download my APKs (but not my source or keys), and get my OK first.

1. **`GhostHubServer/publish_github.py`** (runs on my PC; Python standard library only, no pip installs):
   - For each app whose versionCode isn't in the GitHub index yet:
     - create a release in the builds repo (tag like `com.ghost.volume-v5`, title "Ghost Volume 2.0", body from
       `changes`)
     - upload the APK as an asset
   - Then update `index.json` in the builds repo through the GitHub REST API:
     - same fields as now
     - `file` = the asset's full `browser_download_url`
     - keep `sha256` and `size`
   - Keep old releases, so an older cached index still downloads fine (`raw.githubusercontent.com` can cache for
     about 5 minutes).
   - Owner and repo names go in a small non-secret `github.json` next to the script.
   - The token is read from env var `GHOSTHUB_GITHUB_TOKEN` or from `%USERPROFILE%\.ghosthub\github_token`, and must
     **never** go into any repo or the APK. It's a fine-grained token with Contents read/write on the builds repo only.
   - Make `publish.py` call it at the end when a token exists. Then all 10 apps' `build.sh` files publish to GitHub
     with no changes, and local `www/` publishing keeps working.
   - Clear messages if the token is missing or wrong. Never print the token.
2. **Ghost Hub 2.1** (versionCode 7, versionName "2.1"):
   - Loads from `https://raw.githubusercontent.com/<owner>/<builds-repo>/main/index.json` by default, so it works
     anywhere.
   - Falls back to the PC on Wi-Fi (the current discovery code) when GitHub can't be reached. A settings choice would
     be nice: Auto / GitHub only / PC only.
   - `urlFor` must handle full `https://` URLs in `file`, and relative ones for the PC.
   - Follows GitHub's release-download redirects (https → https).
   - Longer timeouts for mobile data, and friendly errors ("No internet", "GitHub didn't answer").
   - Keep the sha256 check, `fresh()` and the retry.
   - Show where the list came from (GitHub or "PC (Wi-Fi)").
   - Update the Ghost Hub entry's `changes` in `apps_meta.json` to describe 2.1.
3. **Docs:** update `GhostHubServer/README.txt`. Write `PC_STEPS.md` (see §7).

Keep the code style: the same comment density and naming as the existing files.

## 6. Hand-back

- Commit everything to this private repo, with a clear summary.
- I'll get the changed files onto my PC afterwards (probably with a Claude chat running on my PC), so list exactly
  which files changed.

## 7. `PC_STEPS.md`, the steps for my PC (write this file for me)

Short numbered steps I, or a Claude chat on my PC, can follow:

1. Copy the updated `GhostHub/` and `GhostHubServer/` files into `C:\Users\iamve\AndroidDev\...`.
   **Don't overwrite or delete** `ghosthub-release.jks`, `keystore.properties` or `local.properties`.
2. Make the public builds repo on github.com.
3. Create the fine-grained token and save it to `%USERPROFILE%\.ghosthub\github_token`.
4. Fill in `github.json`.
5. In Git Bash, run `cd /c/Users/iamve/AndroidDev/GhostHub && ./build.sh`. That builds and signs Hub 2.1, publishes
   it to the PC Hub, and, with the token set, to GitHub.
6. Run `python publish_github.py` once in GhostHubServer to upload all the other current apps.
7. On the phone: open the current Ghost Hub **at home on Wi-Fi** and update it to 2.1. The first 2.1 can only come
   from the PC, because the old Hub doesn't know about GitHub. After that it works anywhere.
