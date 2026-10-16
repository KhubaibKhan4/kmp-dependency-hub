# Tutorial video

Published: <https://youtu.be/iO_Yv1gEgvw>

An ~87 s animated 3D tutorial: the problem (versions, source sets, engines, KSP, a broken iOS build), then the hub
(browse, multi-select, correct placement, platform safety, Gradle CLI, benefits).

| File | What it is |
|---|---|
| `script.json` | Narration per scene, captions, voice and speaking rate |
| `kit.js`, `scenes.js` | Three.js scenes; every frame is a pure function of time |
| `index.html` | Renders a time; open it via a local server for real-time preview |
| `build_voice.py` | Voiceover with macOS `say`; writes `build/timeline.json` (scene timings follow the narration) |
| `render.py` | Headless Chrome frame capture + ffmpeg encode |

```bash
python3 build_voice.py              # after editing script.json
python3 render.py stills 6,20,40    # review a few frames in build/stills
python3 render.py video 4           # full render → build/kmp-dependency-hub-tutorial.mp4
```

**Your own voice:** record one WAV per scene, name them like the files in `build/voice/`, run
`python3 render.py encode`. Scene lengths come from `build/timeline.json`; re-run `build_voice.py` if the new takes
are much longer, then re-render.
