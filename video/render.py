#!/usr/bin/env python3
"""Renders the video: serves this folder, drives headless Chrome workers, collects frames, encodes with ffmpeg.

  python3 render.py stills 2,12,30      # a few JPEG stills into build/stills for review
  python3 render.py video [workers]     # full render to build/kmp-dependency-hub-tutorial.mp4
"""
import json, os, subprocess, sys, threading, time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
FRAMES = BUILD / "frames"
STILLS = BUILD / "stills"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PORT = 8765
done = set()
lock = threading.Lock()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT), **kw)

    def log_message(self, *a):
        pass

    def do_POST(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if url.path == "/frame":
            (FRAMES / f"{int(q['i'][0]):05d}.jpg").write_bytes(body)
        elif url.path == "/still":
            (STILLS / f"t{float(q['t'][0]):06.2f}.jpg").write_bytes(body)
        elif url.path == "/done":
            with lock:
                done.add(q["worker"][0])
        self.send_response(204)
        self.end_headers()


def chrome(url, profile):
    return subprocess.Popen([
        CHROME, "--headless=new", f"--user-data-dir={profile}", "--window-size=1920,1080",
        "--use-angle=metal", "--enable-gpu-rasterization", "--ignore-gpu-blocklist",
        "--disable-background-timer-throttling", "--disable-renderer-backgrounding", "--no-first-run",
        "--autoplay-policy=no-user-gesture-required", url,
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def serve():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def wait_for(workers, procs, total=None, timeout=3600):
    start = time.time()
    while time.time() - start < timeout:
        with lock:
            if workers <= done:
                return True
        if total:
            n = len(list(FRAMES.glob("*.jpg")))
            print(f"\r  {n}/{total} frames  {time.time() - start:5.0f}s", end="", flush=True)
        if all(p.poll() is not None for p in procs):
            return False
        time.sleep(2)
    return False


def stills(times, extra=""):
    STILLS.mkdir(parents=True, exist_ok=True)
    server = serve()
    p = chrome(f"http://127.0.0.1:{PORT}/index.html?mode=stills&t={times}{extra}", BUILD / "chrome-stills")
    ok = wait_for({"stills"}, [p], timeout=600)
    p.terminate(); server.shutdown()
    print("stills ok" if ok else "stills FAILED")


def video(workers):
    tl = json.loads((BUILD / "timeline.json").read_text())
    total = int(tl["duration"] * tl["fps"])
    FRAMES.mkdir(parents=True, exist_ok=True)
    for f in FRAMES.glob("*.jpg"):
        f.unlink()
    server = serve()
    chunk = (total + workers - 1) // workers
    procs = []
    for w in range(workers):
        a, b = w * chunk, min(total, (w + 1) * chunk)
        procs.append(chrome(f"http://127.0.0.1:{PORT}/index.html?mode=render&from={a}&to={b}&worker={w}", BUILD / f"chrome-{w}"))
    ok = wait_for({str(w) for w in range(workers)}, procs, total)
    for p in procs:
        p.terminate()
    server.shutdown()
    print()
    n = len(list(FRAMES.glob("*.jpg")))
    if not ok or n < total:
        sys.exit(f"render incomplete: {n}/{total} frames")
    encode(tl)


def encode(tl):
    voice = BUILD / "voice"
    # Narration: each clip delayed to its scene; plus a soft generated pad underneath.
    inputs, filters, labels = [], [], []
    for i, s in enumerate(tl["scenes"]):
        inputs += ["-i", str(voice / s["wav"])]
        ms = int(s["voiceAt"] * 1000)
        filters.append(f"[{i + 1}:a]adelay={ms}|{ms},apad[v{i}]")
        labels.append(f"[v{i}]")
    dur = tl["duration"]
    n = len(tl["scenes"])
    pad = (f"aevalsrc='0.05*sin(2*PI*110*t)*(0.6+0.4*sin(2*PI*0.07*t))+0.035*sin(2*PI*164.81*t)+0.03*sin(2*PI*220*t)"
           f"+0.02*sin(2*PI*329.63*t)*(0.5+0.5*sin(2*PI*0.11*t))':s=48000:d={dur},"
           f"lowpass=f=900,afade=t=in:d=2,afade=t=out:st={dur - 2.5}:d=2.5,volume=0.55[pad]")
    filters.append(pad)
    filters.append(f"{''.join(labels)}amix=inputs={n}:normalize=0:duration=longest,atrim=0:{dur},volume=1.1[voice]")
    filters.append("[voice][pad]amix=inputs=2:normalize=0:duration=first,alimiter=limit=0.89:level=false[aout]")
    out = BUILD / "kmp-dependency-hub-tutorial.mp4"
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(tl["fps"]), "-i", str(FRAMES / "%05d.jpg"), *inputs,
           "-filter_complex", ";".join(filters), "-map", "0:v", "-map", "[aout]",
           "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
           "-c:a", "aac", "-b:a", "192k", "-shortest", str(out)]
    subprocess.check_call(cmd)
    print(f"wrote {out}")


if __name__ == "__main__":
    os.chdir(ROOT)
    if sys.argv[1] == "stills":
        stills(sys.argv[2], "&nocap=1" if "--clean" in sys.argv else "")
    elif sys.argv[1] == "video":
        video(int(sys.argv[2]) if len(sys.argv) > 2 else 4)
    elif sys.argv[1] == "encode":
        encode(json.loads((BUILD / "timeline.json").read_text()))
