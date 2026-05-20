#!/usr/bin/env python3
"""Generate synthetic fruit ripeness spectral data from literature profiles.

Creates realistic JSONL training data based on published spectral
characteristics of fruit at different ripeness stages. Useful for:
- Validating the ML pipeline before real data collection
- Establishing theoretical separability of our 6-channel sensor
- Baseline comparison for real measurements

Profiles derived from:
- Lauretti 2025 (banana ripeness, 11-ch AS7341)
- Li 2022 (NDVI-based fruit maturity)
- Spectral reflectance databases for produce

Usage:
    python3 generate_synthetic.py [--samples 50] [--noise 0.03]
    python3 generate_synthetic.py --validate
"""
import json
import math
import os
import random
import sys

RALS = {'R': 950.9, 'G': 1118.0, 'B': 298.9, 'IR': 2577.4, 'CLR1': 4909.1, 'CLR2': 4932.7}

# Spectral profiles: mean raw channel values at gain=33.
# Derived from published reflectance curves mapped to VD6282 channel bands:
#   R: 600-680nm, G: 500-570nm, B: 430-490nm, IR: 800-900nm,
#   CLR1: broadband visible, CLR2: broadband including NIR
PROFILES = {
    'banana_green': {
        'raw': {'R': 35000, 'G': 85000, 'B': 18000, 'IR': 320000, 'CLR1': 60000, 'CLR2': 380000},
        'cv': 0.05,
        'notes': 'High chlorophyll: strong G absorption of R, high IR (mesophyll scattering)',
    },
    'banana_yellow': {
        'raw': {'R': 95000, 'G': 78000, 'B': 12000, 'IR': 290000, 'CLR1': 55000, 'CLR2': 350000},
        'cv': 0.06,
        'notes': 'Carotenoid dominance: R increases, G decreases, B drops (no chlorophyll)',
    },
    'banana_brown': {
        'raw': {'R': 55000, 'G': 42000, 'B': 15000, 'IR': 180000, 'CLR1': 38000, 'CLR2': 220000},
        'cv': 0.08,
        'notes': 'Melanin/oxidation: overall lower, flatter spectrum, higher variability',
    },
    'tomato_green': {
        'raw': {'R': 28000, 'G': 72000, 'B': 14000, 'IR': 280000, 'CLR1': 48000, 'CLR2': 320000},
        'cv': 0.05,
        'notes': 'Strong chlorophyll: very low R/G, high NDVI',
    },
    'tomato_red': {
        'raw': {'R': 120000, 'G': 35000, 'B': 8000, 'IR': 200000, 'CLR1': 42000, 'CLR2': 260000},
        'cv': 0.06,
        'notes': 'Lycopene dominance: very high R/G, moderate IR',
    },
    'avocado_firm': {
        'raw': {'R': 32000, 'G': 68000, 'B': 16000, 'IR': 350000, 'CLR1': 52000, 'CLR2': 400000},
        'cv': 0.05,
        'notes': 'Dark green skin: moderate chlorophyll, very high NIR (thick mesocarp)',
    },
    'avocado_ripe': {
        'raw': {'R': 22000, 'G': 30000, 'B': 12000, 'IR': 280000, 'CLR1': 32000, 'CLR2': 310000},
        'cv': 0.07,
        'notes': 'Darkened skin: broadly lower reflectance, softened tissue',
    },
}


