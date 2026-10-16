// The ten scenes. Each builds its objects once; update(p, t, dur, cam) positions everything from scene progress.
import {
  THREE, C, SANS, MONO, clamp, seg, lerp, easeOut, easeInOut, easeOutBack, easeOutElastic, pulse,
  setA, canvasPlane, panel, rr, text, badge, hexA, libraryCard, code, windowChrome, chip, title, logo, glow,
} from './kit.js';

const V = (x, y, z) => new THREE.Vector3(x, y, z);

// Library data as shown by the plugin (klibs.io stars, Oct 2026).
const LIBS = {
  cmp: { name: 'compose-multiplatform', owner: 'JetBrains', stars: '19k', desc: 'Share UIs across iOS, Android, Desktop and Web', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], version: '1.9.3' },
  ktor: { name: 'Ktor Client', owner: 'ktorio', stars: '14k', desc: 'HTTP client with per-platform engines and JSON', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm', 'Native'], recipe: true, version: '3.6.0' },
  coroutines: { name: 'kotlinx.coroutines', owner: 'Kotlin', stars: '13k', desc: 'Coroutines, Flow and the Android main dispatcher', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], recipe: true, version: '1.11.0' },
  coil: { name: 'Coil 3', owner: 'coil-kt', stars: '11k', desc: 'Image loading for Compose Multiplatform', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], recipe: true, version: '3.6.3' },
  koin: { name: 'Koin', owner: 'InsertKoinIO', stars: '10k', desc: 'Pragmatic DI with Compose and ViewModel support', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], recipe: true, version: '4.2.2' },
  sqldelight: { name: 'SQLDelight', owner: 'sqldelight', stars: '6.9k', desc: 'Typesafe Kotlin APIs generated from SQL', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], recipe: true, version: '2.4.1' },
  arrow: { name: 'arrow', owner: 'arrow-kt', stars: '6.6k', desc: 'Functional companion to Kotlin\'s standard library', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], version: '2.2.0' },
  room: { name: 'Room (KMP)', owner: 'androidx', stars: '6.1k', desc: 'Jetpack Room with the bundled SQLite driver', platforms: ['Android', 'iOS', 'JVM'], recipe: true, version: '2.8.5' },
  serialization: { name: 'kotlinx.serialization', owner: 'Kotlin', stars: '5.9k', desc: 'Multiplatform serialization with JSON', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], recipe: true, version: '1.11.0' },
  voyager: { name: 'voyager', owner: 'adrielcafe', stars: '3.1k', desc: 'A pragmatic navigation library for Compose', platforms: ['Android', 'iOS', 'JVM', 'JS'], version: '1.1.0' },
  kermit: { name: 'Kermit', owner: 'touchlab', stars: '1.0k', desc: 'Touchlab\'s multiplatform logger', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], recipe: true, version: '2.2.0' },
  fuel: { name: 'fuel', owner: 'kittinunf', stars: '4.6k', desc: 'Easiest HTTP networking library for Kotlin', platforms: ['Android', 'iOS', 'JVM', 'Wasm'], version: '3.0.0' },
  wire: { name: 'wire', owner: 'square', stars: '4.4k', desc: 'Protocol Buffers for Android, Kotlin and Swift', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], version: '7.1.0' },
  apollo: { name: 'apollo-kotlin', owner: 'apollographql', stars: '4.0k', desc: 'Typed models from GraphQL queries', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], version: '5.2.0' },
  ktorfit: { name: 'Ktorfit', owner: 'Foso', stars: '2.1k', desc: 'HTTP client inspired by Retrofit, built on Ktor', platforms: ['Android', 'iOS', 'JVM', 'JS', 'Wasm'], version: '2.7.5' },
};
const TOP = ['cmp', 'ktor', 'coroutines', 'coil', 'koin', 'sqldelight', 'arrow', 'room', 'serialization', 'voyager', 'kermit'].map((k) => LIBS[k]);
const NET = ['ktor', 'coil', 'fuel', 'wire', 'apollo', 'ktorfit', 'koin', 'kermit'].map((k) => LIBS[k]);

