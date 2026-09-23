"""Copies the newest APK of every Ghost app into the Hub's web folder and writes index.json.

Run after any build (each project's build.sh calls it). Ghost Hub on the phone reads index.json.
"""
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
WWW = HERE / "www"
APPS = WWW / "apps"
AAPT2 = r"C:\Users\iamve\AndroidDev\sdk\build-tools\35.0.0\aapt2.exe"


def badging(apk: Path) -> dict:
    out = subprocess.run([AAPT2, "dump", "badging", str(apk)], capture_output=True, text=True,
                         timeout=60, errors="replace").stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not m:
        raise RuntimeError(f"couldn't read version from {apk.name}")
    return {"package": m.group(1), "versionCode": int(m.group(2)), "versionName": m.group(3)}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    meta = json.loads((HERE / "apps_meta.json").read_text(encoding="utf-8"))
    APPS.mkdir(parents=True, exist_ok=True)
    old = {}
    index_path = WWW / "index.json"
    if index_path.exists():
        try:
            old = {a["package"]: a for a in json.loads(index_path.read_text(encoding="utf-8"))["apps"]}
        except Exception:
            old = {}

    entries = []
    for app in meta["apps"]:
        src = Path(app["apk"])
        if not src.exists():
            # Not built yet: keep what's already published, if anything.
            if app["package"] in old:
                entries.append(old[app["package"]])
            print(f"  skip  {app['name']}: no APK yet")
            continue
        info = badging(src)
        if info["package"] != app["package"]:
            print(f"  WARN  {app['name']}: APK is {info['package']}, expected {app['package']}")
        prev = old.get(app["package"])
        digest = sha256(src)
        # Every build gets its own file name, so a phone holding a slightly older index.json still
        # downloads exactly the file that index describes (no "corrupted download" mid-publish).
        dest = APPS / f"{app['package']}-{info['versionCode']}-{digest[:10]}.apk"
        if not dest.exists():
            tmp = dest.with_suffix(".apk.part")
            shutil.copyfile(src, tmp)
            tmp.replace(dest)
        # Keep the three newest builds of each app; older ones go (skipping any still being downloaded).
        builds = sorted(APPS.glob(f"{app['package']}-*.apk"), key=lambda f: f.stat().st_mtime, reverse=True)
        for f in builds[3:]:
            try:
                f.unlink()
            except OSError:
                pass
        # Plain-named copy for older Ghost Hub versions.
        alias = APPS / f"{app['package']}.apk"
        try:
            if not alias.exists() or sha256(alias) != digest:
                tmp = alias.with_suffix(".apk.part")
                shutil.copyfile(src, tmp)
                tmp.replace(alias)
        except OSError:
            pass
        published = prev["published"] if prev and prev.get("sha256") == digest else int(time.time())
        entries.append({
            "package": app["package"],
            "name": app["name"],
            "icon": app.get("icon", "ghost"),
            "description": app.get("description", ""),
            "changes": app.get("changes", []),
            "versionCode": info["versionCode"],
            "versionName": info["versionName"],
            "size": src.stat().st_size,
            "sha256": digest,
            "file": f"apps/{dest.name}",
            "published": published,
        })
        print(f"  ok    {app['name']} {info['versionName']} ({info['versionCode']})")

    index = {"server": meta.get("server_name", "Ghost Hub"), "updated": int(time.time()), "apps": entries}
    tmp = index_path.with_suffix(".json.part")
    tmp.write_text(json.dumps(index, indent=2), encoding="utf-8")
    tmp.replace(index_path)
    print(f"Published {len(entries)} apps to {WWW}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
