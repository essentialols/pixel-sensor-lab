#!/usr/bin/env python3
"""
analyze_ripeness.py — Analyze multi-sensor fruit ripeness capture

Processes data from:
  - ToF histogram (940nm laser): displacement, reflectivity, scattering
  - VD6282 spectral (R,G,B,IR,Clear,Vis): color signature
  - Camera RGB: visual appearance

Usage:
    python3 analyze_ripeness.py <capture_dir>
"""
import sys
import os
import csv
import numpy as np

def load_tof_histogram(path):
    """Load BPF histogram CSV and extract features."""
    if not os.path.exists(path):
        print(f"  ToF histogram not found: {path}")
        return None
    
    rows = []
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    
    if not rows:
        return None
    
    # Extract features from histogram data
    features = {}
    
    # Try both CSV formats (bpf_hist_stream vs bpf_fast_hist)
    if 'centroid' in rows[0]:
        centroids = [float(r['centroid']) for r in rows if r.get('centroid')]
        totals = [float(r.get('total', 0)) for r in rows if r.get('total')]
        peak_vals = [float(r.get('peak_val', 0)) for r in rows if r.get('peak_val')]
    else:
        centroids = []
        totals = []
    
    if centroids:
        features['centroid_mean'] = np.mean(centroids)
        features['centroid_std'] = np.std(centroids)
        features['displacement_um'] = features['centroid_std'] * 250  # bin_width ~250ps
    
    if totals:
        features['total_photons_mean'] = np.mean(totals)
        features['total_photons_std'] = np.std(totals)
        features['reflectivity'] = features['total_photons_mean']
        # Fano factor (variance/mean)
        features['fano_factor'] = np.var(totals) / np.mean(totals) if np.mean(totals) > 0 else 0
    
    if peak_vals:
        features['peak_mean'] = np.mean(peak_vals)
    
    features['n_frames'] = len(rows)
    return features


def load_spectral(path):
    """Load VD6282 spectral CSV and extract features."""
    if not os.path.exists(path):
        print(f"  Spectral data not found: {path}")
        return None
    
    rows = []
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    
    if not rows:
        return None
    
    features = {}
    
    # VD6282 channels are in v0-v5:
    # Typical mapping: v0=R, v1=G, v2=B, v3=IR, v4=Clear, v5=Visible
    # (exact mapping depends on sensor HAL configuration)
    channels = {}
    for ch in range(6):
        key = f'v{ch}'
        vals = [float(r.get(key, 0)) for r in rows if r.get(key)]
        if vals:
            channels[ch] = vals
    
    if not channels:
        return None
    
    # Extract spectral features
    for ch, vals in channels.items():
        features[f'ch{ch}_mean'] = np.mean(vals)
        features[f'ch{ch}_std'] = np.std(vals)
    
    # Color ratios (if we have R, G, B channels)
    if 0 in channels and 1 in channels and 2 in channels:
        r_mean = np.mean(channels[0])
        g_mean = np.mean(channels[1])
        b_mean = np.mean(channels[2])
        total = r_mean + g_mean + b_mean
        if total > 0:
            features['r_ratio'] = r_mean / total
            features['g_ratio'] = g_mean / total
            features['b_ratio'] = b_mean / total
            features['rg_ratio'] = r_mean / g_mean if g_mean > 0 else 0
            # Green/Red ratio — key ripeness indicator (chlorophyll)
            features['gr_ratio'] = g_mean / r_mean if r_mean > 0 else 0
    
    # IR vs Visible ratio (if channels 3=IR, 5=Vis)
    if 3 in channels and 5 in channels:
        ir_mean = np.mean(channels[3])
        vis_mean = np.mean(channels[5])
        features['ir_vis_ratio'] = ir_mean / vis_mean if vis_mean > 0 else 0
    
    # Clear vs IR — water content indicator
    if 3 in channels and 4 in channels:
        ir_mean = np.mean(channels[3])
        clear_mean = np.mean(channels[4])
        features['ir_clear_ratio'] = ir_mean / clear_mean if clear_mean > 0 else 0
    
    features['n_events'] = len(rows)
    return features


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 analyze_ripeness.py <capture_dir>")
        sys.exit(1)
    
    capture_dir = sys.argv[1]
    
    print(f"\n{'='*60}")
    print(f" Fruit Ripeness Analysis: {os.path.basename(capture_dir)}")
    print(f"{'='*60}\n")
    
    # Load ToF data
    tof_path = os.path.join(capture_dir, 'tof_histogram.csv')
    print("[ToF Laser (940nm)]")
    tof = load_tof_histogram(tof_path)
    if tof:
        print(f"  Frames: {tof.get('n_frames', 0)}")
        if 'centroid_mean' in tof:
            print(f"  Distance (centroid): {tof['centroid_mean']:.3f} bins")
            print(f"  Vibration (std): {tof['centroid_std']:.4f} bins ({tof.get('displacement_um', 0):.1f} um)")
        if 'reflectivity' in tof:
            print(f"  Reflectivity (total photons): {tof['reflectivity']:.0f}")
            print(f"  Fano factor: {tof.get('fano_factor', 0):.1f}x Poisson")
    
    # Load spectral data
    spec_path = os.path.join(capture_dir, 'spectral_vd6282.csv')
    print(f"\n[VD6282 Spectral (R,G,B,IR,Clear,Vis)]")
    spec = load_spectral(spec_path)
    if spec:
        print(f"  Events: {spec.get('n_events', 0)}")
        for ch in range(6):
            key = f'ch{ch}_mean'
            if key in spec:
                labels = ['Red', 'Green', 'Blue', 'IR(850nm)', 'Clear', 'Visible']
                print(f"  Ch{ch} ({labels[ch]}): mean={spec[key]:.2f} std={spec.get(f'ch{ch}_std', 0):.3f}")
        
        if 'gr_ratio' in spec:
            print(f"\n  Green/Red ratio: {spec['gr_ratio']:.4f}  (chlorophyll indicator)")
        if 'ir_vis_ratio' in spec:
            print(f"  IR/Visible ratio: {spec['ir_vis_ratio']:.4f}  (water/sugar content)")
        if 'ir_clear_ratio' in spec:
            print(f"  IR/Clear ratio: {spec['ir_clear_ratio']:.4f}")
    
    # Summary
    print(f"\n{'='*60}")
    print(" RIPENESS SIGNATURE")
    print(f"{'='*60}")
    
    features = {}
    if tof:
        features.update({f'tof_{k}': v for k, v in tof.items() if isinstance(v, (int, float))})
    if spec:
        features.update({f'spec_{k}': v for k, v in spec.items() if isinstance(v, (int, float))})
    
    print(f"  Total features: {len(features)}")
    for k, v in sorted(features.items()):
        print(f"    {k}: {v:.4f}" if isinstance(v, float) else f"    {k}: {v}")
    
    # Save features to CSV
    feat_path = os.path.join(capture_dir, 'ripeness_features.csv')
    with open(feat_path, 'w') as f:
        writer = csv.DictWriter(f, fieldnames=sorted(features.keys()))
        writer.writeheader()
        writer.writerow(features)
    print(f"\n  Features saved to: {feat_path}")


if __name__ == '__main__':
    main()
