"""Publishes the Ghost apps to GitHub, so Ghost Hub can update from anywhere (not only home Wi-Fi).

Runs after publish.py (which calls it when a token is set up). For every app in www/index.json whose build
isn't on GitHub yet, it creates a Release in the builds repo, uploads the APK as an asset, and then updates
index.json in that repo. Old releases are kept, so a phone holding a slightly older index.json (GitHub's raw
files can be cached for about 5 minutes) still downloads exactly the file its index describes.

Needs: github.json next to this script (owner, repo, branch: not secret) and a fine-grained token with
Contents read/write on the builds repo, in the env var GHOSTHUB_GITHUB_TOKEN or the file
%USERPROFILE%\\.ghosthub\\github_token. The token is never printed and must never be committed.
Python standard library only.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
WWW = HERE / "www"
CONFIG = HERE / "github.json"
TOKEN_FILE = Path.home() / ".ghosthub" / "github_token"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


class PublishError(Exception):
    """Something the user has to fix; the message says what."""


def read_token() -> str | None:
    tok = os.environ.get("GHOSTHUB_GITHUB_TOKEN", "").strip()
    if not tok and TOKEN_FILE.exists():
        tok = TOKEN_FILE.read_text(encoding="utf-8").strip()
    return tok or None


def token_available() -> bool:
    return read_token() is not None


def load_config() -> dict:
    if not CONFIG.exists():
        raise PublishError(f"{CONFIG.name} is missing. It should hold the builds repo, e.g.\n"
                           '  {"owner": "iamvery543634", "repo": "Ghost-projects", "branch": "main"}')
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    for key in ("owner", "repo"):
        if not cfg.get(key):
            raise PublishError(f'{CONFIG.name} needs a "{key}" value.')
    cfg.setdefault("branch", "main")
    return cfg


class GitHub:
    """The few GitHub REST calls we need. Errors come back as PublishError with a plain explanation."""

    def __init__(self, token: str, owner: str, repo: str):
        self.token = token
        self.repo_path = f"/repos/{owner}/{repo}"
        self.owner, self.repo = owner, repo

    def call(self, method: str, url: str, body: bytes | None = None, content_type: str = "application/json",
             ok404: bool = False):
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "GhostHub-publish")
        if body is not None:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
                return json.loads(data) if data else {}
        except urllib.error.HTTPError as e:
            if e.code == 404 and ok404:
                return None
            raise PublishError(self.explain(e)) from None
        except urllib.error.URLError as e:
            raise PublishError(f"Couldn't reach GitHub ({e.reason}). Is the internet connection working?") from None

    def explain(self, e: urllib.error.HTTPError) -> str:
        try:
            detail = json.loads(e.read().decode("utf-8", "replace")).get("message", "")
        except Exception:
            detail = ""
        repo = f"{self.owner}/{self.repo}"
        if e.code == 401:
            return ("GitHub rejected the token (wrong, expired or pasted with extra characters). Make a new "
                    f"fine-grained token with Contents read/write on {repo} and save it to {TOKEN_FILE}.")
        if e.code == 403:
            return (f"The token isn't allowed to do that on {repo}: it needs Contents read and write, and the "
                    f"repo must be selected under Repository access. GitHub said: {detail}")
        if e.code == 404:
            return (f"GitHub can't find {repo}. Check owner/repo in {CONFIG.name}, that the repo exists, and "
                    "that the token's Repository access includes it.")
        if e.code == 422 and "empty" in detail.lower():
            return (f"{repo} has no commits yet. On github.com, add a README to it (or run this again: the "
                    "script creates index.json first), then retry.")
        return f"GitHub answered HTTP {e.code}: {detail or e.reason}"

    def get_file(self, path: str, branch: str):
        """Returns (parsed JSON, blob sha) for a file in the repo, or (None, None) if it doesn't exist."""
        q = urllib.parse.quote(path)
        info = self.call("GET", f"{API}{self.repo_path}/contents/{q}?ref={urllib.parse.quote(branch)}", ok404=True)
        if not info:
            return None, None
        raw = base64.b64decode(info["content"])
        return json.loads(raw.decode("utf-8")), info["sha"]

    def put_file(self, path: str, branch: str, content: bytes, message: str, sha: str | None) -> str:
        body = {"message": message, "content": base64.b64encode(content).decode("ascii"), "branch": branch}
        if sha:
            body["sha"] = sha
        q = urllib.parse.quote(path)
        r = self.call("PUT", f"{API}{self.repo_path}/contents/{q}", json.dumps(body).encode("utf-8"))
        return r["content"]["sha"]

    def release_by_tag(self, tag: str):
        return self.call("GET", f"{API}{self.repo_path}/releases/tags/{urllib.parse.quote(tag)}", ok404=True)

    def create_release(self, tag: str, name: str, body: str) -> dict:
        data = {"tag_name": tag, "name": name, "body": body, "draft": False, "prerelease": False}
        return self.call("POST", f"{API}{self.repo_path}/releases", json.dumps(data).encode("utf-8"))

    def upload_asset(self, release: dict, path: Path) -> dict:
        url = f"{UPLOADS}{self.repo_path}/releases/{release['id']}/assets?name={urllib.parse.quote(path.name)}"
        return self.call("POST", url, path.read_bytes(), content_type="application/vnd.android.package-archive")