/** Shared tool-window drawing used by the browse and select scenes. */
function drawToolWindow(ctx, W, H, s) {
  const z = s.zoom || 1;
  ctx.scale(z, z);
  const w = W / z, h = H / z;
  windowChrome(ctx, w, h, 'KMP Hub');
  // search
  rr(ctx, 26, 86, w - 52, 62, 14); ctx.fillStyle = C.panel2; ctx.fill();
  ctx.strokeStyle = s.searchFocus ? C.brand : C.line; ctx.lineWidth = 2.5; ctx.stroke();
  ctx.strokeStyle = C.muted; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(62, 117, 11, 0, Math.PI * 2); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(70, 125); ctx.lineTo(78, 133); ctx.stroke();
  text(ctx, s.query || 'Search KMP libraries on klibs.io and Maven', 92, 126, { size: 24, color: s.query ? C.text : C.muted });
  // browse dropdown
  text(ctx, 'Browse:', 28, 200, { size: 24, color: C.muted, weight: 600 });
  rr(ctx, 130, 166, w - 156, 54, 12); ctx.fillStyle = C.panel2; ctx.fill(); ctx.strokeStyle = C.line; ctx.stroke();
  const catW = text(ctx, s.category, 150, 201, { size: 25, weight: 600 });
  text(ctx, s.count, 160 + catW, 201, { size: 20, color: C.muted });
  ctx.fillStyle = C.muted; ctx.beginPath(); ctx.moveTo(w - 62, 188); ctx.lineTo(w - 46, 188); ctx.lineTo(w - 54, 198); ctx.fill();
  text(ctx, 'Versions: latest from Maven, checked just now', 28, 252, { size: 19, color: C.muted });
  text(ctx, 'Refresh', 470, 252, { size: 19, color: '#6ea8ff' });
  // list
  const top = 274, bottom = h - (s.footer ? 300 : 30);
  ctx.save(); ctx.beginPath(); ctx.rect(0, top, w, bottom - top); ctx.clip();
  let y = top - (s.scroll || 0);
  s.libs.forEach((lib, i) => {
    if (y > top - 140 && y < bottom) libraryCard(ctx, 6, y, w - 12, lib, { checked: s.checked?.[i], checkPop: s.pops?.[i] ?? 0, highlight: s.highlight?.[i] ?? 0 });
    y += 130;
  });
  if (s.loading) text(ctx, 'Loading more…', w / 2, Math.min(y + 50, bottom - 20), { size: 22, color: C.muted, align: 'center' });
  ctx.restore();
  if (s.footer) {
    const fy = h - 300;
    ctx.strokeStyle = C.line; ctx.beginPath(); ctx.moveTo(0, fy); ctx.lineTo(w, fy); ctx.stroke();
    text(ctx, s.footer.selected, 28, fy + 46, { size: 22, max: w - 140 });
    text(ctx, 'Clear', w - 28, fy + 46, { size: 22, color: '#6ea8ff', align: 'right' });
    text(ctx, 'Module:', 28, fy + 108, { size: 22, color: C.muted });
    rr(ctx, 210, fy + 74, w - 236, 50, 10); ctx.fillStyle = C.panel2; ctx.fill();
    text(ctx, ':composeApp', 228, fy + 107, { size: 22, weight: 600 });
    text(ctx, 'android, iosArm64, iosSimulatorArm64, desktop', 380, fy + 107, { size: 18, color: C.muted, max: w - 420 });
    text(ctx, 'Common artifacts to:', 28, fy + 168, { size: 22, color: C.muted });
    rr(ctx, 270, fy + 134, w - 296, 50, 10); ctx.fillStyle = C.panel2; ctx.fill();
    text(ctx, 'commonMain (recipe default)', 288, fy + 167, { size: 22 });
    const glowK = s.footer.buttonGlow || 0;
    rr(ctx, 26, fy + 206, w - 52, 66, 14);
    const g = ctx.createLinearGradient(0, 0, w, 0); g.addColorStop(0, C.brand); g.addColorStop(1, C.brand2);
    ctx.fillStyle = s.footer.enabled ? g : C.panel2; ctx.fill();
    if (glowK > 0) { ctx.shadowColor = C.brand; ctx.shadowBlur = 40 * glowK; ctx.fill(); ctx.shadowBlur = 0; }
    text(ctx, s.footer.button, w / 2, fy + 250, { size: 26, weight: 700, align: 'center', color: '#fff' });
  }
  if (s.cursor) {
    const { x, y: cy, click } = s.cursor;
    if (click > 0) { ctx.strokeStyle = hexA('#ffffff', 1 - click); ctx.lineWidth = 4; ctx.beginPath(); ctx.arc(x, cy, 20 + 50 * click, 0, Math.PI * 2); ctx.stroke(); }
    ctx.fillStyle = '#fff'; ctx.strokeStyle = '#000'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(x, cy); ctx.lineTo(x, cy + 44); ctx.lineTo(x + 11, cy + 33); ctx.lineTo(x + 20, cy + 52); ctx.lineTo(x + 28, cy + 48); ctx.lineTo(x + 19, cy + 30); ctx.lineTo(x + 34, cy + 30); ctx.closePath(); ctx.fill(); ctx.stroke();
  }
}

/** A labelled 3D slab representing a build-file section. */
function sectionTile(label, sub, color, { w = 3.3, h = 1.75 } = {}) {
  const state = { tint: 0, tintColor: C.green, items: [] };
  const g = panel(w, h, (ctx, cw, ch) => {
    rr(ctx, 0, 0, cw, ch, 30);
    const grad = ctx.createLinearGradient(0, 0, 0, ch);
    grad.addColorStop(0, '#262838'); grad.addColorStop(1, '#1b1c27');
    ctx.fillStyle = grad; ctx.fill();
    if (state.tint > 0) { ctx.fillStyle = hexA(state.tintColor, 0.28 * state.tint); ctx.fill(); }
    ctx.strokeStyle = state.tint > 0 ? hexA(state.tintColor, 0.5 + 0.5 * state.tint) : hexA(color, 0.8); ctx.lineWidth = 5;
    rr(ctx, 3, 3, cw - 6, ch - 6, 28); ctx.stroke();
    ctx.fillStyle = color; ctx.beginPath(); ctx.arc(46, 54, 13, 0, Math.PI * 2); ctx.fill();
    text(ctx, label, 72, 70, { size: 52, weight: 700, font: MONO, max: cw - 90 });
    if (sub) text(ctx, sub, 74, 116, { size: 32, color: C.muted });
    state.items.forEach((it, i) => {
      ctx.globalAlpha = it.a;
      text(ctx, (it.ok === false ? '✗ ' : '✓ ') + it.t, 40, 186 + i * 50, { size: 36, font: MONO, weight: 600, color: it.ok === false ? C.red : C.green });
      ctx.globalAlpha = 1;
    });
  }, { rim: false });
  g.userData.state = state;
  g.userData.redraw = () => g.userData.plane.redraw();
  return g;
}

function bubble(label, color = C.amber, r = 0.42) {
  const p = canvasPlane(r * 2, r * 2, (ctx, cw, ch) => {
    ctx.beginPath(); ctx.arc(cw / 2, ch / 2, cw / 2 - 6, 0, Math.PI * 2);
    ctx.fillStyle = color; ctx.fill();
    ctx.lineWidth = 6; ctx.strokeStyle = 'rgba(255,255,255,0.7)'; ctx.stroke();
    text(ctx, label, cw / 2, ch / 2 + 4, { size: cw * 0.55, weight: 800, color: '#1b1430', align: 'center', base: 'middle' });
  });
  return p.mesh;
}

function line3d(color = C.brand, opacity = 0.7) {
  const geo = new THREE.BufferGeometry().setFromPoints([V(0, 0, 0), V(1, 0, 0)]);
  const l = new THREE.Line(geo, new THREE.LineBasicMaterial({ color, transparent: true, opacity }));
  l.userData.set = (a, b) => { geo.setFromPoints([a, b]); };
  return l;
}

const arc = (from, to, t, lift = 1.2) => {
  const p = new THREE.Vector3().lerpVectors(from, to, t);
  p.y += Math.sin(Math.PI * t) * lift;
  p.z += Math.sin(Math.PI * t) * 0.8;
  return p;
};

