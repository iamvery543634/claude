# PC steps: Ghost Hub 2.1 + GitHub publishing

Do these on the Windows PC, in Git Bash. `AndroidDev` means `C:\Users\iamve\AndroidDev`.
A Claude chat running on the PC can follow them too.

## 1. Copy the new files into AndroidDev

Only these files changed. **Don't overwrite or delete** `GhostHub\ghosthub-release.jks`,
`GhostHub\keystore.properties` or `GhostHub\local.properties`: they exist only on the PC.

```bash
cd /c/Users/iamve/AndroidDev
git clone https://github.com/iamvery543634/claude.git ghost-hub-src   # private repo: sign in if asked
cp ghost-hub-src/GhostHub/app/build.gradle.kts GhostHub/app/
cp ghost-hub-src/GhostHub/app/src/main/java/com/ghost/hub/*.kt GhostHub/app/src/main/java/com/ghost/hub/
cp ghost-hub-src/GhostHubServer/{publish.py,publish_github.py,github.json,apps_meta.json,README.txt} GhostHubServer/
```

(Already cloned before? `cd ghost-hub-src && git pull` first, then the three `cp` lines.)

## 2. Make the public builds repo

1. github.com → **+** (top right) → **New repository**.
2. Repository name: **`Ghost-projects`** (exactly that; GitHub doesn't allow spaces).
3. Choose **Public**. Anyone with the link can download the APKs; your source and keys are not there.
4. Tick **Add a README file**.
5. **Create repository**.

## 3. Make the token and save it

1. github.com → your profile picture → **Settings** → **Developer settings** (bottom of the left list)
   → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. Token name: `Ghost Hub publish`. Expiration: pick 1 year (make a new one when it runs out).
3. Repository access: **Only select repositories** → choose **Ghost-projects**.
4. Permissions → Repository permissions → **Contents: Read and write**. (Metadata becomes Read-only by itself.)
5. **Generate token** and copy it. It's shown once.
6. Save it on the PC (paste the token between the quotes):

   ```bash
   mkdir -p ~/.ghosthub && echo "PASTE_TOKEN_HERE" > ~/.ghosthub/github_token
   ```

   That makes `C:\Users\iamve\.ghosthub\github_token`. Never put the token in a repo or the app.

## 4. Check github.json

`GhostHubServer\github.json` should say:

```json
{ "owner": "iamvery543634", "repo": "Ghost-projects", "branch": "main" }
```

Only change it if you named the repo differently.

## 5. Build Ghost Hub 2.1

```bash
cd /c/Users/iamve/AndroidDev/GhostHub && ./build.sh
```

That builds and signs Hub 2.1 (versionCode 7), publishes it to the Wi-Fi Hub, and, because the token is
set, to GitHub. The output should end with `GitHub: 1 new upload`.

## 6. Upload all the other apps once

```bash
cd /c/Users/iamve/AndroidDev/GhostHubServer && python publish_github.py
```

Uploads every app in `www/index.json` that GitHub doesn't have yet (one Release each; a few minutes the
first time). From now on every `build.sh` does this by itself.

Check: <https://github.com/iamvery543634/Ghost-projects/releases> should list 10 releases, and
<https://raw.githubusercontent.com/iamvery543634/Ghost-projects/main/index.json> should list the apps with
`https://github.com/.../releases/download/...` links.

## 7. Update the phone

1. **At home on Wi-Fi**, open the current Ghost Hub (2.0). It finds the PC and offers Ghost Hub 2.1.
   Update it. (The first 2.1 can only come from the PC: the old Hub doesn't know about GitHub.)
2. Open Ghost Hub 2.1. The pill under the title should say **Apps from GitHub**. Turn Wi-Fi off and pull
   down to refresh: it should still load. Settings (gear) lets you pick Auto / GitHub only / PC only.

## If something goes wrong

| Message | What to do |
|---|---|
| `No GitHub token` | Step 3. Check the file is `C:\Users\iamve\.ghosthub\github_token`. |
| `GitHub rejected the token` | Make a new token (step 3) and save it again. |
| `GitHub can't find iamvery543634/Ghost-projects` | Repo name vs `github.json`, and the token's Repository access must include the repo. |
| `The token isn't allowed to do that` | Token needs **Contents: Read and write**. |
| `... has no commits yet` | Add a README to the repo on github.com (step 2.4), then run again. |
| Phone: `GitHub has no app list at that address yet` | Step 6 hasn't run, or Settings › GitHub address is wrong (Reset puts the default back). |
| Phone: `No internet connection` / `GitHub didn't answer` | Connection problem. At home it falls back to the PC by itself. |

Only want the Wi-Fi Hub? Don't set a token: `publish.py` then works exactly as before.
