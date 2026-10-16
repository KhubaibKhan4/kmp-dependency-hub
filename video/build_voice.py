#!/usr/bin/env python3
"""Synthesises one narration clip per scene with macOS `say` and writes build/timeline.json."""
import json, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "build" / "voice"
LEAD_IN = 0.6  # seconds of picture before the first line

def duration(path):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)])
    return float(out.decode().strip())

def main():
    script = json.loads((ROOT / "script.json").read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    t = LEAD_IN
    scenes = []
    for i, scene in enumerate(script["scenes"]):
        aiff = OUT / f"{i:02d}-{scene['id']}.aiff"
        wav = aiff.with_suffix(".wav")
        subprocess.check_call(["say", "-v", script["voice"], "-r", str(script["rate"]), "-o", str(aiff), scene["text"]])
        subprocess.check_call(["ffmpeg", "-y", "-loglevel", "error", "-i", str(aiff), "-ar", "48000", "-ac", "1", str(wav)])
        d = duration(wav)
        start = t if i else 0.0
        voice_at = t
        end = t + d + scene["pad"]
        scenes.append({"id": scene["id"], "start": round(start, 3), "voiceAt": round(voice_at, 3), "voice": round(d, 3),
                       "end": round(end, 3), "caption": script["captions"][scene["id"]], "wav": wav.name})
        t = end
    timeline = {"fps": 30, "width": 1920, "height": 1080, "duration": round(t, 3), "scenes": scenes}
    (ROOT / "build" / "timeline.json").write_text(json.dumps(timeline, indent=2))
    for s in scenes:
        print(f"{s['id']:10s} {s['start']:6.2f} → {s['end']:6.2f}  (voice {s['voice']:.2f}s)")
    print(f"total {t:.1f}s")

if __name__ == "__main__":
    main()
