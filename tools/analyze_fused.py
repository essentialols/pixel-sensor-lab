#!/usr/bin/env python3
"""Analyze fused spectral + ToF captures from ripeness_daemon JSONL output."""
import json
import sys
import math

def load_jsonl(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return rows

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <fused_capture.jsonl>")
        sys.exit(1)

    rows = load_jsonl(sys.argv[1])
    print(f"Loaded {len(rows)} samples from {sys.argv[1]}")

    spectral_rows = [r for r in rows if 'raw' in r]
    tof_rows = [r for r in rows if 'tof' in r and r['tof'].get('photons', 0) > 0
                and r['tof'].get('photons', 0) < 100000000]

    print(f"  With spectral: {len(spectral_rows)}")
    print(f"  With valid ToF: {len(tof_rows)}")

    if spectral_rows:
        print("\n--- Spectral ---")
        channels = ['R', 'G', 'B', 'IR', 'CLR1', 'CLR2']
        print(f"{'Channel':<8} {'Mean':>12} {'StdDev':>10}")
        for ch in channels:
            vals = [r['raw'][ch] for r in spectral_rows]
            mean = sum(vals) / len(vals)
            std = math.sqrt(sum((v - mean)**2 for v in vals) / len(vals))
            print(f"{ch:<8} {mean:>12.0f} {std:>10.0f}")

        print("\n--- Spectral Indices ---")
        for key in ['NDVI', 'RG', 'BG', 'NIR_VIS', 'CLR', 'CI']:
            vals = [r['idx'][key] for r in spectral_rows]
            mean = sum(vals) / len(vals)
            print(f"  {key:<10} {mean:.4f}")

    if tof_rows:
        print("\n--- ToF (940nm) ---")
        photon_vals = [r['tof']['photons'] for r in tof_rows]
        dist_vals = [r['tof']['dist_mm'] for r in tof_rows if r['tof']['dist_mm'] > 0]

        p_mean = sum(photon_vals) / len(photon_vals)
        print(f"  Total photons: mean={p_mean:.0f}")

        if dist_vals:
            d_mean = sum(dist_vals) / len(dist_vals)
            d_std = math.sqrt(sum((v - d_mean)**2 for v in dist_vals) / len(dist_vals))
            print(f"  Distance: mean={d_mean:.1f}mm, std={d_std:.1f}mm")

        # Histogram analysis: find peak bin and compute centroid
        peak_bins = []
        centroids = []
        for r in tof_rows:
            bins = r['tof']['bins']
            total = sum(bins)
            if total == 0:
                continue
            peak = max(range(len(bins)), key=lambda i: bins[i])
            peak_bins.append(peak)
            centroid = sum(i * bins[i] for i in range(len(bins))) / total
            centroids.append(centroid)

        if peak_bins:
            print(f"  Peak bin: mean={sum(peak_bins)/len(peak_bins):.1f}")
            print(f"  Centroid: mean={sum(centroids)/len(centroids):.2f}")

    if spectral_rows and tof_rows:
        print("\n--- Fused Fruit Ripeness Features ---")
        # For samples that have both spectral and ToF
        fused = [r for r in rows if 'raw' in r and 'tof' in r
                 and r['tof'].get('photons', 0) > 0
                 and r['tof'].get('photons', 0) < 100000000]

        if fused:
            for r in fused[:5]:
                raw = r['raw']
                idx = r['idx']
                tof = r['tof']
                vis = raw['R'] + raw['G'] + raw['B']
                nir940 = tof['photons']
                dist = tof['dist_mm']
                print(f"  t={r['t']:.1f} NDVI={idx['NDVI']:.3f} R/G={idx['RG']:.3f} "
                      f"NIR/VIS={idx['NIR_VIS']:.3f} ToF_photons={nir940} dist={dist}mm")

if __name__ == '__main__':
    main()