def generate_sample(profile_name, profile, seq, gain=33):
    raw = {}
    for ch, mean in profile['raw'].items():
        noise = random.gauss(0, mean * profile['cv'])
        raw[ch] = max(100, mean + noise)

    vis = raw['R'] + raw['G'] + raw['B']
    ir = raw['IR']
    r, g, b = raw['R'], raw['G'], raw['B']

    obj = {
        'seq': seq,
        't': 1747700000.0 + seq * 0.128,
        'aoc_ts': 0,
        'gain': gain,
        'lux': round((g / gain) / 109.58, 1),
        'raw': {k: round(v) for k, v in raw.items()},
        'frac': {
            'R': round(r / vis, 4) if vis > 0 else 0,
            'G': round(g / vis, 4) if vis > 0 else 0,
            'B': round(b / vis, 4) if vis > 0 else 0,
        },
        'idx': {
            'NDVI': round((ir - r) / (ir + r), 4) if (ir + r) > 0 else 0,
            'RG': round(r / g, 4) if g > 0 else 0,
            'BG': round(b / g, 4) if g > 0 else 0,
            'NIR_VIS': round(ir / vis, 4) if vis > 0 else 0,
            'CLR': round(raw['CLR1'] / raw['CLR2'], 4) if raw['CLR2'] > 0 else 0,
            'CI': round((r - b) / g, 4) if g > 0 else 0,
        },
        'rals': {k: round(raw[k] / RALS[k], 4) for k in RALS},
        'label': {
            'fruit': profile_name.split('_')[0],
            'stage': '_'.join(profile_name.split('_')[1:]),
        },
    }

    tof_dist = random.randint(40, 120)
    tof_photons = random.randint(100, 500)
    peak_bin = max(0, min(23, int(tof_dist / 12.5) + random.randint(-1, 1)))
    bins = [0] * 24
    for i in range(24):
        spread = abs(i - peak_bin)
        bins[i] = max(0, int(tof_photons * math.exp(-spread * 0.8) / 3))
    bins[peak_bin] = tof_photons
    total = sum(bins)
    centroid = sum(i * b for i, b in enumerate(bins)) / total if total > 0 else peak_bin

    obj['tof'] = {
        'photons': total,
        'dist_mm': tof_dist,
        'peak_bin': peak_bin,
        'centroid': round(centroid, 2),
        'bins': bins,
    }

    return obj


def generate_dataset(samples_per_class=50, output_dir=None):
    if output_dir is None:
        output_dir = os.path.join(os.path.dirname(__file__), '..', 'data', 'synthetic')
    os.makedirs(output_dir, exist_ok=True)

    all_samples = []
    seq = 0
    for name, profile in PROFILES.items():
        samples = []
        for _ in range(samples_per_class):
            seq += 1
            samples.append(generate_sample(name, profile, seq))
        all_samples.extend(samples)

        path = os.path.join(output_dir, f'synth_{name}.jsonl')
        with open(path, 'w') as f:
            for s in samples:
                f.write(json.dumps(s) + '\n')

    combined = os.path.join(output_dir, 'synthetic_all.jsonl')
    random.shuffle(all_samples)
    with open(combined, 'w') as f:
        for s in all_samples:
            f.write(json.dumps(s) + '\n')

    print(f"Generated {len(all_samples)} samples across {len(PROFILES)} classes")
    print(f"Output: {output_dir}/")
    for name in sorted(PROFILES):
        print(f"  synth_{name}.jsonl ({samples_per_class} samples)")
    print(f"  synthetic_all.jsonl ({len(all_samples)} samples, shuffled)")

    return output_dir


def validate():
    """Generate synthetic data and run the full ML pipeline on it."""
    output_dir = generate_dataset(samples_per_class=50)
    print()

    sys.path.insert(0, os.path.dirname(__file__))
    from train_ripeness import (load_labeled_data, NearestCentroidClassifier,
                                 KNNClassifier, leave_one_out_cv,
                                 feature_importance, compare_feature_groups)

    data = load_labeled_data(output_dir)
    labels = sorted(set(label for _, label in data))
    print(f"Loaded {len(data)} samples, {len(labels)} classes: {', '.join(labels)}")

    print("\n--- Feature Importance (Fisher criterion) ---")
    fi = feature_importance(data)
    for name, score in fi[:15]:
        bar = '#' * min(40, int(score * 2))
        print(f"  {name:<15} {score:.3f}  {bar}")

    print("\n--- Nearest Centroid ---")
    nc = NearestCentroidClassifier()
    nc.fit(data)
    nc.evaluate(data)

    print("\n--- k-NN (k=5) ---")
    knn = KNNClassifier(k=5)
    knn.fit(data)
    knn.evaluate(data)

    if len(data) <= 500:
        print("\n--- Leave-One-Out Cross-Validation ---")
        nc_loo = leave_one_out_cv(data, NearestCentroidClassifier)
        knn_loo = leave_one_out_cv(data, KNNClassifier, k=5)
        print(f"  Nearest Centroid LOO: {nc_loo:.1%}")
        print(f"  k-NN (k=5) LOO:      {knn_loo:.1%}")

    compare_feature_groups(data)


if __name__ == '__main__':
    noise = 0.03
    samples = 50

    for i, arg in enumerate(sys.argv[1:], 1):
        if arg == '--noise' and i < len(sys.argv) - 1:
            noise = float(sys.argv[i + 1])
        elif arg == '--samples' and i < len(sys.argv) - 1:
            samples = int(sys.argv[i + 1])
        elif arg == '--validate':
            validate()
            sys.exit(0)

    generate_dataset(samples_per_class=samples)
