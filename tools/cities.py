"""Builds app/src/main/assets/cities.tsv from GeoNames cities15000 (CC BY 4.0, geonames.org).

    curl -LO https://download.geonames.org/export/dump/cities15000.zip && unzip cities15000.zip
    python tools/cities.py cities15000.txt
"""
import sys
from pathlib import Path

# Sections of a city (Tokyo's wards), historical, abandoned and destroyed places.
SKIP_FEATURES = {"PPLX", "PPLH", "PPLQ", "PPLW"}

out = Path(__file__).resolve().parent.parent / "app/src/main/assets/cities.tsv"
rows = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        f = line.rstrip("\n").split("\t")
        if f[7] in SKIP_FEATURES or not f[2]:
            continue
        rows.append(f"{f[2]}\t{float(f[4]):.4f}\t{float(f[5]):.4f}\t{f[14]}")
out.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
print(f"{len(rows)} cities -> {out}")
