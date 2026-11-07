#!/usr/bin/env python3
"""Extract measured receiver-side CarPlay milestones; never export log payloads.

Usage: python3 tools/summarize-diplay-trace.py LOG > summary.json
Times are receiver log timestamps, not sender-side timings. Each network bring-up
is a separate attempt. A TCP probe alone never counts as a successful session.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path


def summarize(path):
    raw = path.read_bytes()
    attempts = []
    attempt = None
    for number, line in enumerate(raw.decode(errors="replace").splitlines(), 1):
        stamp = re.match(r"(\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})", line)
        if not stamp:
            continue
        _, hh, mm, ss, ms = stamp.groups()
        now = ((int(hh) * 60 + int(mm)) * 60 + int(ss)) * 1000 + int(ms)
        if "wireless hotspot backend=" in line:
            backend = re.search(r"backend=(.*?) iface=", line)
            attempt = {"network_ready_at": stamp.group(), "backend": backend[1] if backend else "unknown",
                       "milestones": {}, "requests": [], "initial_setup_keys": [],
                       "stream_types": [], "audio_profiles": [], "feedback_requests": 0,
                       "feedback_errors": [], "peer_model": None, "_request_keys": set()}
            attempts.append(attempt)
        if attempt is None:
            continue
        markers = {
            "start_session": "StartSession atNs=",
            "first_tcp": "first TCP atNs=",
            "record": "airplay rx RECORD ",
            "first_frame": "Video: first frame rendered",
            "tunnel_ready": "wireless iAP2 tunnel ready;",
            "bluetooth_handoff": "wireless handoff ready; closing Bluetooth",
            "feedback_error": "airplay handler failed POST /feedback",
        }
        for name, marker in markers.items():
            if marker in line and name not in attempt["milestones"]:
                attempt["milestones"][name] = {"at": stamp.group(), "ms_since_midnight": now,
                                                  "source_line": number}
        peer = re.search(r"Phone display peer model=(\S+)", line)
        if peer:
            attempt["peer_model"] = peer[1]
        if "airplay handler failed POST /feedback" in line:
            attempt["feedback_errors"].append({"at": stamp.group(), "source_line": number})
        request = re.search(r"airplay rx (\S+) (\S+) cseq=(\d+) body=(\d+)", line)
        if request:
            method, route, cseq, size = request.groups()
            key = (method, route, cseq, size)
            if key not in attempt["_request_keys"]:
                attempt["_request_keys"].add(key)
                if route == "/feedback":
                    attempt["feedback_requests"] += 1
                    continue
                # RTSP paths include session IDs; retain only the route kind.
                attempt["requests"].append({"method": method, "route": "rtsp-session" if route.startswith("rtsp:") else route,
                                            "cseq": int(cseq), "body_bytes": int(size), "source_line": number})
        keys = re.search(r"airplay SETUP keys=\[(.*?)\]", line)
        if keys and keys[1] != "streams" and not attempt["initial_setup_keys"]:
            attempt["initial_setup_keys"] = keys[1].split(", ")
        stream = re.search(r"airplay SETUP stream type=(\d+)", line)
        if stream and int(stream[1]) not in attempt["stream_types"]:
            attempt["stream_types"].append(int(stream[1]))
        if stream and int(stream[1]) in (100, 101, 102, 103):
            profile = {"type": int(stream[1])}
            for field in ("audioLatencyMs", "spf", "audioFormat"):
                value = re.search(r"\b" + field + r"=(\d+)", line)
                if value:
                    profile[field] = int(value[1])
            kind = re.search(r"audioType=([A-Za-z]+)", line)
            if kind:
                profile["audioType"] = kind[1]
            if profile not in attempt["audio_profiles"]:
                attempt["audio_profiles"].append(profile)
    for attempt in attempts:
        del attempt["_request_keys"]
        milestones = attempt["milestones"]
        attempt["success_record_and_frame"] = all(k in milestones for k in ("record", "first_frame"))
        attempt["durations_ms"] = {}
        for label, start, end in (("start_to_tcp", "start_session", "first_tcp"),
                                  ("start_to_record", "start_session", "record"),
                                  ("record_to_frame", "record", "first_frame"),
                                  ("start_to_frame", "start_session", "first_frame"),
                                  ("record_to_feedback_error", "record", "feedback_error")):
            if start in milestones and end in milestones:
                delta = milestones[end]["ms_since_midnight"] - milestones[start]["ms_since_midnight"]
                attempt["durations_ms"][label] = delta % 86_400_000
    return {"source_sha256": hashlib.sha256(raw).hexdigest(), "source_lines": len(raw.splitlines()),
            "measurement": "DiPlay receiver-side log timestamps; TCP probes alone are not successes",
            "attempts": attempts}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    result = summarize(args.log)
    if not result["attempts"]:
        parser.error("No DiPlay network bring-up markers found; provide the full capture")
    print(json.dumps(result, indent=2))
