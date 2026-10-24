#!/usr/bin/env python3
"""Independent verification of the DeviceScope scoring arithmetic.

This script is deliberately a *second implementation*. It shares no code with the
Kotlin app: it re-derives every number from the inputs and compares the result to
what the app wrote. Agreement to 1e-6 means the weighting, the geometric means and
the renormalisation are implemented the same way in both places.

Usage
-----
    python tools/verify_scoring.py --file validation-20260101-120000-scoring.csv
    python tools/verify_scoring.py --self-test

The CSV written by the app has the columns:
    label, inputs, combined, cpu, gpu, memory, ux
where `inputs` looks like
    single=1400.0,multi=5200.0,memory=900.0,storage=1100.0,gpu=1600.0,ux=950.0

Exit code 0 = every row verified, 1 = mismatch or bad input.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from typing import Dict, Optional, Sequence

# Weights must match app/src/main/assets/scoring.json.
WEIGHTS: Dict[str, float] = {
    "cpu": 0.40,
    "gpu": 0.30,
    "memory": 0.15,
    "ux": 0.15,
}

TOLERANCE = 1e-6


def geo_mean(values: Sequence[Optional[float]]) -> Optional[float]:
    """Geometric mean of the positive values; None when nothing is usable."""
    usable = [v for v in values if v is not None and v > 0.0]
    if not usable:
        return None
    return math.exp(sum(math.log(v) for v in usable) / len(usable))


def breakdown(inputs: Dict[str, float], weights: Dict[str, float] = WEIGHTS):
    """Returns (cpu, gpu, memory, ux, combined) exactly like ScoreConfig.breakdown."""
    cpu = geo_mean([inputs.get("single"), inputs.get("multi")])
    gpu = inputs.get("gpu") if (inputs.get("gpu") or 0) > 0 else None
    memory = geo_mean([inputs.get("memory"), inputs.get("storage")])
    ux = inputs.get("ux") if (inputs.get("ux") or 0) > 0 else None

    parts = []
    if cpu is not None:
        parts.append((weights["cpu"], cpu))
    if gpu is not None:
        parts.append((weights["gpu"], gpu))
    if memory is not None:
        parts.append((weights["memory"], memory))
    if ux is not None:
        parts.append((weights["ux"], ux))

    total_weight = sum(w for w, _ in parts)
    combined = sum(w * v for w, v in parts) / total_weight if total_weight > 0 else 0.0
    return cpu, gpu, memory, ux, combined


def shifted(weights: Dict[str, float], group: str, delta: float) -> Dict[str, float]:
    """Shift one weight by `delta` points, floor at 0.01 (same as ScoreConfig.shifted)."""
    out = dict(weights)
    out[group] = max(0.01, out[group] + delta)
    return out


def parse_inputs(text: str) -> Dict[str, float]:
    values: Dict[str, float] = {}
    for chunk in text.split(","):
        if "=" not in chunk:
            continue
        key, raw = chunk.split("=", 1)
        key = key.strip()
        raw = raw.strip()
        if raw in ("", "null", "None"):
            continue
        try:
            values[key] = float(raw)
        except ValueError:
            raise ValueError(f"bad number {raw!r} for {key!r}")
    return values


def verify_file(path: str) -> int:
    failures = 0
    rows = 0
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            rows += 1
            label = row.get("label", f"row {rows}")
            inputs = parse_inputs(row.get("inputs", ""))
            cpu, gpu, memory, ux, combined = breakdown(inputs)

            checks = [
                ("combined", combined, row.get("combined")),
                ("cpu", cpu, row.get("cpu")),
                ("gpu", gpu, row.get("gpu")),
                ("memory", memory, row.get("memory")),
                ("ux", ux, row.get("ux")),
            ]
            for name, expected, actual_text in checks:
                actual = float(actual_text) if actual_text not in (None, "") else None
                if expected is None and actual is None:
                    continue
                if expected is None or actual is None or abs(expected - actual) > TOLERANCE:
                    failures += 1
                    print(f"FAIL {label} · {name}: python={expected} app={actual}")
            print(f"ok   {label}: combined={combined:.6f}")

    if rows == 0:
        print("FAIL: no rows in CSV")
        return 1
    print(f"\n{rows} satır doğrulandı, {failures} uyuşmazlık.")
    return 0 if failures == 0 else 1


def self_test() -> int:
    problems = 0

    def check(name: str, condition: bool, detail: str) -> None:
        nonlocal problems
        if condition:
            print(f"ok   {name}")
        else:
            problems += 1
            print(f"FAIL {name}: {detail}")

    # 1. reference device: every part exactly 1000 -> combined exactly 1000
    ref = {"single": 1000.0, "multi": 1000.0, "memory": 1000.0,
           "storage": 1000.0, "gpu": 1000.0, "ux": 1000.0}
    _, _, _, _, combined = breakdown(ref)
    check("referans cihaz = 1000", abs(combined - 1000.0) < TOLERANCE, f"{combined}")

    # 2. geometric mean sanity: sqrt(400*1600) = 800
    cpu, _, _, _, _ = breakdown({"single": 400.0, "multi": 1600.0})
    check("geometrik ortalama", abs(cpu - 800.0) < TOLERANCE, f"{cpu}")

    # 3. weights sum to 1 and are actually used
    check("ağırlık toplamı", abs(sum(WEIGHTS.values()) - 1.0) < TOLERANCE, str(WEIGHTS))

    # 4. 20% slowdown of one workload: single drops to 0.8x.
    #    CPU group = sqrt(0.8*s * m) = sqrt(0.8) * sqrt(s*m) -> -10.557%
    #    total drop = 0.40 * 10.557% = 4.223%
    base = {"single": 1000.0, "multi": 1000.0, "memory": 1000.0,
            "storage": 1000.0, "gpu": 1000.0, "ux": 1000.0}
    slow = dict(base, single=800.0)
    _, _, _, _, base_combined = breakdown(base)
    _, _, _, _, slow_combined = breakdown(slow)
    observed = (base_combined - slow_combined) / base_combined * 100.0
    expected = WEIGHTS["cpu"] * (1.0 - math.sqrt(0.8)) * 100.0
    check("20% tek iş yükü yavaşlatma", abs(observed - expected) < 1e-9,
          f"gözlenen {observed:.9f}%, beklenen {expected:.9f}%")

    # 5. 30% slowdown of one category (GPU) -> total drop 0.30 * 30% = 9%
    slow_gpu = dict(base, gpu=700.0)
    _, _, _, _, gpu_combined = breakdown(slow_gpu)
    observed_gpu = (base_combined - gpu_combined) / base_combined * 100.0
    expected_gpu = WEIGHTS["gpu"] * 30.0
    check("30% kategori yavaşlatma", abs(observed_gpu - expected_gpu) < 1e-9,
          f"gözlenen {observed_gpu:.9f}%, beklenen {expected_gpu:.9f}%")

    # 6. weight sensitivity: +5 points on CPU must raise a CPU-heavy device
    fast_cpu = {"single": 2000.0, "multi": 2000.0, "memory": 500.0,
                "storage": 500.0, "gpu": 500.0, "ux": 500.0}
    normal = breakdown(fast_cpu)[4]
    boosted = breakdown(fast_cpu, shifted(WEIGHTS, "cpu", 0.05))[4]
    check("ağırlık +5 puan etkisi", boosted > normal,
          f"normal {normal:.3f}, +5p {boosted:.3f}")

    # 7. missing GPU must renormalise instead of scoring zero
    no_gpu = {"single": 1000.0, "multi": 1000.0, "memory": 1000.0,
              "storage": 1000.0, "ux": 1000.0}
    check("eksik grup yeniden normalise", abs(breakdown(no_gpu)[4] - 1000.0) < TOLERANCE,
          str(breakdown(no_gpu)[4]))

    # 8. ordering: a faster device must score higher
    slow_device = {"single": 500.0, "multi": 600.0, "memory": 400.0,
                   "storage": 400.0, "gpu": 500.0, "ux": 500.0}
    check("sıralama tutarlı", breakdown(base)[4] > breakdown(slow_device)[4],
          f"{breakdown(base)[4]} vs {breakdown(slow_device)[4]}")

    print(f"\n{'TÜM TESTLER GEÇTİ' if problems == 0 else str(problems) + ' TEST BAŞARISIZ'}")
    return 0 if problems == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="DeviceScope puanlama bağımsız doğrulaması")
    parser.add_argument("--file", help="uygulamanın yazdığı *-scoring.csv")
    parser.add_argument("--self-test", action="store_true", help="formülün kendi testleri")
    args = parser.parse_args()

    if args.file:
        return verify_file(args.file)
    if args.self_test:
        return self_test()
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
