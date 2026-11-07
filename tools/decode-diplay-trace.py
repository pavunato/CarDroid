#!/usr/bin/env python3
"""Decode the AirPlay control requests in a DiPlay trace (see tools/capture-diplay-trace.sh).

Prints, per control request (deduplicated by session + CSeq): method/route, length, whether the
logged hex is complete, and the decoded binary plist with every value's type. Pairing/auth bodies
(pair-setup, pair-verify, auth-setup) are opaque crypto and are shown by size only.

Identifiers (MAC-like strings, UUIDs, long IDs) are masked by default; pass --raw to show them.

Usage: python3 tools/decode-diplay-trace.py TRACE.log [--raw] [--route /command] [--json]
"""
import argparse
import json
import plistlib
import re

RX = re.compile(r"TRACE airplay control rx headers=\{(.*?)\} bodyHex=([0-9a-fA-F]*)")
REQ = re.compile(r"airplay rx (\S+) (\S+) cseq=(\d+) body=(\d+)")
MAC = re.compile(r"^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
UUID = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
OPAQUE = ("/pair-setup", "/pair-verify", "/auth-setup")
ID_KEYS = {"deviceID", "macAddress", "sessionUUID", "sessionCorrelationUUID", "clientUUID",
           "streamConnectionID", "seed", "channelID", "uuid", "name"}


def mask(key, value, raw):
    if raw:
        return value
    if isinstance(value, str) and (MAC.match(value) or UUID.match(value) or key in ID_KEYS):
        return f"<{key or 'id'} len={len(value)}>"
    if isinstance(value, int) and key in ID_KEYS:
        return f"<{key} int>"
    if isinstance(value, bytes):
        return f"<data {len(value)}B {value[:8].hex()}…>"
    return value


def typed(value, raw, key=None):
    if isinstance(value, dict):
        return {k: typed(v, raw, k) for k, v in value.items()}
    if isinstance(value, list):
        return [typed(v, raw, key) for v in value]
    return {"type": type(value).__name__, "value": mask(key, value, raw)}


def show(node, indent=2):
    pad = " " * indent
    if isinstance(node, dict) and set(node) == {"type", "value"}:
        return
    if isinstance(node, dict):
        if not node:
            print(f"{pad}(empty dict)")
        for k, v in node.items():
            if isinstance(v, dict) and set(v) == {"type", "value"}:
                print(f"{pad}{k}: {v['type']} = {v['value']}")
            else:
                print(f"{pad}{k}:")
                show(v, indent + 2)
    elif isinstance(node, list):
        if not node:
            print(f"{pad}(empty list)")
        for i, v in enumerate(node):
            if isinstance(v, dict) and set(v) == {"type", "value"}:
                print(f"{pad}[{i}] {v['type']} = {v['value']}")
            else:
                print(f"{pad}[{i}]")
                show(v, indent + 2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--raw", action="store_true", help="do not mask identifiers")
    ap.add_argument("--route", help="only requests whose route contains this text")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    pending = {}   # cseq -> (method, route) from the preceding "airplay rx" line
    seen = set()
    session = 0
    out = []
    for line in open(args.log, errors="replace"):
        r = REQ.search(line)
        if r:
            if r[3] == "0":
                session += 1
            pending[r[3]] = (r[1], r[2])
            continue
        m = RX.search(line)
        if not m:
            continue
        headers = dict(kv.split("=", 1) for kv in m[1].split(", ") if "=" in kv)
        cseq = headers.get("cseq", "?")
        if (session, cseq) in seen:
            continue
        seen.add((session, cseq))
        method, route = pending.get(cseq, ("?", "?"))
        route = re.sub(r"rtsp://[^ ]*", "rtsp-session", route)
        if args.route and args.route not in route:
            continue
        body = bytes.fromhex(m[2])
        length = int(headers.get("content-length", "0"))
        entry = {"session": session, "cseq": int(cseq) if cseq.isdigit() else cseq, "method": method,
                 "route": route, "length": length, "complete": len(body) == length}
        if body and not route.endswith(OPAQUE):
            try:
                entry["plist"] = typed(plistlib.loads(body), args.raw)
            except Exception:
                entry["hex"] = body.hex() if args.raw else f"<{len(body)}B not a plist>"
        out.append(entry)

    if args.json:
        print(json.dumps(out, indent=1, default=str))
        return
    for e in out:
        flag = "" if e["complete"] else "  ** TRUNCATED **"
        print(f"session {e['session']} cseq={e['cseq']} {e['method']} {e['route']} {e['length']}B{flag}")
        if "plist" in e:
            show(e["plist"], 4)
        elif "hex" in e:
            print(f"    {e['hex']}")


if __name__ == "__main__":
    main()
