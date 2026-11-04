#!/usr/bin/env python3
"""Packages the web app for the Android updater and writes/checks its manifest.

  release.py pack OUT.zip
  release.py manifest --zip Z --build N --commit SHA --url URL --min-shell M --out manifest.json
  release.py verify manifest.json [--zip Z] [--field NAME]

The Android app reads releases/latest/download/manifest.json and accepts an update only if
the zip's size and SHA-256 match (android/.../Updater.java).
"""
import argparse, datetime, hashlib, json, os, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB_FILES = ["index.html", "app.js", "converter.js", "style.css"]
WEB_DIRS = ["icons"]
FIXED_TIME = (2020, 1, 1, 0, 0, 0)  # same input -> byte-identical zip


def web_files():
    out = list(WEB_FILES)
    for d in WEB_DIRS:
        for dirpath, _, names in os.walk(os.path.join(ROOT, d)):
            for n in names:
                out.append(os.path.relpath(os.path.join(dirpath, n), ROOT))
    return sorted(out)


def pack(out):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in web_files():
            info = zipfile.ZipInfo(rel.replace(os.sep, "/"), FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(os.path.join(ROOT, rel), "rb") as f:
                z.writestr(info, f.read())
    print(f"{out}: {len(web_files())} files, {os.path.getsize(out)} bytes")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def manifest(a):
    m = {
        "format": 1,
        "build": a.build,
        "commit": a.commit,
        "url": a.url,
        "sha256": sha256(a.zip),
        "size": os.path.getsize(a.zip),
        "min_shell": a.min_shell,
        "published_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    with open(a.out, "w") as f:
        json.dump(m, f, indent=1)
        f.write("\n")
    print(json.dumps(m, indent=1))


def verify(a):
    with open(a.manifest) as f:
        m = json.load(f)
    for k in ["format", "build", "url", "sha256", "size", "min_shell"]:
        if k not in m:
            sys.exit(f"manifest is missing {k}")
    if m["format"] != 1:
        sys.exit("unknown manifest format")
    if a.zip:
        if os.path.getsize(a.zip) != m["size"]:
            sys.exit("size mismatch")
        if sha256(a.zip) != m["sha256"]:
            sys.exit("sha256 mismatch")
        with zipfile.ZipFile(a.zip) as z:
            names = z.namelist()
            if "index.html" not in names:
                sys.exit("zip has no index.html")
            if any(n.startswith("/") or ".." in n.split("/") for n in names):
                sys.exit("zip has unsafe paths")
    if a.field:
        print(m[a.field])
    elif a.zip:
        print("ok")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("pack"); s.add_argument("out")
    s = sub.add_parser("manifest")
    s.add_argument("--zip", required=True); s.add_argument("--build", type=int, required=True)
    s.add_argument("--commit", required=True); s.add_argument("--url", required=True)
    s.add_argument("--min-shell", type=int, required=True); s.add_argument("--out", required=True)
    s = sub.add_parser("verify"); s.add_argument("manifest"); s.add_argument("--zip"); s.add_argument("--field")
    a = p.parse_args()
    {"pack": lambda: pack(a.out), "manifest": lambda: manifest(a), "verify": lambda: verify(a)}[a.cmd]()


if __name__ == "__main__":
    main()
