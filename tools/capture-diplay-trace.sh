#!/bin/sh
# Record a raw DiPlay protocol trace from the receiver phone, with no DiPlay code changes.
#
# DiPlay 0.2.22 hex-dumps every decrypted AirPlay control request (headers + body), SETUP replies,
# TEARDOWN bodies and event traffic when `log.tag.DiPlayTrace` is VERBOSE (CarPlayController.wantsTrace).
# The property is not persistent: it clears on reboot, or run with --off.
#
# Usage: tools/capture-diplay-trace.sh RECEIVER_SERIAL OUT.log   (Ctrl-C to stop)
#        tools/capture-diplay-trace.sh RECEIVER_SERIAL --off
# Then:  python3 tools/decode-diplay-trace.py OUT.log
set -eu
serial="${1:?receiver adb serial}"
out="${2:?output file or --off}"
if [ "$out" = "--off" ]; then
    adb -s "$serial" shell setprop log.tag.DiPlayTrace '""'
    echo "DiPlay trace off"
    exit 0
fi
adb -s "$serial" shell setprop log.tag.DiPlayTrace VERBOSE
adb -s "$serial" logcat -G 16M
adb -s "$serial" logcat -c
echo "DiPlay trace on ($(adb -s "$serial" shell getprop log.tag.DiPlayTrace)); connect the phone now. Writing $out, Ctrl-C to stop."
adb -s "$serial" logcat -v threadtime -s xcertplay-usb:V DiPlay-VideoStats:V > "$out"
