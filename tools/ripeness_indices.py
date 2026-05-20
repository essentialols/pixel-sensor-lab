#!/usr/bin/env python3
"""Compute fruit ripeness spectral indices from VD6282 captures.

Based on literature review (LITERATURE_REVIEW.md). Indices are designed
to work with 6 broadband channels + optional ToF 940nm reflectance.

Usage:
    python3 ripeness_indices.py <spectral_capture.csv>
    python3 ripeness_indices.py <dir_of_csvs> --compare
"""
import csv
import sys
import os

# Channel mapping from spectral_reader output
CH = {'R': 'f2', 'G': 'f3', 'B': 'f4', 'IR': 'f5', 'CLR1': 'f6', 'CLR2': 'f7', 'GAIN': 'f8'}

def compute_indices(row):
    r = float(row[CH['R']])
    g = float(row[CH['G']])
    b = float(row[CH['B']])
    ir = float(row[CH['IR']])
    c1 = float(row[CH['CLR1']])
    c2 = float(row[CH['CLR2']])
    vis = r + g + b

    return {
        'NDVI': (ir - r) / (ir + r) if (ir + r) > 0 else 0,
        'RG_ratio': r / g if g > 0 else 0,
        'BG_ratio': b / g if g > 0 else 0,
        'NIR_VIS': ir / vis if vis > 0 else 0,
        'CLR_ratio': c1 / c2 if c2 > 0 else 0,
        'color_index': (r - b) / g if g > 0 else 0,
        'red_frac': r / vis if vis > 0 else 0,
        'green_frac': g / vis if vis > 0 else 0,
        'blue_frac': b / vis if vis > 0 else 0,
    }

def analyze_file(path):
    rows = []
    with open(path) as f:
        reader = csv.DictReader(f)
        if 'f2' not in (reader.fieldnames or []):
            return None
        for r in reader:
            rows.append(r)
    if not rows:
        return None

    all_indices = [compute_indices(r) for r in rows]
    means = {}
    for key in all_indices[0]:
        vals = [idx[key] for idx in all_indices]
        means[key] = sum(vals) / len(vals)
    return {'file': os.path.basename(path), 'n': len(rows), 'indices': means}

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <capture.csv> [--compare <dir>]")
        print("\nSpectral indices for fruit ripeness assessment:")
        print("  NDVI          (IR-R)/(IR+R)     Chlorophyll content")
        print("  RG_ratio      R/G               Chlorophyll breakdown")
        print("  BG_ratio      B/G               Carotenoid accumulation")
        print("  NIR_VIS       IR/(R+G+B)        Water/structure")
        print("  CLR_ratio     CLR1/CLR2         Broadband profile")
        print("  color_index   (R-B)/G           Overall maturity")
        sys.exit(1)

    if '--compare' in sys.argv:
        dirpath = sys.argv[1]
        files = sorted([os.path.join(dirpath, f) for f in os.listdir(dirpath) if f.endswith('.csv')])
        results = [r for r in (analyze_file(f) for f in files) if r is not None]

        header = f"{'File':<45} {'N':>4} {'NDVI':>8} {'R/G':>8} {'B/G':>8} {'NIR/VIS':>8} {'CLR':>8} {'ColIdx':>8}"
        print(header)
        print("-" * len(header))
        for r in results:
            idx = r['indices']
            print(f"{r['file']:<45} {r['n']:>4} {idx['NDVI']:>8.4f} {idx['RG_ratio']:>8.4f} "
                  f"{idx['BG_ratio']:>8.4f} {idx['NIR_VIS']:>8.4f} {idx['CLR_ratio']:>8.4f} "
                  f"{idx['color_index']:>8.4f}")
    else:
        result = analyze_file(sys.argv[1])
        if not result:
            print("No data found.")
            sys.exit(1)

        print(f"File: {result['file']} ({result['n']} samples)")
        print()
        print("Ripeness Spectral Indices:")
        print(f"  NDVI (chlorophyll):     {result['indices']['NDVI']:>8.4f}")
        print(f"  R/G ratio:              {result['indices']['RG_ratio']:>8.4f}")
        print(f"  B/G ratio:              {result['indices']['BG_ratio']:>8.4f}")
        print(f"  NIR/VIS ratio:          {result['indices']['NIR_VIS']:>8.4f}")
        print(f"  CLR1/CLR2 ratio:        {result['indices']['CLR_ratio']:>8.4f}")
        print(f"  Color index (R-B)/G:    {result['indices']['color_index']:>8.4f}")
        print()
        print("Visible light fractions:")
        print(f"  Red:   {result['indices']['red_frac']*100:.1f}%")
        print(f"  Green: {result['indices']['green_frac']*100:.1f}%")
        print(f"  Blue:  {result['indices']['blue_frac']*100:.1f}%")

if __name__ == '__main__':
    main()
