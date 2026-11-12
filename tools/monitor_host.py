#!/usr/bin/env python3
"""Sample Mac and local Lettrine Docker metrics without exposing the daemon."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
from threading import Event
import time


ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("dashboard", "api", "postgres", "redis", "worker", "beat")
MIB = 1024 ** 2
STOP = Event()
UNITS = {
    "b": 1, "kib": 1024, "mib": MIB, "gib": 1024 * MIB, "tib": 1024 ** 2 * MIB,
    "kb": 10 ** 3, "mb": 10 ** 6, "gb": 10 ** 9, "tb": 10 ** 12,
    "k": 1024, "m": MIB, "g": 1024 * MIB, "t": 1024 ** 2 * MIB,
}


def command(*args, timeout=6):
    try:
        result = subprocess.run(
            args, cwd=ROOT, capture_output=True, text=True, check=True, timeout=timeout,
            env={**os.environ, "LC_ALL": "C"},
        )
        return result.stdout
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return ""


def memory_mib(value):
    match = re.match(r"^\s*([\d.]+)\s*([a-z]+)", value, re.I)
    if not match:
        return None
    factor = UNITS.get(match[2].lower())
    return round(float(match[1]) * factor / MIB, 1) if factor else None


def mac_metrics():
    snapshot = {}
    report = command("top", "-l", "2", "-s", "1", "-n", "0")
    idle = re.findall(r"CPU usage: .*?([\d.]+)% idle", report)
    if idle:
        snapshot["cpu_percent"] = round(min(100, max(0, 100 - float(idle[-1]))), 1)
    memory = re.findall(r"PhysMem:\s+([\d.]+\s*[KMGTP]B?) used", report, flags=re.I)
    if memory and (amount := memory_mib(memory[-1])) is not None:
        snapshot["memory_used_mb"] = amount
    total = command("sysctl", "-n", "hw.memsize").strip()
    if total.isdecimal():
        snapshot["memory_total_mb"] = round(int(total) / MIB, 1)
    return snapshot


def battery_metrics():
    report = command("pmset", "-g", "batt", timeout=3)
    battery = re.search(r"\b(\d{1,3})%;\s*([^;]+);", report)
    if not battery:
        return {}
    result = {"battery_percent": int(battery[1]), "charging": battery[2].strip().lower() == "charging"}
    source = re.search(r"Now drawing from '([^']+)'", report)
    if source:
        result["power_source"] = "ac" if source[1] == "AC Power" else "battery"
    return result


def json_rows(output):
    if not output.strip():
        return []
    if output.lstrip().startswith("["):
        try:
            return [item for item in json.loads(output) if isinstance(item, dict)]
        except (ValueError, TypeError):
            return []
    rows = []
    for line in output.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


def compose_metrics():
    services = {name: {"id": name, "status": "unknown"} for name in SERVICES}
    report = command("docker", "compose", "ps", "--all", "--format", "json")
    running = {}
    for row in json_rows(report):
        name = row.get("Service")
        container = row.get("ID")
        if name not in services or not isinstance(container, str) or not container:
            continue
        state, health = row.get("State"), row.get("Health")
        if state == "running":
            services[name]["status"] = health if health in ("healthy", "unhealthy", "starting") else "running"
            running[container[:12]] = name
        elif state in ("exited", "dead", "paused", "created", "removing"):
            services[name]["status"] = "stopped"
        elif state == "restarting":
            services[name]["status"] = "starting"
    if running:
        report = command("docker", "stats", "--no-stream", "--format", "{{json .}}", *running)
        for row in json_rows(report):
            container = row.get("ID") or row.get("Container")
            name = running.get(container[:12]) if isinstance(container, str) else None
            if name is None:
                continue
            cpu = str(row.get("CPUPerc", "")).rstrip("%")
            if re.fullmatch(r"[\d.]+", cpu):
                services[name]["cpu_percent"] = float(cpu)
            usage = str(row.get("MemUsage", "")).partition("/")[0]
            if (amount := memory_mib(usage)) is not None:
                services[name]["memory_mb"] = amount
    return list(services.values())


def collect(pool):
    mac = pool.submit(mac_metrics) if sys.platform == "darwin" else None
    battery = pool.submit(battery_metrics) if sys.platform == "darwin" else None
    compose = pool.submit(compose_metrics)
    host = {}
    if mac is not None:
        host.update(mac.result())
    if battery is not None:
        host.update(battery.result())
    return {"updated_at": time.time_ns() // 1_000_000, "host": host, "services": compose.result()}


def write_snapshot(destination, snapshot):
    fd, temporary = tempfile.mkstemp(prefix=".host-metrics-", dir=destination.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), 0o644)
            json.dump(snapshot, stream, ensure_ascii=False, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--watch", action="store_true", help="Sample until interrupted.")
    parser.add_argument("--interval", type=float, default=10, help="Seconds between samples.")
    parser.add_argument("--output", type=Path, default=ROOT / "results/host-metrics.json")
    args = parser.parse_args()
    if not 5 <= args.interval <= 600:
        parser.error("--interval must be between 5 and 600 seconds")
    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.with_suffix(".lock").open("a+", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Un collecteur est déjà actif pour ce fichier.", file=sys.stderr)
            return 0
        lock.seek(0)
        lock.truncate()
        lock.write(str(os.getpid()) + "\n")
        lock.flush()
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: STOP.set())
        with ThreadPoolExecutor(max_workers=3) as pool:
            while not STOP.is_set():
                started = time.monotonic()
                write_snapshot(destination, collect(pool))
                if not args.watch:
                    break
                STOP.wait(max(0, args.interval - (time.monotonic() - started)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
