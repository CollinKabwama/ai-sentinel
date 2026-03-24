#!/usr/bin/env python3
"""
AI-Sentinel Stage 2: Generate traffic and monitor Isolation Forest training progress.

Polls /actuator/sentinel until isolationForestModelLoaded is true and
isolationForestModelVersion >= 1. Uses only the Python standard library.

Usage:
  python scripts/train_monitor.py
  python scripts/train_monitor.py --base-url http://localhost:8080 --total-requests 200
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Event


def parse_args():
    p = argparse.ArgumentParser(
        description="Generate traffic and monitor Isolation Forest training until model is loaded.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Base URL of the demo app",
    )
    p.add_argument(
        "--traffic-endpoint",
        default="/api/hello",
        help="Path to hit for generating traffic",
    )
    p.add_argument(
        "--total-requests",
        type=int,
        default=500,
        help="Total number of requests to send",
    )
    p.add_argument(
        "--concurrency",
        type=int,
        default=4,
        help="Number of concurrent request workers",
    )
    p.add_argument(
        "--poll-interval",
        type=float,
        default=3.0,
        help="Seconds between actuator polls",
    )
    p.add_argument(
        "--request-delay-ms",
        type=float,
        default=10,
        help="Delay in ms between requests per worker (0 = no delay)",
    )
    return p.parse_args()


def fetch(url, timeout=10):
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read().decode()
    except urllib.error.URLError as e:
        raise ConnectionError(f"{url}: {e.reason}") from e


def send_traffic(base_url, path, count, delay_ms, stop_event):
    url = base_url.rstrip("/") + path
    sent = 0
    errors = 0
    while sent < count and not stop_event.is_set():
        try:
            fetch(url, timeout=5)
            sent += 1
        except (ConnectionError, OSError):
            errors += 1
        if delay_ms > 0:
            time.sleep(delay_ms / 1000.0)
    return sent, errors


def get_sentinel_info(base_url, timeout=10):
    url = base_url.rstrip("/") + "/actuator/sentinel"
    raw = fetch(url, timeout=timeout)
    return json.loads(raw)


def main():
    args = parse_args()
    base = args.base_url.rstrip("/")
    stop_event = Event()

    # Check actuator is reachable before starting
    try:
        info = get_sentinel_info(base)
    except (ConnectionError, OSError, json.JSONDecodeError) as e:
        print(f"Error: Could not reach {base}/actuator/sentinel — {e}", file=sys.stderr)
        sys.exit(1)

    print("AI-Sentinel Stage 2 — Isolation Forest training monitor")
    print("=" * 60)
    print(f"Base URL:        {base}")
    print(f"Traffic:        {args.total_requests} requests @ {args.concurrency} workers")
    print(f"Poll interval:  {args.poll_interval}s")
    print()
    if not info.get("isolationForestEnabled"):
        print("Warning: isolationForestEnabled is false. Enable it in application.yaml and restart.", file=sys.stderr)
        print("Continuing to send traffic and poll anyway.", file=sys.stderr)
        print()

    requests_per_worker = (args.total_requests + args.concurrency - 1) // args.concurrency
    start = time.time()

    def run_worker(_):
        return send_traffic(
            base,
            args.traffic_endpoint,
            requests_per_worker,
            args.request_delay_ms,
            stop_event,
        )

    info = {}
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(run_worker, i) for i in range(args.concurrency)]
        last_poll = 0.0
        done = False

        while not done:
            now = time.time()
            if now - last_poll >= args.poll_interval:
                last_poll = now
                try:
                    info = get_sentinel_info(base)
                except (ConnectionError, OSError, json.JSONDecodeError) as e:
                    print(f"  [poll error] {e}")
                    info = {}

                loaded = info.get("isolationForestModelLoaded", False)
                version = info.get("isolationForestModelVersion", 0) or 0
                buffered = info.get("isolationForestBufferedSampleCount", 0)
                last_retrain = info.get("isolationForestLastRetrainTimeMillis") or 0
                last_retrain_str = time.strftime("%H:%M:%S", time.localtime(last_retrain / 1000)) if last_retrain else "—"

                print(f"  buffered={buffered}  modelLoaded={loaded}  modelVersion={version}  lastRetrain={last_retrain_str}")

                if loaded and version >= 1:
                    stop_event.set()
                    done = True
                    print()
                    print("Done: Isolation Forest model is trained and loaded.")
                    break

            time.sleep(0.5)
            if all(f.done() for f in futures):
                if not done:
                    stop_event.set()
                    total_sent = sum(f.result()[0] for f in futures)
                    total_errors = sum(f.result()[1] for f in futures)
                    print()
                    print(f"All {total_sent} requests sent ({total_errors} errors). Waiting for next poll...")
                    # One more poll after traffic ends
                    if now - last_poll >= args.poll_interval - 0.5:
                        continue
                done = True

    elapsed = time.time() - start
    results = [f.result() for f in futures]
    total_sent = sum(r[0] for r in results)
    total_errors = sum(r[1] for r in results)
    print(f"Total requests: {total_sent} (errors: {total_errors}) in {elapsed:.1f}s")

    # Final poll for exit status if we exited on traffic completion
    if not (info.get("isolationForestModelLoaded") and (info.get("isolationForestModelVersion") or 0) >= 1):
        try:
            info = get_sentinel_info(base)
        except (ConnectionError, OSError, json.JSONDecodeError):
            pass
    return 0 if (info.get("isolationForestModelLoaded") and (info.get("isolationForestModelVersion") or 0) >= 1) else 1


if __name__ == "__main__":
    sys.exit(main())
