#!/usr/bin/env python3
"""Train a fruit ripeness classifier from labeled JSONL captures.

Usage:
    # Train from all labeled data in data/fruit/
    python3 train_ripeness.py data/fruit/

    # Predict on new capture
    python3 train_ripeness.py data/fruit/ --predict data/new_capture.jsonl
"""
import json
import sys
import os
import math

def extract_features(obj):
    if 'raw' not in obj or 'idx' not in obj:
        return None
    raw = obj['raw']
    idx = obj['idx']
    vis = raw['R'] + raw['G'] + raw['B']

    features = {
        'NDVI': idx['NDVI'],
        'RG': idx['RG'],
        'BG': idx['BG'],
        'NIR_VIS': idx['NIR_VIS'],
        'CLR': idx['CLR'],
        'CI': idx['CI'],
        'red_frac': raw['R'] / vis if vis > 0 else 0,
        'green_frac': raw['G'] / vis if vis > 0 else 0,
        'blue_frac': raw['B'] / vis if vis > 0 else 0,
        'ir_intensity': raw['IR'],
        'gain': obj.get('gain', 33),
    }

    if 'tof' in obj and obj['tof'].get('photons', 0) > 0 and obj['tof']['photons'] < 1e8:
        tof = obj['tof']
        features['tof_photons'] = tof['photons']
        features['tof_dist'] = tof.get('dist_mm', -1)
        bins = tof.get('bins', [])
        total = sum(bins) if bins else 0
        if total > 0:
            features['tof_centroid'] = sum(i * b for i, b in enumerate(bins)) / total
            features['tof_peak_bin'] = max(range(len(bins)), key=lambda i: bins[i])
            features['tof_fwhm'] = sum(1 for b in bins if b > max(bins) * 0.5)

    return features

def load_labeled_data(dirpath):
    data = []
    for fname in sorted(os.listdir(dirpath)):
        if not fname.endswith('.jsonl'):
            continue
        path = os.path.join(dirpath, fname)
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                obj = json.loads(line)
                if 'label' not in obj:
                    continue
                features = extract_features(obj)
                if features is None:
                    continue
                label = f"{obj['label']['fruit']}_{obj['label']['stage']}"
                data.append((features, label))
    return data

class NearestCentroidClassifier:
    """Minimal classifier that works without sklearn."""

    def __init__(self):
        self.centroids = {}
        self.feature_keys = []

    def fit(self, data):
        label_features = {}
        for features, label in data:
            if label not in label_features:
                label_features[label] = []
            label_features[label].append(features)

        self.feature_keys = sorted(data[0][0].keys())

        for label, feat_list in label_features.items():
            centroid = {}
            for key in self.feature_keys:
                vals = [f.get(key, 0) for f in feat_list]
                centroid[key] = sum(vals) / len(vals)
            self.centroids[label] = centroid

        print(f"Trained on {len(data)} samples, {len(self.centroids)} classes")
        print(f"Features: {', '.join(self.feature_keys)}")
        print(f"Classes: {', '.join(sorted(self.centroids.keys()))}")

    def predict(self, features):
        best_label = None
        best_dist = float('inf')
        for label, centroid in self.centroids.items():
            dist = 0
            for key in self.feature_keys:
                diff = features.get(key, 0) - centroid.get(key, 0)
                scale = abs(centroid.get(key, 1)) + 1e-10
                dist += (diff / scale) ** 2
            dist = math.sqrt(dist)
            if dist < best_dist:
                best_dist = dist
                best_label = label
        return best_label, best_dist

    def evaluate(self, data):
        correct = 0
        total = 0
        confusion = {}
        for features, true_label in data:
            pred_label, _ = self.predict(features)
            if pred_label == true_label:
                correct += 1
            key = (true_label, pred_label)
            confusion[key] = confusion.get(key, 0) + 1
            total += 1

        accuracy = correct / total if total > 0 else 0
        print(f"\nAccuracy: {correct}/{total} = {accuracy:.1%}")

        labels = sorted(set(label for _, label in data))
        print(f"\n{'True \\ Pred':<25}", end='')
        for l in labels:
            print(f" {l:<15}", end='')
        print()
        for true_l in labels:
            print(f"{true_l:<25}", end='')
            for pred_l in labels:
                count = confusion.get((true_l, pred_l), 0)
                print(f" {count:<15}", end='')
            print()
        return accuracy

    def save(self, path):
        with open(path, 'w') as f:
            json.dump({'centroids': self.centroids, 'feature_keys': self.feature_keys}, f, indent=2)
        print(f"Model saved to {path}")

    def load(self, path):
        with open(path) as f:
            d = json.load(f)
        self.centroids = d['centroids']
        self.feature_keys = d['feature_keys']

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <data_dir> [--predict <capture.jsonl>]")
        sys.exit(1)

    data_dir = sys.argv[1]
    predict_file = None
    if '--predict' in sys.argv:
        idx = sys.argv.index('--predict')
        if idx + 1 < len(sys.argv):
            predict_file = sys.argv[idx + 1]

    data = load_labeled_data(data_dir)
    if not data:
        print(f"No labeled data found in {data_dir}")
        print("Capture labeled data with: tools/capture_fruit.sh <fruit> <stage>")
        sys.exit(1)

    clf = NearestCentroidClassifier()
    clf.fit(data)
    clf.evaluate(data)
    clf.save(os.path.join(data_dir, 'ripeness_model.json'))

    if predict_file:
        print(f"\n--- Predictions for {predict_file} ---")
        with open(predict_file) as f:
            for line in f:
                obj = json.loads(line.strip())
                features = extract_features(obj)
                if features:
                    label, dist = clf.predict(features)
                    print(f"  t={obj.get('t', 0):.1f}: {label} (distance={dist:.3f})")

if __name__ == '__main__':
    main()
