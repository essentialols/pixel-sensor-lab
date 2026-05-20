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

    # Use pre-computed fractions if available (brightness-invariant)
    frac = obj.get('frac', {})

    features = {
        'NDVI': idx['NDVI'],
        'RG': idx['RG'],
        'BG': idx['BG'],
        'NIR_VIS': idx['NIR_VIS'],
        'CLR': idx['CLR'],
        'CI': idx['CI'],
        'red_frac': frac.get('R', raw['R'] / vis if vis > 0 else 0),
        'green_frac': frac.get('G', raw['G'] / vis if vis > 0 else 0),
        'blue_frac': frac.get('B', raw['B'] / vis if vis > 0 else 0),
        'lux': obj.get('lux', (raw['G'] / obj.get('gain', 33)) / 109.58),
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

    def fit(self, data, verbose=True):
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

        if verbose:
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

class KNNClassifier:
    """k-Nearest Neighbors, no sklearn needed."""

    def __init__(self, k=5):
        self.k = k
        self.data = []
        self.feature_keys = []
        self.scales = {}

    def fit(self, data, verbose=True):
        self.data = data
        self.feature_keys = sorted(data[0][0].keys())
        for key in self.feature_keys:
            vals = [f[0].get(key, 0) for f in data]
            s = max(abs(max(vals) - min(vals)), 1e-10)
            self.scales[key] = s

    def predict(self, features):
        dists = []
        for train_feat, train_label in self.data:
            d = sum(((features.get(k, 0) - train_feat.get(k, 0)) / self.scales[k]) ** 2
                    for k in self.feature_keys)
            dists.append((math.sqrt(d), train_label))
        dists.sort()
        votes = {}
        for _, label in dists[:self.k]:
            votes[label] = votes.get(label, 0) + 1
        best = max(votes, key=votes.get)
        return best, dists[0][0]

    def evaluate(self, data):
        correct = 0
        confusion = {}
        for features, true_label in data:
            pred, _ = self.predict(features)
            if pred == true_label:
                correct += 1
            confusion[(true_label, pred)] = confusion.get((true_label, pred), 0) + 1
        acc = correct / len(data) if data else 0
        labels = sorted(set(label for _, label in data))
        print(f"\nAccuracy: {correct}/{len(data)} = {acc:.1%}")
        print(f"{'True \\ Pred':<25}", end='')
        for l in labels:
            print(f" {l:<15}", end='')
        print()
        for tl in labels:
            print(f"{tl:<25}", end='')
            for pl in labels:
                print(f" {confusion.get((tl, pl), 0):<15}", end='')
            print()
        return acc

    def save(self, path):
        with open(path, 'w') as f:
            json.dump({'type': 'knn', 'k': self.k, 'feature_keys': self.feature_keys,
                        'scales': self.scales,
                        'data': [(feat, label) for feat, label in self.data]}, f)


def leave_one_out_cv(data, classifier_class, **kwargs):
    correct = 0
    for i in range(len(data)):
        train = data[:i] + data[i+1:]
        test_feat, test_label = data[i]
        clf = classifier_class(**kwargs)
        clf.fit(train, verbose=False)
        pred, _ = clf.predict(test_feat)
        if pred == test_label:
            correct += 1
    return correct / len(data) if data else 0


def feature_importance(data):
    """Rank features by per-class separation (Fisher's criterion)."""
    feature_keys = sorted(data[0][0].keys())
    labels = sorted(set(label for _, label in data))
    if len(labels) < 2:
        return []

    scores = {}
    for key in feature_keys:
        class_means = {}
        class_vars = {}
        for label in labels:
            vals = [f[key] for f, l in data if l == label]
            if not vals:
                continue
            m = sum(vals) / len(vals)
            v = sum((x - m)**2 for x in vals) / len(vals) if len(vals) > 1 else 0
            class_means[label] = m
            class_vars[label] = v

        if len(class_means) < 2:
            scores[key] = 0
            continue

        means = list(class_means.values())
        vars_ = list(class_vars.values())
        between = sum((m - sum(means)/len(means))**2 for m in means)
        within = sum(vars_) + 1e-10
        scores[key] = between / within

    return sorted(scores.items(), key=lambda x: -x[1])


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

    labels = sorted(set(label for _, label in data))
    print(f"Loaded {len(data)} samples, {len(labels)} classes: {', '.join(labels)}")

    # Feature importance
    print("\n--- Feature Importance (Fisher criterion) ---")
    fi = feature_importance(data)
    for name, score in fi[:10]:
        bar = '#' * min(40, int(score * 10))
        print(f"  {name:<15} {score:.3f}  {bar}")

    # Train and evaluate both classifiers
    print("\n--- Nearest Centroid ---")
    nc = NearestCentroidClassifier()
    nc.fit(data)
    nc_acc = nc.evaluate(data)

    if len(data) >= 6:
        print("\n--- k-NN (k=3) ---")
        knn = KNNClassifier(k=min(3, len(data) - 1))
        knn.fit(data)
        knn_acc = knn.evaluate(data)

        # Leave-one-out CV
        if len(data) <= 200:
            print("\n--- Leave-One-Out Cross-Validation ---")
            nc_loo = leave_one_out_cv(data, NearestCentroidClassifier)
            knn_loo = leave_one_out_cv(data, KNNClassifier, k=min(3, len(data) - 2))
            print(f"  Nearest Centroid LOO: {nc_loo:.1%}")
            print(f"  k-NN (k=3) LOO:      {knn_loo:.1%}")
    else:
        knn = None

    # Save best model
    nc.save(os.path.join(data_dir, 'ripeness_model.json'))

    if predict_file:
        print(f"\n--- Predictions for {predict_file} ---")
        with open(predict_file) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                obj = json.loads(line)
                features = extract_features(obj)
                if features:
                    label, dist = nc.predict(features)
                    print(f"  seq={obj.get('seq', '?')}: {label} (d={dist:.3f})")

if __name__ == '__main__':
    main()
