#!/usr/bin/env python3
"""Convert raw VD6282 spectral captures to calibrated values.

Uses factory RALS (Reference Ambient Light Source) calibration from
/mnt/vendor/persist/sensors/registry/vd6282_spectral_fac_cal.reg

Raw counts are normalized by the RALS values to produce relative
spectral power that is comparable across devices.
"""
import csv
import sys

# Factory calibration values from this specific Pixel 7 Pro (serial 28071FDH3000R7)
RALS = {
    'R':    950.9087162826094,
    'G':   1117.9805710091719,
    'B':    298.94512567329764,
    'IR':  2577.402032322832,
    'CLR1': 4909.111490385691,
    'CLR2': 4932.690660085636,
}
DARK = {'R': 0, 'G': 0, 'B': 0, 'IR': 0, 'CLR1': 0, 'CLR2': 0}
G_TO_LUX = 109.582727

RAW_FIELDS = {'R': 'f2', 'G': 'f3', 'B': 'f4', 'IR': 'f5', 'CLR1': 'f6', 'CLR2': 'f7'}

def calibrate_row(row):
    out = {
        'wall_time': row['wall_time'],
        'gain': float(row['f8']),
    }
    for ch, field in RAW_FIELDS.items():
        raw = float(row[field])
        dark = DARK[ch]
        rals = RALS[ch]
        out[f'raw_{ch}'] = raw
        out[f'cal_{ch}'] = (raw - dark) / rals if rals > 0 else 0

    raw_g = float(row['f3'])
    gain = float(row['f8'])
    out['lux_est'] = (raw_g / gain) / G_TO_LUX if gain > 0 else 0
    return out

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <raw_capture.csv> [output.csv]")
        print("Converts raw spectral captures to RALS-calibrated values.")
        sys.exit(1)

    inpath = sys.argv[1]
    outpath = sys.argv[2] if len(sys.argv) > 2 else inpath.replace('.csv', '_calibrated.csv')

    rows = []
    with open(inpath) as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(calibrate_row(r))

    if not rows:
        print("No data rows found.")
        sys.exit(1)

    fields = ['wall_time', 'gain', 'lux_est',
              'raw_R', 'raw_G', 'raw_B', 'raw_IR', 'raw_CLR1', 'raw_CLR2',
              'cal_R', 'cal_G', 'cal_B', 'cal_IR', 'cal_CLR1', 'cal_CLR2']

    with open(outpath, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Calibrated {len(rows)} samples -> {outpath}")
    print()

    # Summary
    print(f"{'Channel':<8} {'Raw Mean':>12} {'Cal Mean':>10} {'Cal StdDev':>10}")
    print("-" * 44)
    for ch in ['R', 'G', 'B', 'IR', 'CLR1', 'CLR2']:
        raw_vals = [r[f'raw_{ch}'] for r in rows]
        cal_vals = [r[f'cal_{ch}'] for r in rows]
        raw_mean = sum(raw_vals) / len(raw_vals)
        cal_mean = sum(cal_vals) / len(cal_vals)
        cal_std = (sum((v - cal_mean)**2 for v in cal_vals) / len(cal_vals))**0.5
        print(f"{ch:<8} {raw_mean:>12.0f} {cal_mean:>10.1f} {cal_std:>10.2f}")

    lux_vals = [r['lux_est'] for r in rows]
    lux_mean = sum(lux_vals) / len(lux_vals)
    print(f"\nEstimated lux: {lux_mean:.1f}")

if __name__ == '__main__':
    main()
