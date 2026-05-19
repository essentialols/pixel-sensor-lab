#!/usr/bin/env python3
"""Analyze VD6282 spectral sensor captures from spectral_reader CSV output."""
import csv
import sys
import math

CHANNELS = {
    'f2': 'Red',
    'f3': 'Green',
    'f4': 'Blue',
    'f5': 'IR',
    'f6': 'CLR1',
    'f7': 'CLR2',
}

def load_csv(path):
    rows = []
    with open(path) as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)
    return rows

def compute_stats(vals):
    n = len(vals)
    if n == 0:
        return {}
    mean = sum(vals) / n
    variance = sum((v - mean)**2 for v in vals) / n
    std = math.sqrt(variance)
    cv = 100 * std / mean if mean > 0 else 0
    return {'mean': mean, 'std': std, 'cv': cv, 'min': min(vals), 'max': max(vals), 'n': n}

def allan_deviation(vals, tau_factors=None):
    if tau_factors is None:
        tau_factors = [1, 2, 4, 8, 16, 32, 64, 128]
    results = []
    for m in tau_factors:
        if m * 2 > len(vals):
            break
        clusters = [sum(vals[i:i+m]) / m for i in range(0, len(vals) - m + 1, m)]
        if len(clusters) < 2:
            break
        diffs = [(clusters[i+1] - clusters[i])**2 for i in range(len(clusters)-1)]
        adev = math.sqrt(sum(diffs) / (2 * len(diffs)))
        results.append((m, adev))
    return results

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <spectral_capture.csv>")
        sys.exit(1)

    rows = load_csv(sys.argv[1])
    print(f"Loaded {len(rows)} samples from {sys.argv[1]}")

    times = [float(r['wall_time']) for r in rows]
    if len(times) > 1:
        dts = [times[i+1] - times[i] for i in range(len(times)-1)]
        avg_dt = sum(dts) / len(dts)
        print(f"Duration: {times[-1] - times[0]:.2f}s")
        print(f"Rate: {1/avg_dt:.1f} Hz (avg interval {avg_dt*1000:.1f}ms)")
        print()

    print(f"{'Channel':<10} {'Mean':>12} {'StdDev':>10} {'CV%':>8} {'Min':>12} {'Max':>12}")
    print("-" * 66)
    for key, name in CHANNELS.items():
        vals = [float(r[key]) for r in rows]
        s = compute_stats(vals)
        print(f"{name:<10} {s['mean']:>12.0f} {s['std']:>10.0f} {s['cv']:>7.2f}% {s['min']:>12.0f} {s['max']:>12.0f}")

    gain_vals = [float(r['f8']) for r in rows]
    gain_s = compute_stats(gain_vals)
    print(f"\nGain: {gain_s['mean']:.1f} (constant={gain_s['std'] < 0.01})")

    print("\n--- Allan Deviation ---")
    print(f"{'Channel':<10} ", end='')
    for key, name in CHANNELS.items():
        vals = [float(r[key]) for r in rows]
        adev = allan_deviation(vals)
        if not adev:
            continue
        if key == 'f2':
            print(f"{'tau':>6}", end='')
            for ch_name in CHANNELS.values():
                print(f" {ch_name:>10}", end='')
            print()
            for m, _ in adev:
                t_sec = m * avg_dt if 'avg_dt' in dir() else m * 0.127
                print(f"{t_sec:>6.2f}s", end='')
                for k2 in CHANNELS.keys():
                    v2 = [float(r[k2]) for r in rows]
                    ad2 = allan_deviation(v2, [m])
                    if ad2:
                        print(f" {ad2[0][1]:>10.0f}", end='')
                print()
        break

    # Spectral ratios
    print("\n--- Spectral Ratios ---")
    for r in rows[:5]:
        red, grn, blu = float(r['f2']), float(r['f3']), float(r['f4'])
        ir, c1, c2 = float(r['f5']), float(r['f6']), float(r['f7'])
        total_vis = red + grn + blu
        print(f"R/G={red/grn:.3f}  B/G={blu/grn:.3f}  IR/Vis={ir/total_vis:.3f}  "
              f"CLR1/CLR2={c1/c2:.3f}")

if __name__ == '__main__':
    main()
