#!/usr/bin/env python3
"""
AI-Sentinel traffic simulation utility.

Generates configurable request patterns (normal, burst, attack) to train and
evaluate the Isolation Forest model. Uses only the Python standard library.

Usage:
  python scripts/traffic_simulator.py --mode normal --duration 60
  python scripts/traffic_simulator.py --mode burst --requests-per-second 20
  python scripts/traffic_simulator.py --mode attack --duration 30 --concurrency 8
"""

import argparse
import random
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock
from urllib.parse import urlencode

# Default endpoints for diverse/attack mode
DEFAULT_ENDPOINTS = ["/api/hello", "/api/items", "/api/users", "/api/health"]

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
    "curl/7.68.0",
    "Python-urllib/3.9",
    "PostmanRuntime/7.28.4",
]

ACCEPT_HEADERS = [
    "application/json",
    "text/plain",
    "application/json, text/plain, */*",
    "*/*",
]


class Stats:
    def __init__(self):
        self.lock = Lock()
        self.sent = 0
        self.errors = 0
        self.latencies = []

    def record_success(self, latency_sec):
        with self.lock:
            self.sent += 1
            self.latencies.append(latency_sec)

    def record_error(self):
        with self.lock:
            self.errors += 1

    def summary(self):
        with self.lock:
            total = self.sent + self.errors
            lat = sorted(self.latencies) if self.latencies else []
            n = len(lat)
            return {
                "sent": self.sent,
                "errors": self.errors,
                "total": total,
                "latencies": lat,
                "count": n,
                "avg_ms": (sum(lat) / n * 1000) if n else 0,
                "min_ms": (lat[0] * 1000) if n else 0,
                "max_ms": (lat[-1] * 1000) if n else 0,
                "p50_ms": (lat[n // 2] * 1000) if n else 0,
                "p99_ms": (lat[int(n * 0.99)] * 1000) if n and n >= 100 else (lat[-1] * 1000 if n else 0),
            }


def parse_args():
    p = argparse.ArgumentParser(
        description="Generate realistic traffic patterns for AI-Sentinel Isolation Forest.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument(
        "--mode",
        choices=["normal", "burst", "attack"],
        default="normal",
        help="Traffic pattern: normal (steady), burst (spikes), attack (diverse endpoints/payloads)",
    )
    p.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Base URL of the target app",
    )
    p.add_argument(
        "--duration",
        type=float,
        default=60.0,
        help="Run duration in seconds (approximate)",
    )
    p.add_argument(
        "--requests-per-second",
        type=float,
        default=10.0,
        dest="rps",
        help="Target requests per second (normal mode); burst uses multiples",
    )
    p.add_argument(
        "--concurrency",
        type=int,
        default=4,
        help="Number of concurrent workers",
    )
    p.add_argument(
        "--endpoints",
        default=",".join(DEFAULT_ENDPOINTS),
        help="Comma-separated paths for diverse/attack mode",
    )
    p.add_argument(
        "--timeout",
        type=float,
        default=10.0,
        help="Request timeout in seconds",
    )
    return p.parse_args()


def random_headers():
    h = {
        "User-Agent": random.choice(USER_AGENTS),
        "Accept": random.choice(ACCEPT_HEADERS),
    }
    if random.random() < 0.3:
        h["Accept-Language"] = random.choice(["en-US,en;q=0.9", "en-GB,en;q=0.9"])
    if random.random() < 0.2:
        h["X-Request-ID"] = f"{random.randint(10000, 99999)}"
    return h


def random_query():
    if random.random() < 0.5:
        return ""
    params = {}
    if random.random() < 0.4:
        params["page"] = str(random.randint(1, 20))
    if random.random() < 0.3:
        params["limit"] = str(random.choice([10, 20, 50]))
    if random.random() < 0.2:
        params["q"] = random.choice(["a", "test", "id"])
    return urlencode(params) if params else ""


def random_payload_size(mode):
    if mode == "normal":
        return 0 if random.random() < 0.7 else random.randint(0, 200)
    if mode == "burst":
        return random.randint(0, 100)
    # attack: wider range including large
    return random.choice([0, 0, 0, random.randint(50, 500), random.randint(500, 2000)])


def build_request(url, mode, timeout):
    query = random_query()
    if query:
        full_url = url + ("&" if "?" in url else "?") + query
    else:
        full_url = url
    req = urllib.request.Request(full_url, headers=random_headers(), method="GET")
    size = random_payload_size(mode)
    if size > 0:
        req.add_header("Content-Type", "application/json")
        req.data = b"x" * size
        req.method = "POST"
    return req


def send_one(base_url, endpoint, mode, timeout, stats):
    url = base_url.rstrip("/") + endpoint
    try:
        req = build_request(url, mode, timeout)
        t0 = time.perf_counter()
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
        elapsed = time.perf_counter() - t0
        stats.record_success(elapsed)
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, TimeoutError):
        stats.record_error()


