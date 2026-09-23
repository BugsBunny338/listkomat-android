#!/usr/bin/env python3
"""Google Play release tooling for Lístkomat — the Android sibling of the iOS
scripts/asc_*.py. One command from a built AAB to a rolled-out track, with
release notes sourced from files (never typed into the console UI — house rule).

Auth: service account JSON at ~/.config/listkomat-play/service-account.json
(the only secret; stays local, never committed). The account holds per-app
release + store-presence permissions on Lístkomat only.

Usage:
  play_release.py status                      # tracks + releases overview
  play_release.py release <track> [--notes-file F] [--name N]
        # uploads app/build/outputs/bundle/release/app-release.aab to <track>
        # (internal|alpha|beta|production), notes default to
        # play/release-notes/<track>.txt if present
  play_release.py promote <from> <to>         # move the newest release across tracks

Run ./gradlew bundleRelease first; this script deliberately does not build.
"""
import json, os, sys, time, urllib.request, urllib.parse

import jwt  # PyJWT (+cryptography), same deps the iOS ASC scripts use

PACKAGE = "cz.flipcom.listkomat"
KEY_PATH = os.path.expanduser("~/.config/listkomat-play/service-account.json")
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD_BASE = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
AAB = os.path.join(os.path.dirname(__file__), os.pardir,
                   "app/build/outputs/bundle/release/app-release.aab")
TRACKS = ("internal", "alpha", "beta", "production")


def token() -> str:
    sa = json.load(open(KEY_PATH))
    now = int(time.time())
    assertion = jwt.encode({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": sa["token_uri"], "iat": now, "exp": now + 1800,
    }, sa["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion}).encode()
    return json.load(urllib.request.urlopen(
        urllib.request.Request(sa["token_uri"], data=body)))["access_token"]


def call(tok, method, url, data=None, content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {tok}", "Content-Type": content_type})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"ERROR {method} {url}\n{e.code}: {e.read().decode()[:500]}")


def notes_for(track: str, path: str | None):
    if path is None:
        default = os.path.join(os.path.dirname(__file__), os.pardir,
                               "play", "release-notes", f"{track}.txt")
        path = default if os.path.exists(default) else None
    if path is None:
        return None
    text = open(path).read().strip()
    return [{"language": "cs-CZ", "text": text}]


def cmd_status():
    tok = token()
    edit = call(tok, "POST", f"{BASE}/edits", b"{}")
    tracks = call(tok, "GET", f"{BASE}/edits/{edit['id']}/tracks")
    for t in tracks.get("tracks", []):
        rels = t.get("releases", [])
        info = ", ".join(
            f"{r.get('name')} [{r.get('status')}] vc={','.join(map(str, r.get('versionCodes', [])))}"
            for r in rels) or "empty"
        print(f"{t['track']:<12} {info}")
    call(tok, "DELETE", f"{BASE}/edits/{edit['id']}")


def cmd_release(track, notes_file=None, name=None):
    assert track in TRACKS, f"track must be one of {TRACKS}"
    aab = os.path.abspath(AAB)
    assert os.path.exists(aab), f"missing {aab} — run ./gradlew bundleRelease first"
    tok = token()
    edit = call(tok, "POST", f"{BASE}/edits", b"{}")
    eid = edit["id"]
    print(f"-> uploading {os.path.basename(aab)} ({os.path.getsize(aab)//1024} KB)")
    bundle = call(tok, "POST",
                  f"{UPLOAD_BASE}/edits/{eid}/bundles?uploadType=media",
                  open(aab, "rb").read(), content_type="application/octet-stream")
    vc = bundle["versionCode"]
    print(f"   versionCode {vc}")
    release = {"status": "completed", "versionCodes": [str(vc)]}
    if name:
        release["name"] = name
    notes = notes_for(track, notes_file)
    if notes:
        release["releaseNotes"] = notes
    call(tok, "PUT", f"{BASE}/edits/{eid}/tracks/{track}",
         json.dumps({"track": track, "releases": [release]}).encode())
    call(tok, "POST", f"{BASE}/edits/{eid}:commit")
    print(f"DONE: versionCode {vc} rolled out to '{track}'")


def cmd_promote(src, dst):
    assert src in TRACKS and dst in TRACKS
    tok = token()
    edit = call(tok, "POST", f"{BASE}/edits", b"{}")
    eid = edit["id"]
    tracks = {t["track"]: t for t in
              call(tok, "GET", f"{BASE}/edits/{eid}/tracks").get("tracks", [])}
    rels = tracks.get(src, {}).get("releases", [])
    assert rels, f"no release on '{src}'"
    release = rels[0]
    call(tok, "PUT", f"{BASE}/edits/{eid}/tracks/{dst}",
         json.dumps({"track": dst, "releases": [release]}).encode())
    call(tok, "POST", f"{BASE}/edits/{eid}:commit")
    print(f"DONE: {release.get('name')} promoted {src} -> {dst}")


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args or args[0] == "status":
        cmd_status()
    elif args[0] == "release":
        track = args[1]
        nf = args[args.index("--notes-file") + 1] if "--notes-file" in args else None
        nm = args[args.index("--name") + 1] if "--name" in args else None
        cmd_release(track, nf, nm)
    elif args[0] == "promote":
        cmd_promote(args[1], args[2])
    else:
        sys.exit(__doc__)
