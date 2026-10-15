#!/usr/bin/env python3
"""Build the page two ways from page.html + engine.js:

- dist/serve-spin-meter.html: one self-contained file (the claude.ai artifact).
- dist/app/: the installable web app (GitHub Pages): the same page plus a manifest, a service
  worker (offline use, "Share → Spin Meter" from Photos) and icons.
"""
import hashlib
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "app")
read = lambda *p: open(os.path.join(*p), encoding="utf-8").read()

page = read(HERE, "page.html")
engine = read(HERE, "engine.js")
strip = read(HERE, "example_strip.b64").strip()
base = page.replace("/*ENGINE*/", engine).replace("__W2_STRIP__", strip)
assert "/*ENGINE*/" not in base and "__W2_STRIP__" not in base
# Version shown on the page: VERSION (bumped by hand) + a build id from the page's content, so
# the same source always gives the same build id (locally and in the Pages workflow).
version = read(HERE, "VERSION").strip()
build = hashlib.sha256((version + base + "".join(read(APP, n) for n in sorted(os.listdir(APP)) if n.endswith((".html", ".js", ".webmanifest")))).encode()).hexdigest()[:7]
base = base.replace("__APP_VERSION__", version).replace("__APP_BUILD__", build)
SLOTS = {"<!--APP_HEAD-->": "app_head.html", "<!--APP_INSTALL-->": "app_install.html",
         "<!--APP_SCRIPT-->": "app_script.html", "<!--APP_PICK_HINT-->": "app_pick_hint.html"}
for slot in SLOTS:
    assert base.count(slot) == 1, slot

dist = os.path.join(HERE, "dist")
os.makedirs(dist, exist_ok=True)

artifact = base
for slot in SLOTS:
    artifact = artifact.replace(slot, "")
path = os.path.join(dist, "serve-spin-meter.html")
open(path, "w", encoding="utf-8").write(artifact)
print(path, len(artifact), "bytes")

app = base
for slot, name in SLOTS.items():
    app = app.replace(slot, read(APP, name).strip())
app = app.replace("__APP_VERSION__", version).replace("__APP_BUILD__", build)
app = '<!doctype html>\n<html lang="en">\n' + app
out = os.path.join(dist, "app")
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)
open(os.path.join(out, "index.html"), "w", encoding="utf-8").write(app)
open(os.path.join(out, "sw.js"), "w", encoding="utf-8").write(read(APP, "sw.js").replace("__VERSION__", build))
# the open app polls this to notice a newer build
open(os.path.join(out, "version.json"), "w", encoding="utf-8").write(json.dumps({"version": version, "build": build}) + "\n")
for f in ["manifest.webmanifest", "icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png"]:
    shutil.copy(os.path.join(APP, f), out)
open(os.path.join(out, ".nojekyll"), "w").close()
print(out, "version", version, "build", build)