def run_normal(args, stats, stop_at):
    endpoints = [e.strip() for e in args.endpoints.split(",") if e.strip()] or ["/api/hello"]
    interval = 1.0 / args.rps if args.rps > 0 else 0
    delay_per_worker = interval * args.concurrency

    def worker():
        while time.perf_counter() < stop_at:
            ep = random.choice(endpoints)
            send_one(args.base_url, ep, "normal", args.timeout, stats)
            if delay_per_worker > 0:
                time.sleep(delay_per_worker)

    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        futs = [ex.submit(worker) for _ in range(args.concurrency)]
        for f in as_completed(futs):
            try:
                f.result()
            except Exception:
                pass


def run_burst(args, stats, stop_at):
    endpoints = [e.strip() for e in args.endpoints.split(",") if e.strip()] or ["/api/hello"]
    burst_rps = max(args.rps * 2, 20)
    burst_sec = 2.0
    pause_sec = 1.0
    interval = 1.0 / burst_rps
    delay_per_worker = interval * args.concurrency

    def worker():
        while time.perf_counter() < stop_at:
            burst_until = time.perf_counter() + burst_sec
            while time.perf_counter() < burst_until and time.perf_counter() < stop_at:
                ep = random.choice(endpoints)
                send_one(args.base_url, ep, "burst", args.timeout, stats)
                if delay_per_worker > 0:
                    time.sleep(delay_per_worker)
            if time.perf_counter() < stop_at:
                time.sleep(pause_sec)

    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        futs = [ex.submit(worker) for _ in range(args.concurrency)]
        for f in as_completed(futs):
            try:
                f.result()
            except Exception:
                pass


def run_attack(args, stats, stop_at):
    endpoints = [e.strip() for e in args.endpoints.split(",") if e.strip()] or ["/api/hello"]
    interval = 1.0 / args.rps if args.rps > 0 else 0
    delay_per_worker = interval * args.concurrency

    def worker():
        while time.perf_counter() < stop_at:
            ep = random.choice(endpoints)
            send_one(args.base_url, ep, "attack", args.timeout, stats)
            if delay_per_worker > 0:
                time.sleep(delay_per_worker)

    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        futs = [ex.submit(worker) for _ in range(args.concurrency)]
        for f in as_completed(futs):
            try:
                f.result()
            except Exception:
                pass


def main():
    args = parse_args()
    stats = Stats()
    start = time.perf_counter()
    stop_at = start + args.duration

    print("AI-Sentinel traffic simulator")
    print("=" * 50)
    print(f"Mode:       {args.mode}")
    print(f"Base URL:   {args.base_url}")
    print(f"Duration:   ~{args.duration}s")
    print(f"Target RPS: {args.rps}")
    print(f"Workers:    {args.concurrency}")
    print()

    try:
        if args.mode == "normal":
            run_normal(args, stats, stop_at)
        elif args.mode == "burst":
            run_burst(args, stats, stop_at)
        else:
            run_attack(args, stats, stop_at)
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)

    elapsed = time.perf_counter() - start
    elapsed = max(elapsed, 0.001)
    s = stats.summary()
    actual_rps = s["total"] / elapsed

    print("Statistics")
    print("-" * 50)
    print(f"  Requests sent:   {s['sent']}")
    print(f"  Errors:          {s['errors']}")
    print(f"  Total:           {s['total']}")
    print(f"  Elapsed (s):     {elapsed:.2f}")
    print(f"  Actual RPS:     {actual_rps:.1f}")
    if s["count"] > 0:
        print(f"  Latency (ms):    min={s['min_ms']:.0f}  avg={s['avg_ms']:.0f}  max={s['max_ms']:.0f}  p50={s['p50_ms']:.0f}  p99={s['p99_ms']:.0f}")
    print()
    return 0 if s["errors"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