// =========================================================================================================
export function buildScenes(scene) {
  const S = {};

  // ---- 1. Problem --------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const ideState = { cursor: 1 };
    const ide = panel(8.2, 5.0, (ctx, w, h) => {
      windowChrome(ctx, w, h, 'composeApp/build.gradle.kts');
      code(ctx, 60, 140, [
        { t: 'kotlin {' }, { t: '    androidTarget()' }, { t: '    iosArm64()' }, { t: '    iosSimulatorArm64()' },
        { t: '    jvm("desktop")' }, { t: '' }, { t: '    sourceSets {' }, { t: '        commonMain.dependencies {' },
        { t: '            // networking… but how?' }, { t: '        }' }, { t: '    }' }, { t: '}' },
      ], { size: 30, lh: 46, width: w - 90 });
      if (ideState.cursor > 0.5) { ctx.fillStyle = C.text; ctx.fillRect(578, 486, 4, 36); }
    });
    g.add(ide);
    const tabs = [
      ['Ktor docs', 'Client engines per platform'], ['klibs.io', 'Search: ktor'], ['Maven Central', 'io.ktor:ktor-client-core'],
      ['GitHub README', 'Installation'], ['Blog', 'KMP networking setup'],
    ].map(([t1, t2], i) => {
      const p = panel(3.0, 1.75, (ctx, w, h) => {
        rr(ctx, 0, 0, w, h, 22); ctx.fillStyle = '#f4f5fb'; ctx.fill();
        ctx.fillStyle = '#dfe2ee'; rr(ctx, 0, 0, w, 54, 22); ctx.fill(); ctx.fillRect(0, 30, w, 24);
        text(ctx, t1, 26, 38, { size: 26, weight: 700, color: '#2b2d3a' });
        text(ctx, t2, 26, 98, { size: 24, weight: 600, color: '#4a4f66' });
        [140, 178, 216, 254, 292].forEach((y, j) => { ctx.fillStyle = '#d3d6e4'; rr(ctx, 26, y, (w - 60) * (0.55 + 0.4 * ((i * 7 + j * 3) % 5) / 5), 16, 8); ctx.fill(); });
      }, { rim: false, shadow: 0.5 });
      g.add(p);
      return p;
    });
    const tabHome = [V(-6.0, 2.4, 1.0), V(6.0, 2.6, 0.6), V(-6.2, -1.6, 1.4), V(6.1, -1.3, 1.2), V(0.2, 3.8, -1.0)];
    const qs = [V(-4.3, 3.4, 2.0), V(4.4, 3.5, 1.6), V(-4.6, -0.6, 2.4), V(4.6, -0.2, 2.2), V(1.6, 4.4, 0)].map((p) => {
      const b = bubble('?'); b.position.copy(p); g.add(b); return b;
    });
    S.problem = {
      group: g,
      update(p, t, dur, cam) {
        ide.position.set(0, -0.3, 0);
        ide.rotation.set(-0.06, Math.sin(t * 0.4) * 0.05, 0);
        setA(ide, easeOut(seg(p, 0, 0.12)));
        ideState.cursor = (Math.floor(t * 2) % 2 === 0) ? 1 : 0;
        ide.userData.plane.redraw();
        tabs.forEach((tab, i) => {
          const k = easeOut(seg(p, 0.12 + i * 0.07, 0.4 + i * 0.07));
          const home = tabHome[i];
          tab.position.set(lerp(home.x * 1.6, home.x, k), home.y + Math.sin(t * 1.3 + i) * 0.08, lerp(-14, home.z, k));
          tab.rotation.set(0.05 * Math.sin(t + i), (home.x > 0 ? -1 : 1) * 0.35, 0.03 * Math.cos(t * 0.8 + i));
          setA(tab, k);
        });
        qs.forEach((q, i) => {
          const k = seg(p, 0.55 + i * 0.06, 0.75 + i * 0.06);
          q.scale.setScalar(Math.max(0.001, easeOutElastic(k)));
          q.rotation.z = Math.sin(t * 2 + i) * 0.15;
          setA(q, k > 0 ? 1 : 0);
        });
        cam.position.set(Math.sin(t * 0.3) * 0.4, 0.3, lerp(15, 13, easeInOut(p)));
        cam.lookAt(0, 0.4, 0);
      },
    };
  }

  // ---- 2. Pain -------------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const versions = ['3.2.0', '3.6.0', '3.11.0-beta1', '2.3.12', '3.5.1'];
    let vi = 0;
    const versionChip = canvasPlane(7.2, 1.25, (ctx, w, h) => {
      rr(ctx, 6, 6, w - 12, h - 12, 40); ctx.fillStyle = '#1e1f2b'; ctx.fill(); ctx.strokeStyle = C.amber; ctx.lineWidth = 5; ctx.stroke();
      const x = text(ctx, 'io.ktor:ktor-client-core:', 50, h / 2 + 16, { size: 46, font: MONO, weight: 600 });
      text(ctx, versions[vi] + '  ?', 50 + x, h / 2 + 16, { size: 46, font: MONO, weight: 800, color: C.amber });
    });
    g.add(versionChip.mesh);
    const tiles = [
      sectionTile('commonMain', 'shared code', C.brand),
      sectionTile('androidMain', 'Android only', C.android),
      sectionTile('iosMain', 'iOS only', C.ios),
      sectionTile('build.gradle.kts', 'root plugins', C.amber),
    ];
    const tilePos = [V(-5.4, -0.9, 0), V(-1.8, -0.9, 0), V(1.8, -0.9, 0), V(5.4, -0.9, 0)];
    tiles.forEach((t) => g.add(t));
    const flying = [
      { c: chip('ktor-client-core', { color: C.brand }), to: 0, at: 0.17, item: 'ktor-client-core' },
      { c: chip('ktor-client-okhttp', { color: '#3a8f5c' }), to: 1, at: 0.25, item: 'okhttp' },
      { c: chip('ktor-client-darwin', { color: '#5f6f7a' }), to: 2, at: 0.33, item: 'darwin' },
      { c: chip('kotlinx-serialization', { color: '#c07a18', sub: 'plugin' }), to: 3, at: 0.42, item: 'serialization' },
    ];
    flying.forEach((f) => g.add(f.c));
    const room = chip('room-compiler', { color: '#6a4bd6', sub: 'KSP' });
    g.add(room);
    const ksp = ['kspAndroid', 'kspIosArm64', 'kspIosSimulatorArm64', 'kspDesktop'].map((n) => { const c = chip(n, { color: '#4e3bb0' }); g.add(c); return c; });
    const hilt = chip('hilt-android', { color: '#c62e4a', sub: 'Android only' });
    g.add(hilt);
    const failed = panel(7.4, 2.0, (ctx, w, h) => {
      rr(ctx, 0, 0, w, h, 26); ctx.fillStyle = '#2a1018'; ctx.fill(); ctx.strokeStyle = C.red; ctx.lineWidth = 6; ctx.stroke();
      text(ctx, 'BUILD FAILED', 50, 112, { size: 84, weight: 900, color: C.red });
      text(ctx, 'Could not resolve com.google.dagger:hilt-android', 54, 200, { size: 30, font: MONO, color: '#ffc2cc' });
      text(ctx, 'for target iosArm64 (commonMain)', 54, 244, { size: 30, font: MONO, color: '#ffc2cc' });
    }, { rim: false });
    g.add(failed);
    S.pain = {
      group: g,
      update(p, t, dur, cam) {
        // a) which version?
        const va = seg(p, 0, 0.04) * (1 - seg(p, 0.13, 0.17));
        vi = Math.floor(t * 4) % versions.length;
        versionChip.redraw();
        versionChip.mesh.position.set(0, 2.6 + (1 - easeOut(seg(p, 0, 0.05))) * 1.5, 1);
        setA(versionChip.mesh, va);
        // b) tiles + chips
        tiles.forEach((tile, i) => {
          const k = easeOutBack(seg(p, 0.1 + i * 0.025, 0.2 + i * 0.025));
          tile.position.copy(tilePos[i]).add(V(0, (1 - k) * -3, 0));
          tile.rotation.set(-0.12, (tilePos[i].x) * -0.04, 0);
          setA(tile, seg(p, 0.1 + i * 0.025, 0.16 + i * 0.025));
        });
        const st = tiles.map((tl) => tl.userData.state);
        st.forEach((s) => { s.items = []; s.tint = 0; });
        flying.forEach((f) => {
          const k = easeInOut(seg(p, f.at, f.at + 0.08));
          const from = V(-7, 4, 2), to = tilePos[f.to].clone().add(V(0, 0.2, 0.3));
          f.c.position.copy(arc(from, to, k, 1.5));
          f.c.scale.setScalar(lerp(1, 0.6, k));
          setA(f.c, seg(p, f.at, f.at + 0.01) * (1 - seg(p, f.at + 0.075, f.at + 0.09)));
          if (p > f.at + 0.08) st[f.to].items.push({ t: f.item, a: seg(p, f.at + 0.08, f.at + 0.1) });
        });
        // c) KSP for every target
        const rk = seg(p, 0.54, 0.58) * (1 - seg(p, 0.7, 0.73));
        room.position.set(0, 2.9, 1.5);
        setA(room, rk);
        ksp.forEach((c, i) => {
          const k = easeOutBack(seg(p, 0.58 + i * 0.02, 0.64 + i * 0.02));
          c.position.copy(new THREE.Vector3().lerpVectors(V(0, 2.9, 1.5), V(-5.4 + i * 3.6, 1.6, 1.2), k));
          c.scale.setScalar(0.8);
          setA(c, seg(p, 0.58 + i * 0.02, 0.6 + i * 0.02) * (1 - seg(p, 0.7, 0.73)));
        });
        // d) Android-only into commonMain breaks iOS
        const hk = easeInOut(seg(p, 0.72, 0.8));
        hilt.position.copy(arc(V(7, 4, 2), tilePos[0].clone().add(V(0, 0.3, 0.4)), hk, 1.8));
        setA(hilt, seg(p, 0.72, 0.73) * (1 - seg(p, 0.8, 0.82)));
        if (p > 0.8) {
          st[0].items.push({ t: 'hilt-android', a: 1, ok: false });
          st[0].tint = seg(p, 0.8, 0.83); st[0].tintColor = C.red;
          st[2].tint = seg(p, 0.82, 0.86); st[2].tintColor = C.red;
          st[2].items.push({ t: 'can\'t resolve', a: seg(p, 0.83, 0.86), ok: false });
        }
        tiles.forEach((tl) => tl.userData.redraw());
        const shake = p > 0.82 && p < 0.9 ? Math.sin(t * 60) * 0.08 * (1 - seg(p, 0.82, 0.9)) : 0;
        tiles[2].position.x += shake;
        const fk = easeOutBack(seg(p, 0.85, 0.93));
        failed.position.set(0, lerp(-6, 2.4, fk), 2.2);
        failed.rotation.x = lerp(0.6, -0.05, fk);
        setA(failed, seg(p, 0.85, 0.88));
        cam.position.set(Math.sin(t * 0.25) * 0.6 + shake * 2, 0.6, 14);
        cam.lookAt(0, 0.6, 0);
      },
    };
  }

  // ---- 3. Intro ------------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const L = logo();
    g.add(L);
    const name = title('KMP Dependency Hub', { size: 130, w: 13, h: 1.7 });
    const sub = title('Every Kotlin Multiplatform dependency, in one place — inside Android Studio', { size: 46, w: 13, h: 0.8, gradient: false, color: '#c9cbe0', weight: 500 });
    g.add(name.mesh, sub.mesh);
    S.intro = {
      group: g,
      update(p, t, dur, cam) {
        L.userData.assemble(seg(p, 0, 0.6));
        L.position.set(0, lerp(0.4, 1.3, easeInOut(seg(p, 0.55, 0.75))), 0);
        L.scale.setScalar(lerp(1.15, 0.9, easeInOut(seg(p, 0.55, 0.75))));
        L.rotation.set(0.15 * Math.sin(t * 0.7), 0.5 * Math.sin(t * 0.6) + lerp(1.2, 0, easeOut(seg(p, 0, 0.5))), 0);
        const nk = easeOut(seg(p, 0.6, 0.78));
        name.mesh.position.set(0, lerp(-2.2, -1.75, nk), 1);
        setA(name.mesh, nk);
        const sk = easeOut(seg(p, 0.72, 0.9));
        sub.mesh.position.set(0, lerp(-3.1, -2.85, sk), 1);
        setA(sub.mesh, sk);
        cam.position.set(Math.sin(t * 0.35) * 1.2, 0.5, 11.5);
        cam.lookAt(0, -0.2, 0);
      },
    };
  }

  // ---- 4. Browse -----------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const state = { libs: TOP, category: 'Top libraries', count: '4,783', scroll: 0, zoom: 1.32 };
    const tw = panel(6.0, 8.6, (ctx, w, h) => drawToolWindow(ctx, w, h, state));
    g.add(tw);
    const sources = [['klibs.io', '4,800 KMP projects', C.brand], ['Maven Central', 'latest stable versions', '#c07a18'], ['Google Maven', 'AndroidX & Jetpack', '#2f8f5b']]
      .map(([n, s, c], i) => {
        const node = new THREE.Group();
        const orb = new THREE.Mesh(new THREE.SphereGeometry(0.42, 48, 32), new THREE.MeshPhysicalMaterial({ color: c, emissive: c, emissiveIntensity: 0.55, roughness: 0.25, clearcoat: 1 }));
        const label = canvasPlane(3.4, 0.9, (ctx, w, h) => {
          text(ctx, n, 0, 70, { size: 54, weight: 800 });
          text(ctx, s, 0, 120, { size: 30, color: C.muted });
        });
        label.mesh.position.set(2.25, -0.05, 0);
        node.add(orb, label.mesh);
        const l = line3d(c, 0.8);
        g.add(node, l);
        return { node, orb, line: l };
      });
    const cats = ['Networking', 'Database', 'Dependency injection', 'Navigation', 'Images', 'Serialization', 'UI components', 'Logging', 'Testing', 'Charts', 'AI & LLM']
      .map((n, i) => { const c = chip(n, { color: i % 2 ? '#4a3bb8' : '#2b5ec8', w: 0.16 * n.length + 0.9 }); g.add(c); return c; });
    S.browse = {
      group: g,
      update(p, t, dur, cam) {
        const k = easeOut(seg(p, 0, 0.15));
        tw.position.set(lerp(-7, -3.0, k), -0.15, 0);
        tw.rotation.set(0, lerp(0.8, 0.26, k), 0);
        setA(tw, seg(p, 0, 0.08));
        const netK = seg(p, 0.6, 0.62);
        state.libs = netK > 0 ? NET : TOP;
        state.category = netK > 0 ? 'Networking' : 'Top libraries';
        state.count = netK > 0 ? '124' : '4,783';
        state.scroll = netK > 0 ? lerp(0, 260, easeInOut(seg(p, 0.66, 0.95))) : lerp(0, 620, easeInOut(seg(p, 0.15, 0.55)));
        state.loading = netK === 0 && p > 0.42 && p < 0.58;
        tw.userData.plane.redraw();
        const twAnchor = tw.position.clone().add(V(1.4, 1.2, 0.2));
        sources.forEach((s, i) => {
          const sk = easeOutBack(seg(p, 0.12 + i * 0.06, 0.3 + i * 0.06));
          s.node.position.set(2.0, 2.6 - i * 1.55, lerp(-4, 0.5, sk));
          s.orb.scale.setScalar(1 + 0.08 * Math.sin(t * 3 + i));
          setA(s.node, seg(p, 0.12 + i * 0.06, 0.2 + i * 0.06));
          s.line.userData.set(s.node.position.clone().add(V(-0.45, 0, 0)), twAnchor.clone().add(V(0, -i * 0.9, 0)));
          setA(s.line, seg(p, 0.25 + i * 0.06, 0.35 + i * 0.06) * (0.5 + 0.5 * pulse(t, 1.2 + i * 0.3)));
        });
        cats.forEach((c, i) => {
          const ang = (i / cats.length) * Math.PI * 2 + t * 0.25;
          const ck = seg(p, 0.35 + i * 0.015, 0.5 + i * 0.015);
          c.position.set(4.6 + Math.cos(ang) * 2.6, -2.0 + Math.sin(ang) * 0.9, Math.sin(ang) * 2.2 - 1);
          c.scale.setScalar(0.62);
          setA(c, ck * (0.55 + 0.45 * (Math.sin(ang) + 1) / 2));
          c.lookAt(cam.position);
        });
        cam.position.set(lerp(1.2, 0.4, p), 0.2, 12.4);
        cam.lookAt(0.4, -0.1, 0);
      },
    };
  }

  // ---- 5. Select -----------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const libs = [LIBS.ktor, LIBS.koin, LIBS.room, LIBS.coil];
    const ZOOM = 1.25;
    const state = { libs, category: 'Top libraries', count: '4,783', checked: [], pops: [], footer: null, zoom: ZOOM };
    const tw = panel(5.8, 6.3, (ctx, w, h) => drawToolWindow(ctx, w, h, state));
    g.add(tw);
    S.select = {
      group: g,
      update(p, t, dur, cam) {
        tw.position.set(0, 0.45, 0);
        tw.rotation.set(0, lerp(0.25, 0.08, easeOut(p)), 0);
        const ticks = [0.12, 0.28, 0.44];
        state.checked = ticks.map((a) => p > a).concat([false]);
        state.pops = ticks.map((a) => seg(p, a, a + 0.08)).concat([0]);
        state.highlight = ticks.map((a) => (p > a ? 1 - seg(p, a + 0.08, a + 0.3) * 0.6 : 0)).concat([0]);
        const n = state.checked.filter(Boolean).length;
        const names = ['Ktor Client', 'Koin', 'Room (KMP)'].slice(0, n);
        state.footer = {
          selected: n ? `${n} selected: ${names.join(', ')}` : 'Tick libraries to add them',
          button: n ? `Add ${n} to Module…` : 'Add to Module', enabled: n > 0, buttonGlow: seg(p, 0.6, 0.72) * (0.6 + 0.4 * pulse(t, 2)),
        };
        // cursor: visits each checkbox, then the button
        const w = 5.8 * 230 / ZOOM, h = 6.3 * 230 / ZOOM;
        const pts = [[60, 338], [60, 468], [60, 598], [w / 2, h - 300 + 238]];
        const legs = [[0, 0.1], [0.16, 0.26], [0.32, 0.42], [0.55, 0.72]];
        let cx = pts[0][0] + 200, cy = pts[0][1] + 300;
        legs.forEach(([a, b], i) => {
          const k = easeInOut(seg(p, a, b));
          const prev = i === 0 ? [pts[0][0] + 200, pts[0][1] + 300] : pts[i - 1];
          if (p >= a) { cx = lerp(prev[0], pts[i][0], k); cy = lerp(prev[1], pts[i][1], k); }
        });
        state.cursor = { x: cx + 10, y: cy, click: seg(p, 0.76, 0.9) };
        tw.userData.plane.redraw();
        cam.position.set(0.6, 0.1, lerp(11.2, 10.2, easeInOut(p)));
        cam.lookAt(0, 0.1, 0);
      },
    };
  }

  // ---- 6. Placement --------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const toml = [
      { t: '[versions]' }, { t: 'kotlin = "2.4.20"' }, { t: 'ktor = "3.6.0"', add: 1, at: 0.06 }, { t: 'koin = "4.2.2"', add: 1, at: 0.08 }, { t: 'room = "2.8.5"', add: 1, at: 0.1 }, { t: 'ksp = "2.3.12"', add: 1, at: 0.12 },
      { t: '' }, { t: '[libraries]' }, { t: 'ktor-client-core = { module = … }', add: 1, at: 0.14 }, { t: 'ktor-client-okhttp = { module = … }', add: 1, at: 0.16 },
      { t: 'ktor-client-darwin = { module = … }', add: 1, at: 0.18 }, { t: 'koin-bom = { module = … }', add: 1, at: 0.2 }, { t: 'androidx-room-runtime = …', add: 1, at: 0.22 },
      { t: '' }, { t: '[plugins]' }, { t: 'ksp = { id = "com.google.devtools.ksp" }', add: 1, at: 0.68 }, { t: 'androidx-room = { id = "androidx.room" }', add: 1, at: 0.7 },
    ];
    const mod = [
      { t: 'plugins {' }, { t: '    alias(libs.plugins.kotlinMultiplatform)' }, { t: '    alias(libs.plugins.ksp)', add: 1, at: 0.72 }, { t: '    alias(libs.plugins.androidx.room)', add: 1, at: 0.74 }, { t: '}' },
      { t: 'kotlin {' }, { t: '    sourceSets {' }, { t: '        commonMain.dependencies {' },
      { t: '            implementation(libs.ktor.client.core)', add: 1, at: 0.3 }, { t: '            implementation(project.dependencies.platform(libs.koin.bom))', add: 1, at: 0.33 },
      { t: '            implementation(libs.androidx.room.runtime)', add: 1, at: 0.36 }, { t: '        }' },
      { t: '        androidMain.dependencies {' }, { t: '            implementation(libs.ktor.client.okhttp)', add: 1, at: 0.41 }, { t: '        }' },
      { t: '        iosMain.dependencies {', add: 1, at: 0.45 }, { t: '            implementation(libs.ktor.client.darwin)', add: 1, at: 0.47 }, { t: '        }', add: 1, at: 0.47 },
      { t: '    }' }, { t: '}' },
      { t: 'dependencies {', add: 1, at: 0.56 }, { t: '    add("kspAndroid", libs.androidx.room.compiler)', add: 1, at: 0.58 },
      { t: '    add("kspIosArm64", libs.androidx.room.compiler)', add: 1, at: 0.6 }, { t: '    add("kspDesktop", libs.androidx.room.compiler)', add: 1, at: 0.62 }, { t: '}', add: 1, at: 0.62 },
    ];
    const root = [
      { t: 'plugins {' }, { t: '    alias(libs.plugins.kotlinMultiplatform) apply false' },
      { t: '    alias(libs.plugins.ksp) apply false', add: 1, at: 0.76 }, { t: '    alias(libs.plugins.androidx.room) apply false', add: 1, at: 0.78 }, { t: '}' },
    ];
    let P = 0;
    const reveal = (lines) => lines.map((l) => ({ ...l, a: l.at === undefined ? 1 : seg(P, l.at, l.at + 0.025) }));
    const pToml = panel(4.9, 4.2, (ctx, w, h) => { windowChrome(ctx, w, h, 'gradle/libs.versions.toml'); code(ctx, 40, 122, reveal(toml), { size: 30, lh: 46, width: w - 40 }); });
    const pMod = panel(6.6, 6.0, (ctx, w, h) => { windowChrome(ctx, w, h, 'composeApp/build.gradle.kts'); code(ctx, 40, 122, reveal(mod), { size: 30, lh: 45, width: w - 40 }); });
    const pRoot = panel(5.2, 1.9, (ctx, w, h) => { windowChrome(ctx, w, h, 'build.gradle.kts'); code(ctx, 40, 122, reveal(root), { size: 30, lh: 46, width: w - 40 }); });
    const dialog = panel(5.4, 2.4, (ctx, w, h) => {
      rr(ctx, 0, 0, w, h, 24); ctx.fillStyle = '#20212c'; ctx.fill(); ctx.strokeStyle = C.brand; ctx.lineWidth = 4; ctx.stroke();
      text(ctx, 'KMP Hub: Add Dependencies', 40, 70, { size: 36, weight: 700 });
      text(ctx, '3 files · 31 lines added · review the diff', 40, 122, { size: 26, color: C.muted });
      text(ctx, 'Show diff…', 40, 190, { size: 28, color: '#6ea8ff' });
      rr(ctx, w - 470, h - 120, 430, 82, 16); const gr = ctx.createLinearGradient(w - 470, 0, w, 0); gr.addColorStop(0, C.brand); gr.addColorStop(1, C.brand2); ctx.fillStyle = gr; ctx.fill();
      text(ctx, 'Apply 3 File Changes', w - 255, h - 66, { size: 30, weight: 700, align: 'center', color: '#fff' });
    });
    g.add(pToml, pMod, pRoot, dialog);
    S.placement = {
      group: g,
      update(p, t, dur, cam) {
        P = p;
        const k = easeOut(seg(p, 0, 0.1));
        pToml.position.set(-6.4, 0.6, lerp(-3, -0.4, k)); pToml.rotation.set(0, 0.28, 0);
        pMod.position.set(0, 0, lerp(-2, 0, k)); pMod.rotation.set(0, 0, 0);
        pRoot.position.set(6.6, 1.6, lerp(-3, -0.4, k)); pRoot.rotation.set(0, -0.28, 0);
        [pToml, pMod, pRoot].forEach((x) => { setA(x, seg(p, 0, 0.06)); x.userData.plane.redraw(); });
        const dk = easeOutBack(seg(p, 0.84, 0.94));
        dialog.position.set(0, lerp(-7, -1.4, dk), 3.2);
        dialog.rotation.x = lerp(0.5, -0.04, dk);
        setA(dialog, seg(p, 0.84, 0.87));
        // camera visits the file being written
        // Pure function of p (frames render out of order in parallel): catalog → module file → root file → dialog.
        const tx = lerp(lerp(-6.4, 0, easeInOut(seg(p, 0.22, 0.3))), 6.6, easeInOut(seg(p, 0.64, 0.72)));
        const target = lerp(tx, 0, easeInOut(seg(p, 0.8, 0.88)));
        const z = lerp(10.6, 12.8, easeInOut(seg(p, 0.8, 0.88)));
        cam.position.set(target * 0.8 + Math.sin(t * 0.3) * 0.15, 0.3, z);
        cam.lookAt(target, 0.1, 0);
      },
    };
  }

  // ---- 7. Safety -----------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const tiles = [sectionTile('androidMain', 'Android only', C.android), sectionTile('commonMain', 'android + iOS + desktop', C.brand), sectionTile('iosMain', 'iOS only', C.ios)];
    const pos = [V(-4.2, -1.3, 0), V(0, -1.3, 0), V(4.2, -1.3, 0)];
    tiles.forEach((t) => g.add(t));
    const shield = new THREE.Mesh(new THREE.CircleGeometry(1.5, 6), new THREE.MeshBasicMaterial({ color: '#7fb2ff', transparent: true, opacity: 0.22, side: THREE.DoubleSide, depthWrite: false }));
    const ring = new THREE.Mesh(new THREE.RingGeometry(1.45, 1.6, 6), new THREE.MeshBasicMaterial({ color: '#9cc9ff', transparent: true, opacity: 0.9, side: THREE.DoubleSide, depthWrite: false }));
    const wave = new THREE.Mesh(new THREE.RingGeometry(1.5, 1.62, 48), new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.8, depthWrite: false }));
    g.add(shield, ring, wave);
    const hilt = chip('hilt-android', { color: '#c62e4a', sub: 'Android only' });
    g.add(hilt);
    const msg = panel(6.6, 1.3, (ctx, w, h) => {
      rr(ctx, 0, 0, w, h, 24); ctx.fillStyle = '#251a10'; ctx.fill(); ctx.strokeStyle = C.amber; ctx.lineWidth = 4; ctx.stroke();
      text(ctx, '⚠  Hilt doesn\'t publish for iOS', 40, 78, { size: 40, weight: 700, color: '#ffd68a' });
      text(ctx, 'Pick a platform source set: androidMain', 40, 132, { size: 30, color: '#f3dcb4' });
    }, { rim: false });
    g.add(msg);
    S.safety = {
      group: g,
      update(p, t, dur, cam) {
        tiles.forEach((tile, i) => {
          tile.position.copy(pos[i]); tile.rotation.set(-0.1, -pos[i].x * 0.05, 0);
          setA(tile, seg(p, 0, 0.08));
          const s = tile.userData.state; s.items = []; s.tint = 0;
        });
        const shieldK = easeOutBack(seg(p, 0.08, 0.2));
        shield.position.set(0, 0.2, 1.6); ring.position.copy(shield.position);
        shield.scale.setScalar(Math.max(0.001, shieldK)); ring.scale.copy(shield.scale);
        shield.rotation.z = ring.rotation.z = t * 0.3;
        const hit = seg(p, 0.33, 0.37);
        shield.material.opacity = 0.22 + 0.5 * (hit > 0 && hit < 1 ? 1 - hit : 0) * Math.sin(Math.PI * hit);
        setA(shield, seg(p, 0.08, 0.14)); setA(ring, seg(p, 0.08, 0.14));
        const wk = seg(p, 0.35, 0.5);
        wave.position.copy(shield.position); wave.scale.setScalar(1 + wk * 1.6); setA(wave, wk > 0 && wk < 1 ? 1 - wk : 0);
        // chip: approach, bounce off the shield, then go to androidMain
        let cp;
        if (p < 0.35) cp = arc(V(-7, 4.5, 3), V(0, 0.4, 2.0), easeInOut(seg(p, 0.12, 0.35)), 0.8);
        else if (p < 0.55) cp = arc(V(0, 0.4, 2.0), V(-2.6, 2.4, 2.6), easeOut(seg(p, 0.35, 0.55)), 0.4);
        else cp = arc(V(-2.6, 2.4, 2.6), pos[0].clone().add(V(0, 0.3, 0.5)), easeInOut(seg(p, 0.58, 0.78)), 1.0);
        hilt.position.copy(cp);
        hilt.rotation.z = p > 0.35 && p < 0.5 ? Math.sin(t * 25) * 0.08 : 0;
        setA(hilt, seg(p, 0.12, 0.14) * (1 - seg(p, 0.77, 0.8)));
        const mk = easeOutBack(seg(p, 0.38, 0.48));
        msg.position.set(3.4, lerp(4.4, 2.9, mk), 2.2);
        setA(msg, seg(p, 0.38, 0.42) * (1 - seg(p, 0.85, 0.92)));
        if (p > 0.78) {
          const s = tiles[0].userData.state;
          s.items = [{ t: 'hilt-android', a: seg(p, 0.78, 0.82) }];
          s.tint = seg(p, 0.78, 0.84) * (0.7 + 0.3 * pulse(t, 1.5)); s.tintColor = C.green;
          tiles[2].userData.state.items = [{ t: 'iOS still builds', a: seg(p, 0.84, 0.9) }];
        }
        tiles.forEach((tl) => tl.userData.redraw());
        cam.position.set(Math.sin(t * 0.3) * 0.6, 1.0, 13);
        cam.lookAt(0, 0.4, 0);
      },
    };
  }

  // ---- 8. CLI --------------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const script = [
      { cmd: './gradlew kmpHubSearch --query=okio', out: ['  com.squareup.okio:okio        3.18.2   ios/jvm/js/wasm/native'], at: 0.02 },
      { cmd: './gradlew kmpHubAdd --lib=ktor-client,koin', out: ['  Added Ktor Client to :composeApp.', '  Added Koin to :composeApp.', '  Updated 3 files. Sync Gradle to pick up the changes.'], at: 0.33 },
      { cmd: './gradlew kmpHubDoctor --strict', out: ['  No problems found. ✓'], at: 0.68 },
    ];
    let P = 0;
    const term = panel(10.4, 5.6, (ctx, w, h) => {
      windowChrome(ctx, w, h, 'Terminal — SampleKmpApp', { bg: '#0f1016' });
      let y = 130;
      for (const s of script) {
        const typeK = seg(P, s.at, s.at + 0.14);
        if (typeK <= 0) break;
        const shown = s.cmd.slice(0, Math.ceil(s.cmd.length * typeK));
        const x = text(ctx, '$ ', 46, y, { size: 40, font: MONO, color: C.green, weight: 700 });
        text(ctx, shown, 46 + x, y, { size: 40, font: MONO, color: C.text, weight: 600 });
        y += 60;
        s.out.forEach((o, i) => {
          const a = seg(P, s.at + 0.16 + i * 0.03, s.at + 0.19 + i * 0.03);
          if (a <= 0) return;
          ctx.globalAlpha = a;
          text(ctx, o, 46, y, { size: 34, font: MONO, color: o.includes('✓') ? C.green : '#b8bdd6', max: w - 80 });
          ctx.globalAlpha = 1;
          y += 52;
        });
        y += 22;
      }
      if (Math.floor(P * 60) % 2 === 0) { ctx.fillStyle = C.text; ctx.fillRect(46, y - 30, 16, 34); }
    });
    g.add(term);
    S.cli = {
      group: g,
      update(p, t, dur, cam) {
        P = p;
        term.position.set(0, 0.2, 0);
        term.rotation.set(-0.05, lerp(-0.35, 0.12, easeInOut(p)), 0);
        setA(term, seg(p, 0, 0.06));
        term.userData.plane.redraw();
        cam.position.set(0, 0.3, lerp(13.4, 11.6, easeInOut(p)));
        cam.lookAt(0, 0.2, 0);
      },
    };
  }

  // ---- 9. Benefits ---------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const icons = {
      clock: (ctx, x, y, r) => { ctx.lineWidth = 9; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.stroke(); ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x, y - r * 0.6); ctx.moveTo(x, y); ctx.lineTo(x + r * 0.45, y + r * 0.2); ctx.stroke(); },
      target: (ctx, x, y, r) => { ctx.lineWidth = 8; [1, 0.62, 0.25].forEach((k) => { ctx.beginPath(); ctx.arc(x, y, r * k, 0, Math.PI * 2); ctx.stroke(); }); },
      up: (ctx, x, y, r) => { ctx.lineWidth = 10; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.beginPath(); ctx.moveTo(x, y + r); ctx.lineTo(x, y - r); ctx.moveTo(x - r * 0.6, y - r * 0.35); ctx.lineTo(x, y - r); ctx.lineTo(x + r * 0.6, y - r * 0.35); ctx.stroke(); },
      bolt: (ctx, x, y, r) => { ctx.beginPath(); ctx.moveTo(x + r * 0.2, y - r); ctx.lineTo(x - r * 0.55, y + r * 0.1); ctx.lineTo(x - r * 0.02, y + r * 0.1); ctx.lineTo(x - r * 0.25, y + r); ctx.lineTo(x + r * 0.55, y - r * 0.15); ctx.lineTo(x + r * 0.05, y - r * 0.15); ctx.closePath(); ctx.fill(); },
    };
    const items = [
      ['clock', 'Less time in docs', 'Recipes know engines, BOMs, KSP and plugins', C.brand],
      ['target', 'No wrong source sets', 'Platform-aware placement and checks', C.green],
      ['up', 'Always latest stable', 'Live from Maven Central & Google Maven', C.amber],
      ['bolt', 'Your stack, one click', 'Multi-select · diff preview · one undo', '#4f8ff7'],
    ];
    const cards = items.map(([icon, t1, t2, color]) => {
      const c = panel(5.6, 2.3, (ctx, w, h) => {
        rr(ctx, 0, 0, w, h, 30);
        const gr = ctx.createLinearGradient(0, 0, w, h); gr.addColorStop(0, '#262839'); gr.addColorStop(1, '#1a1b26');
        ctx.fillStyle = gr; ctx.fill();
        ctx.strokeStyle = hexA(color, 0.7); ctx.lineWidth = 4; rr(ctx, 2, 2, w - 4, h - 4, 28); ctx.stroke();
        ctx.fillStyle = hexA(color, 0.18); ctx.beginPath(); ctx.arc(150, h / 2, 95, 0, Math.PI * 2); ctx.fill();
        ctx.strokeStyle = color; ctx.fillStyle = color; icons[icon](ctx, 150, h / 2, 50);
        text(ctx, t1, 285, h / 2 - 6, { size: 60, weight: 800, max: w - 310 });
        text(ctx, t2, 285, h / 2 + 58, { size: 36, color: '#aeb3c9', max: w - 310 });
      }, { rim: false });
      g.add(c);
      return c;
    });
    const home = [V(-3.0, 1.35, 0), V(3.0, 1.35, 0), V(-3.0, -1.35, 0), V(3.0, -1.35, 0)];
    S.benefits = {
      group: g,
      update(p, t, dur, cam) {
        cards.forEach((c, i) => {
          const k = easeOutBack(seg(p, 0.05 + i * 0.13, 0.25 + i * 0.13));
          c.position.copy(home[i]).add(V(0, Math.sin(t * 1.2 + i) * 0.05, 0));
          c.rotation.set(0, lerp(Math.PI / 2, 0, k), 0);
          setA(c, seg(p, 0.05 + i * 0.13, 0.12 + i * 0.13));
        });
        cam.position.set(Math.sin(t * 0.4) * 0.6, 0.2, 11.4);
        cam.lookAt(0, 0, 0);
      },
    };
  }

  // ---- 10. Outro -----------------------------------------------------------------------------------------
  {
    const g = new THREE.Group();
    const L = logo();
    g.add(L);
    const name = title('KMP Dependency Hub', { size: 130, w: 13, h: 1.7 });
    const cta = panel(6.4, 1.15, (ctx, w, h) => {
      rr(ctx, 0, 0, w, h, h / 2); const gr = ctx.createLinearGradient(0, 0, w, 0); gr.addColorStop(0, C.brand); gr.addColorStop(1, C.brand2); ctx.fillStyle = gr; ctx.fill();
      text(ctx, 'Install from JetBrains Marketplace', w / 2, h / 2 + 16, { size: 48, weight: 800, align: 'center', color: '#fff' });
    }, { rim: false });
    const sub = title('Android Studio · IntelliJ IDEA · Gradle plugin', { size: 44, w: 10, h: 0.7, gradient: false, color: '#b9bcd6', weight: 500 });
    g.add(name.mesh, cta, sub.mesh);
    S.outro = {
      group: g,
      update(p, t, dur, cam) {
        L.userData.assemble(1);
        L.position.set(0, 1.55, 0);
        L.scale.setScalar(0.8);
        L.rotation.set(0.1 + 0.05 * Math.sin(t * 0.9), 0.35 * Math.sin(t * 0.8), 0);
        setA(L, seg(p, 0, 0.15));
        name.mesh.position.set(0, -0.75, 1); setA(name.mesh, easeOut(seg(p, 0.1, 0.3)));
        const ck = easeOutBack(seg(p, 0.25, 0.45));
        cta.position.set(0, -2.05, 1.2); cta.scale.setScalar(Math.max(0.001, ck)); setA(cta, seg(p, 0.25, 0.3));
        sub.mesh.position.set(0, -2.95, 1); setA(sub.mesh, seg(p, 0.4, 0.55));
        cam.position.set(0, 0.2, 11.5);
        cam.lookAt(0, -0.2, 0);
      },
    };
  }

  for (const k of Object.keys(S)) { S[k].group.visible = false; scene.add(S[k].group); }
  return S;
}
