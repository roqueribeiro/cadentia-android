#!/bin/bash
# Semeia o Gravador com um projeto de demonstração (três trilhas, takes
# sintéticos de 14 s) para o print de loja. Só na build de QA/debug: usa
# `run-as`, que exige app depurável. Nunca toca em dado de usuário de verdade.
#
#   scripts/store-seed-recorder.sh com.levelhard.cadentia.debug
set -euo pipefail
PKG="${1:-com.levelhard.cadentia.debug}"
TMP="$(mktemp -d)"
python3 - "$TMP" <<'PY'
import sys, wave, struct, math, json, random, uuid
out = sys.argv[1]
RATE = 48000
def take(path, secs, notes, decay=2.2, seed=1):
    rnd = random.Random(seed)
    n = int(RATE * secs); buf = bytearray()
    beat = 60 / 96
    for i in range(n):
        t = i / RATE
        k = int(t / beat); phase = t - k * beat
        f = notes[k % len(notes)]
        env = math.exp(-decay * phase) * (0.35 + 0.65 * (0.5 + 0.5 * math.sin(2 * math.pi * 0.1 * t)))
        v = env * (math.sin(2 * math.pi * f * t) + 0.35 * math.sin(2 * math.pi * 2 * f * t) + 0.02 * (rnd.random() - 0.5))
        buf += struct.pack('<h', int(max(-1, min(1, v * 0.8)) * 32767))
    w = wave.open(path, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(bytes(buf)); w.close()
take(f'{out}/take-violao.wav', 14, [196.0, 246.9, 293.7, 196.0, 220.0, 261.6, 329.6, 220.0], 3.0, 1)
take(f'{out}/take-baixo.wav', 14, [98.0, 98.0, 110.0, 110.0], 1.6, 2)
take(f'{out}/take-voz.wav', 12, [392.0, 440.0, 493.9, 440.0, 392.0, 349.2], 1.2, 3)
def clip(name, start, dur, fade=0.0):
    return {"id": str(uuid.uuid4()), "fileName": name, "start": start, "trimStart": 0.0, "duration": dur,
            "sourceDuration": dur, "gain": 1.0, "fadeIn": 0.0, "fadeOut": fade}
def track(name, clips, color, pan=0.0):
    return {"id": str(uuid.uuid4()), "name": name, "clips": clips, "volume": 1.0, "pan": pan,
            "muted": False, "soloed": False, "armed": False, "colorIndex": color}
project = {"tracks": [
    track("Violão", [clip("take-violao.wav", 0.0, 14.0, 0.8)], 0, -0.2),
    track("Baixo", [clip("take-baixo.wav", 0.0, 14.0)], 1, 0.0),
    track("Voz", [clip("take-voz.wav", 2.0, 12.0, 0.5)], 2, 0.15),
], "bpm": 96, "metronomeEnabled": False, "countInBars": 1}
json.dump(project, open(f'{out}/project.json', 'w'), ensure_ascii=False)
PY
adb shell run-as "$PKG" mkdir -p files/Recorder
for f in take-violao.wav take-baixo.wav take-voz.wav project.json; do
  adb push "$TMP/$f" /data/local/tmp/cadentia-seed-$f >/dev/null
  adb shell run-as "$PKG" cp /data/local/tmp/cadentia-seed-$f "files/Recorder/$f"
  adb shell rm -f /data/local/tmp/cadentia-seed-$f
done
rm -rf "$TMP"
echo "  gravador semeado (3 trilhas)"
