"""Regenerates res/values{,-zh}/strings.xml from tools/strings.tsv (name \t English \t 中文).

    python -X utf8 tools/gen_strings.py
"""
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(ROOT, "tools", "strings.tsv"), encoding="utf8") if l.strip()]


def esc(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return s.replace('"', '\\"') if '"' in s else s


for idx, folder in ((1, "values"), (2, "values-zh")):
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>", "    <!-- design `copy` (zh + en) and window / 3D HUD copy; generated from tools/strings.tsv (edit there) -->"]
    for r in rows:
        tr = ' translatable="false"' if r[0] == "lang_code" else ""
        out.append(f'    <string name="{r[0]}"{tr}>{esc(r[idx])}</string>')
    out.append("</resources>")
    open(os.path.join(ROOT, "app", "src", "main", "res", folder, "strings.xml"), "w", encoding="utf8").write("\n".join(out) + "\n")
print(len(rows), "strings")
