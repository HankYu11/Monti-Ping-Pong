#!/usr/bin/env python3
"""Inline engine.js (and the example filmstrip) into page.html -> dist/serve-spin-meter.html."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
page = open(os.path.join(HERE, "page.html"), encoding="utf-8").read()
engine = open(os.path.join(HERE, "engine.js"), encoding="utf-8").read()
strip = open(os.path.join(HERE, "example_strip.b64"), encoding="utf-8").read().strip()
out = page.replace("/*ENGINE*/", engine).replace("__W2_STRIP__", strip)
assert "/*ENGINE*/" not in out and "__W2_STRIP__" not in out
os.makedirs(os.path.join(HERE, "dist"), exist_ok=True)
path = os.path.join(HERE, "dist", "serve-spin-meter.html")
open(path, "w", encoding="utf-8").write(out)
print(path, len(out), "bytes")
