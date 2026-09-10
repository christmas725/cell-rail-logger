#!/usr/bin/env python3
"""Very small helper for Cell Rail Logger v0.1 CSV files.

Usage:
    python tools/analyze_logs.py log1.csv log2.csv ...

It prints registered-cell transition sequences per file and simple pairwise overlap.
"""
from __future__ import annotations
import csv
import sys
from pathlib import Path


def load_sequence(path: Path):
    seq = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            if row.get("event") != "CELL" or row.get("registered") != "true":
                continue
            key = (
                row.get("rat", ""),
                row.get("mcc", ""),
                row.get("mnc", ""),
                row.get("tac_lac", ""),
                row.get("cell_id", ""),
                row.get("pci_psc", ""),
            )
            if not seq or seq[-1] != key:
                seq.append(key)
    return seq


def lcs_len(a, b):
    prev = [0] * (len(b) + 1)
    for x in a:
        cur = [0]
        for j, y in enumerate(b, start=1):
            if x == y:
                cur.append(prev[j - 1] + 1)
            else:
                cur.append(max(cur[-1], prev[j]))
        prev = cur
    return prev[-1]


def label(cell):
    rat, mcc, mnc, tac, cell_id, pci = cell
    return f"{rat}:{mcc}{mnc}:TAC{tac}:ID{cell_id}:PCI{pci}"


def main(argv):
    paths = [Path(p) for p in argv]
    if not paths:
        print("Usage: python tools/analyze_logs.py log1.csv log2.csv ...")
        return 2
    data = []
    for p in paths:
        seq = load_sequence(p)
        data.append((p, seq))
        print(f"\n[{p.name}] transitions={len(seq)}")
        for i, c in enumerate(seq, 1):
            print(f"{i:03d}  {label(c)}")
    if len(data) >= 2:
        print("\nPairwise sequence similarity (LCS / longer sequence)")
        for i in range(len(data)):
            for j in range(i + 1, len(data)):
                pa, a = data[i]
                pb, b = data[j]
                denom = max(len(a), len(b), 1)
                score = lcs_len(a, b) / denom
                print(f"{pa.name} vs {pb.name}: {score:.1%}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