def release_body(entry: dict) -> str:
    lines = [entry.get("description", "").strip(), ""]
    lines += [f"- {c}" for c in entry.get("changes", [])]
    return "\n".join(lines).strip() + "\n"


def publish(gh: GitHub, branch: str, local: dict) -> int:
    """Uploads what's new and rewrites index.json in the builds repo. Returns the number of uploads."""
    remote, index_sha = gh.get_file("index.json", branch)
    if remote is None:
        # First run (maybe an empty repo): create the index first, so the repo has a commit for releases to hang off.
        print("  new   index.json (first publish)")
        empty = {"server": local.get("server", "Ghost Hub"), "updated": int(time.time()), "apps": []}
        index_sha = gh.put_file("index.json", branch, json.dumps(empty, indent=2).encode("utf-8"),
                                "Create index.json", None)
        remote = empty
    remote_apps = {a["package"]: a for a in remote.get("apps", [])}

    uploads = 0
    for entry in local["apps"]:
        pkg, code = entry["package"], entry["versionCode"]
        prev = remote_apps.get(pkg)
        if prev and prev.get("versionCode") == code and prev.get("sha256") == entry.get("sha256"):
            print(f"  ok    {entry['name']} {entry['versionName']} ({code}) already on GitHub")
            continue
        apk = WWW / entry["file"]
        if not apk.exists():
            print(f"  skip  {entry['name']}: {apk.name} isn't in www/apps (run publish.py first)")
            if prev:
                remote_apps[pkg] = prev
            continue
        tag = f"{pkg}-v{code}"
        release = gh.release_by_tag(tag)
        if release is None:
            release = gh.create_release(tag, f"{entry['name']} {entry['versionName']}", release_body(entry))
            print(f"  new   release {tag}")
        # A rebuild with the same versionCode has a different sha, so a different file name: it becomes a
        # second asset on the same release and the index points at it. Retries after a failed upload land here too.
        asset = next((a for a in release.get("assets", []) if a["name"] == apk.name), None)
        if asset is None:
            print(f"  up    {apk.name} ({apk.stat().st_size // 1024} KB)...", flush=True)
            asset = gh.upload_asset(release, apk)
            uploads += 1
        else:
            print(f"  ok    {apk.name} already uploaded")
        new = dict(entry)
        new["file"] = asset["browser_download_url"]
        remote_apps[pkg] = new

    # Keep the app order from the local index (apps_meta.json order), then anything only GitHub knows.
    order = [a["package"] for a in local["apps"]]
    apps = [remote_apps[p] for p in order if p in remote_apps]
    apps += [a for p, a in remote_apps.items() if p not in order]
    index = {"server": local.get("server", "Ghost Hub"), "updated": int(time.time()), "apps": apps}
    if apps == remote.get("apps", []):
        print("  ok    index.json on GitHub is already up to date")
        return uploads
    content = json.dumps(index, indent=2).encode("utf-8")
    old = {a["package"]: a for a in remote.get("apps", [])}
    changed = [f"{a['name']} {a['versionName']}" for a in apps if old.get(a["package"]) != a]
    message = "Update index.json: " + ", ".join(changed) if changed else "Update index.json"
    try:
        gh.put_file("index.json", branch, content, message, index_sha)
    except PublishError as e:
        # Someone (another publish?) changed the file since we read it: read again and retry once.
        if "409" not in str(e) and "422" not in str(e):
            raise
        _, index_sha = gh.get_file("index.json", branch)
        gh.put_file("index.json", branch, content, message, index_sha)
    print(f"  ok    index.json updated ({len(apps)} apps)")
    return uploads


def main() -> int:
    try:
        token = read_token()
        if not token:
            raise PublishError("No GitHub token. Put a fine-grained token (Contents read/write on the builds repo) "
                               f"in {TOKEN_FILE} or the env var GHOSTHUB_GITHUB_TOKEN. See PC_STEPS.md.")
        cfg = load_config()
        index_path = WWW / "index.json"
        if not index_path.exists():
            raise PublishError(f"{index_path} doesn't exist yet: run publish.py (or build an app) first.")
        local = json.loads(index_path.read_text(encoding="utf-8"))
        print(f"Publishing to github.com/{cfg['owner']}/{cfg['repo']} ({cfg['branch']})")
        gh = GitHub(token, cfg["owner"], cfg["repo"])
        n = publish(gh, cfg["branch"], local)
        print(f"GitHub: {n} new upload{'s' if n != 1 else ''}. Phones read "
              f"https://raw.githubusercontent.com/{cfg['owner']}/{cfg['repo']}/{cfg['branch']}/index.json")
        return 0
    except PublishError as e:
        print(f"GitHub publish failed: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
