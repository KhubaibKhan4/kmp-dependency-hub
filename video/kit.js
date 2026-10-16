// Shared building blocks for the tutorial video: easing, canvas-textured panels, UI drawing, the 3D logo.
import * as THREE from './vendor/three.module.min.js';

export { THREE };

// ---- Time helpers ------------------------------------------------------------------------------------
export const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const seg = (p, a, b) => clamp((p - a) / (b - a));
export const lerp = (a, b, t) => a + (b - a) * t;
export const easeOut = (t) => 1 - Math.pow(1 - t, 3);
export const easeInOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
export const easeOutBack = (t) => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); };
export const easeOutElastic = (t) => (t === 0 || t === 1 ? t : Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI) / 3) + 1);
export const pulse = (t, speed = 1) => 0.5 + 0.5 * Math.sin(t * Math.PI * 2 * speed);

// ---- Palette -----------------------------------------------------------------------------------------
export const C = {
  bg: '#0b0a1a', panel: '#1c1d26', panel2: '#252733', line: '#363948', text: '#e9ebf3', muted: '#8f94a8',
  brand: '#7f52ff', brand2: '#1e88e5', green: '#3ddc84', red: '#ff5370', amber: '#ffb238', star: '#f2c14e',
  android: '#7bc67e', ios: '#b0bec5', jvm: '#f0b36a', js: '#e6d45c', wasm: '#a99bff', native: '#6fd3c5', recipe: '#4f8ff7',
  codeBg: '#16171f', add: 'rgba(61,220,132,0.16)', addBar: '#3ddc84', kw: '#c792ea', str: '#c3e88d', acc: '#82aaff', cmt: '#676e95',
};
export const SANS = '-apple-system, "SF Pro Display", "Helvetica Neue", Helvetica, Arial, sans-serif';
export const MONO = '"SF Mono", Menlo, Monaco, monospace';

// ---- Opacity: per-object multipliers, applied once per frame ------------------------------------------
export function setA(obj, a) { obj.userData.a = a; return obj; }
export function applyAlpha(root, inherited = 1) {
  const a = inherited * (root.userData.a ?? 1);
  root.visible = a > 0.002;
  if (root.material) {
    const mats = Array.isArray(root.material) ? root.material : [root.material];
    for (const m of mats) {
      if (m.userData.base === undefined) m.userData.base = m.opacity;
      m.transparent = true;
      m.opacity = m.userData.base * a;
    }
  }
  for (const c of root.children) applyAlpha(c, a);
}

// ---- Canvas-textured panels ---------------------------------------------------------------------------
export const PPU = 230; // texture pixels per world unit; ~2x the on-screen density at z = 0

export function canvasPlane(w, h, draw, { ppu = PPU } = {}) {
  const cw = Math.round(w * ppu), ch = Math.round(h * ppu);
  const canvas = document.createElement('canvas');
  canvas.width = cw; canvas.height = ch;
  const ctx = canvas.getContext('2d');
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  tex.generateMipmaps = true;
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, toneMapped: false, depthWrite: false });
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
  const s = ppu / PPU; // drawing scale so callers can use "pixels at PPU"
  const api = {
    mesh, ctx, canvas, w, h, cw, ch,
    redraw: (...args) => {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, cw, ch);
      ctx.setTransform(s, 0, 0, s, 0, 0);
      draw(ctx, cw / s, ch / s, ...args);
      tex.needsUpdate = true;
    },
  };
  api.redraw();
  return api;
}

/** Soft drop shadow / glow behind a panel. */
export function glow(w, h, color = '#000000', strength = 0.55, spread = 0.5) {
  const pad = spread;
  const plane = canvasPlane(w + pad * 2, h + pad * 2, (ctx, cw, ch) => {
    ctx.shadowColor = color;
    ctx.shadowBlur = pad * PPU * 0.55;
    ctx.fillStyle = color;
    rr(ctx, pad * PPU, pad * PPU, w * PPU, h * PPU, 30);
    ctx.fill();
  }, { ppu: 60 });
  plane.mesh.material.opacity = strength;
  plane.mesh.renderOrder = -1;
  return plane.mesh;
}

/** Panel with a shadow and a thin brand-coloured rim, as a group. */
export function panel(w, h, draw, { shadow = 0.6, rim = true } = {}) {
  const group = new THREE.Group();
  const sh = glow(w, h, '#000000', shadow, 0.6);
  sh.position.set(0.12, -0.18, -0.06);
  group.add(sh);
  if (rim) {
    const r = glow(w, h, C.brand, 0.35, 0.25);
    r.position.z = -0.03;
    group.add(r);
  }
  const p = canvasPlane(w, h, draw);
  group.add(p.mesh);
  group.userData.plane = p;
  return group;
}

// ---- 2D drawing -------------------------------------------------------------------------------------
export function rr(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

export function text(ctx, s, x, y, { size = 28, weight = 500, color = C.text, font = SANS, align = 'left', base = 'alphabetic', max } = {}) {
  ctx.font = `${weight} ${size}px ${font}`;
  ctx.fillStyle = color;
  ctx.textAlign = align;
  ctx.textBaseline = base;
  if (max) {
    let str = s;
    while (str.length > 1 && ctx.measureText(str).width > max) str = str.slice(0, -2) + '…';
    s = str;
  }
  ctx.fillText(s, x, y);
  return ctx.measureText(s).width;
}

export function badge(ctx, x, y, label, color, { filled = false, size = 18 } = {}) {
  ctx.font = `600 ${size}px ${SANS}`;
  const w = ctx.measureText(label).width + size * 0.9;
  const h = size * 1.45;
  rr(ctx, x, y, w, h, h / 2.4);
  if (filled) { ctx.fillStyle = color; ctx.fill(); }
  else { ctx.fillStyle = hexA(color, 0.14); ctx.fill(); ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.stroke(); }
  text(ctx, label, x + size * 0.45, y + h * 0.72, { size, weight: 600, color: filled ? '#ffffff' : color });
  return w;
}

export function hexA(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

export const PLATFORM = {
  Android: C.android, iOS: C.ios, JVM: C.jvm, JS: C.js, Wasm: C.wasm, Native: C.native,
};

/** A library card like the plugin's list rows. */
export function libraryCard(ctx, x, y, w, lib, { checked = false, highlight = 0, checkPop = 0 } = {}) {
  const h = 128;
  if (highlight > 0) { ctx.fillStyle = hexA(C.brand, 0.16 * highlight); rr(ctx, x, y, w, h, 14); ctx.fill(); }
  ctx.strokeStyle = C.line; ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.moveTo(x + 12, y + h); ctx.lineTo(x + w - 12, y + h); ctx.stroke();
  // checkbox
  const cb = 30, cx = x + 22, cy = y + 22;
  rr(ctx, cx, cy, cb, cb, 7);
  if (checked) {
    const s = 1 + 0.25 * Math.sin(Math.PI * checkPop);
    ctx.save(); ctx.translate(cx + cb / 2, cy + cb / 2); ctx.scale(s, s); ctx.translate(-(cx + cb / 2), -(cy + cb / 2));
    rr(ctx, cx, cy, cb, cb, 7); ctx.fillStyle = C.brand; ctx.fill();
    ctx.strokeStyle = '#fff'; ctx.lineWidth = 4; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.beginPath(); ctx.moveTo(cx + 7, cy + 16); ctx.lineTo(cx + 13, cy + 22); ctx.lineTo(cx + 24, cy + 9); ctx.stroke();
    ctx.restore();
  } else { ctx.strokeStyle = C.muted; ctx.lineWidth = 2; ctx.stroke(); }
  const tx = x + 72;
  const nameW = text(ctx, lib.name, tx, y + 44, { size: 28, weight: 700 });
  text(ctx, lib.owner, tx + nameW + 12, y + 44, { size: 22, color: C.muted });
  if (lib.stars) text(ctx, '★ ' + lib.stars, x + w - 20, y + 44, { size: 24, weight: 600, color: C.star, align: 'right' });
  text(ctx, lib.desc, tx, y + 80, { size: 21, color: C.muted, max: w - 100 });
  let bx = tx;
  for (const p of lib.platforms) bx += badge(ctx, bx, y + 92, p, PLATFORM[p], { size: 16 }) + 8;
  if (lib.recipe) badge(ctx, bx, y + 92, 'Recipe', C.recipe, { filled: true, size: 16 });
  if (lib.version) text(ctx, lib.version, x + w - 20, y + 112, { size: 18, color: C.muted, align: 'right' });
  return h;
}

/** Code with optional added-line highlights. lines: [{t, add, err, a}] where a = 0..1 reveal. */
export function code(ctx, x, y, lines, { size = 24, lh = 36, width = 1000 } = {}) {
  let cy = y;
  for (const l of lines) {
    const a = l.a ?? 1;
    if (a <= 0) continue;
    ctx.globalAlpha = a;
    if (l.add || l.err) {
      ctx.fillStyle = l.err ? 'rgba(255,83,112,0.18)' : C.add;
      ctx.fillRect(x - 16, cy - lh * 0.72, width, lh);
      ctx.fillStyle = l.err ? C.red : C.addBar;
      ctx.fillRect(x - 16, cy - lh * 0.72, 5, lh);
      text(ctx, l.err ? '!' : '+', x - 4, cy, { size, font: MONO, color: l.err ? C.red : C.green, weight: 700 });
    }
    drawCodeLine(ctx, l.t, x + 24, cy, size, l.typed);
    ctx.globalAlpha = 1;
    cy += lh;
  }
  return cy;
}

function drawCodeLine(ctx, s, x, y, size, typed) {
  if (typed !== undefined) s = s.slice(0, Math.floor(typed));
  ctx.font = `500 ${size}px ${MONO}`;
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';
  // light-weight highlighting: comments, strings, libs accessors, a few keywords
  const tokens = s.split(/("[^"]*"|\/\/.*$|libs\.[\w.]+|\b(?:plugins|kotlin|dependencies|sourceSets|implementation|alias|add|val|by|getting|room|version)\b)/);
  let cx = x;
  for (const t of tokens) {
    if (!t) continue;
    let color = C.text;
    if (t.startsWith('//')) color = C.cmt;
    else if (t.startsWith('"')) color = C.str;
    else if (t.startsWith('libs.')) color = C.acc;
    else if (/^(plugins|kotlin|dependencies|sourceSets|implementation|alias|add|val|by|getting|room|version)$/.test(t)) color = C.kw;
    ctx.fillStyle = color;
    ctx.fillText(t, cx, y);
    cx += ctx.measureText(t).width;
  }
}

export function windowChrome(ctx, w, h, title, { bg = C.panel } = {}) {
  rr(ctx, 0, 0, w, h, 26); ctx.fillStyle = bg; ctx.fill();
  ctx.save(); rr(ctx, 0, 0, w, h, 26); ctx.clip();
  ctx.fillStyle = '#14151c'; ctx.fillRect(0, 0, w, 64);
  ['#ff5f57', '#febc2e', '#28c840'].forEach((c, i) => { ctx.fillStyle = c; ctx.beginPath(); ctx.arc(36 + i * 30, 32, 9, 0, Math.PI * 2); ctx.fill(); });
  text(ctx, title, w / 2, 41, { size: 22, color: C.muted, align: 'center', weight: 600 });
  ctx.restore();
  ctx.strokeStyle = 'rgba(255,255,255,0.08)'; ctx.lineWidth = 2; rr(ctx, 1, 1, w - 2, h - 2, 26); ctx.stroke();
}

/** A rounded "chip" label as its own 3D plane. */
export function chip(label, { color = C.brand, sub, w } = {}) {
  const ctxW = w ?? Math.max(2.4, 0.21 * label.length + 0.8);
  const h = sub ? 1.0 : 0.72;
  const p = canvasPlane(ctxW, h, (ctx, cw, ch) => {
    rr(ctx, 4, 4, cw - 8, ch - 8, (ch - 8) / 2.2);
    const g = ctx.createLinearGradient(0, 0, cw, ch);
    g.addColorStop(0, hexA(color, 0.95)); g.addColorStop(1, hexA(shade(color, -40), 0.95));
    ctx.fillStyle = g; ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = 3; ctx.stroke();
    if (sub) {
      text(ctx, label, cw / 2, ch * 0.48, { size: 52, weight: 700, color: '#fff', align: 'center', font: MONO });
      text(ctx, sub, cw / 2, ch * 0.8, { size: 34, weight: 600, color: 'rgba(255,255,255,0.88)', align: 'center' });
    } else {
      text(ctx, label, cw / 2, ch * 0.65, { size: 52, weight: 700, color: '#fff', align: 'center', font: MONO });
    }
  });
  const g = new THREE.Group();
  const gl = glow(ctxW, h, color, 0.5, 0.35);
  gl.position.z = -0.02;
  g.add(gl, p.mesh);
  g.userData.plane = p;
  return g;
}

export function shade(hex, amt) {
  const n = parseInt(hex.slice(1), 16);
  const r = clamp(((n >> 16) & 255) + amt, 0, 255), g = clamp(((n >> 8) & 255) + amt, 0, 255), b = clamp((n & 255) + amt, 0, 255);
  return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
}

/** Large title text as a plane. */
export function title(label, { size = 120, w = 12, h = 1.6, color = '#ffffff', gradient = true, weight = 800 } = {}) {
  return canvasPlane(w, h, (ctx, cw, ch) => {
    ctx.font = `${weight} ${size}px ${SANS}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    if (gradient) {
      const g = ctx.createLinearGradient(cw * 0.2, 0, cw * 0.8, 0);
      g.addColorStop(0, '#c8b6ff'); g.addColorStop(0.5, '#ffffff'); g.addColorStop(1, '#9cc9ff');
      ctx.fillStyle = g;
    } else ctx.fillStyle = color;
    ctx.shadowColor = 'rgba(127,82,255,0.6)';
    ctx.shadowBlur = 30;
    ctx.fillText(label, cw / 2, ch / 2);
  });
}

// ---- The 3D logo ------------------------------------------------------------------------------------
export function logo() {
  const root = new THREE.Group();
  const shape = new THREE.Shape();
  const s = 1.5, r = 0.42;
  shape.moveTo(-s + r, -s);
  shape.lineTo(s - r, -s); shape.quadraticCurveTo(s, -s, s, -s + r);
  shape.lineTo(s, s - r); shape.quadraticCurveTo(s, s, s - r, s);
  shape.lineTo(-s + r, s); shape.quadraticCurveTo(-s, s, -s, s - r);
  shape.lineTo(-s, -s + r); shape.quadraticCurveTo(-s, -s, -s + r, -s);
  const tileGeo = new THREE.ExtrudeGeometry(shape, { depth: 0.36, bevelEnabled: true, bevelThickness: 0.12, bevelSize: 0.1, bevelSegments: 6, curveSegments: 18 });
  tileGeo.translate(0, 0, -0.36);
  // Vertex colours give the tile its purple-to-blue diagonal gradient.
  const pos = tileGeo.attributes.position;
  const colors = new Float32Array(pos.count * 3);
  const c1 = new THREE.Color(C.brand), c2 = new THREE.Color(C.brand2), c = new THREE.Color();
  for (let i = 0; i < pos.count; i++) {
    const t = clamp((pos.getX(i) - pos.getY(i) + 2 * s) / (4 * s));
    c.copy(c1).lerp(c2, t);
    colors.set([c.r, c.g, c.b], i * 3);
  }
  tileGeo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  const tile = new THREE.Mesh(tileGeo, new THREE.MeshPhysicalMaterial({ vertexColors: true, roughness: 0.28, metalness: 0.15, clearcoat: 1, clearcoatRoughness: 0.15 }));
  root.add(tile);

  const nodeColors = [C.green, '#e3e8ef', C.amber, '#b49cff'];
  const corners = [[-1, 1], [1, 1], [-1, -1], [1, -1]];
  const nodes = [], spokes = [];
  corners.forEach(([x, y], i) => {
    const spoke = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.07, 1, 16), new THREE.MeshStandardMaterial({ color: '#ffffff', roughness: 0.4, emissive: '#ffffff', emissiveIntensity: 0.25 }));
    const len = Math.hypot(x, y) * 0.95;
    spoke.userData = { dir: new THREE.Vector3(x, y, 0).normalize(), len };
    spoke.rotation.z = Math.atan2(y, x) - Math.PI / 2;
    spoke.position.z = 0.16;
    root.add(spoke); spokes.push(spoke);
    const node = new THREE.Mesh(new THREE.SphereGeometry(0.34, 48, 32), new THREE.MeshPhysicalMaterial({ color: nodeColors[i], roughness: 0.25, clearcoat: 1, emissive: nodeColors[i], emissiveIntensity: 0.15 }));
    node.userData.home = new THREE.Vector3(x * 0.98, y * 0.98, 0.3);
    root.add(node); nodes.push(node);
  });
  const hub = new THREE.Group();
  const hubBall = new THREE.Mesh(new THREE.SphereGeometry(0.62, 64, 48), new THREE.MeshPhysicalMaterial({ color: '#ffffff', roughness: 0.2, clearcoat: 1 }));
  const plusMat = new THREE.MeshStandardMaterial({ color: '#6d4cf2', roughness: 0.35 });
  const bar1 = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.17, 0.12), plusMat);
  const bar2 = new THREE.Mesh(new THREE.BoxGeometry(0.17, 0.62, 0.12), plusMat);
  bar1.position.z = bar2.position.z = 0.6;
  hub.add(hubBall, bar1, bar2);
  hub.position.z = 0.22;
  root.add(hub);
  root.userData = { tile, nodes, spokes, hub };

  /** Assemble: k in 0..1 (nodes fly in, spokes grow, hub pops). */
  root.userData.assemble = (k) => {
    tile.scale.setScalar(lerp(0.6, 1, easeOutBack(seg(k, 0, 0.3))));
    setA(tile, seg(k, 0, 0.15));
    nodes.forEach((n, i) => {
      const t = easeOutBack(seg(k, 0.12 + i * 0.07, 0.45 + i * 0.07));
      const from = n.userData.home.clone().multiplyScalar(5).add(new THREE.Vector3(0, 0, 6));
      n.position.lerpVectors(from, n.userData.home, t);
      setA(n, seg(k, 0.12 + i * 0.07, 0.25 + i * 0.07));
    });
    spokes.forEach((sp, i) => {
      const t = easeOut(seg(k, 0.5 + i * 0.04, 0.72 + i * 0.04));
      const L = sp.userData.len * t;
      sp.scale.set(1, Math.max(L, 0.001), 1);
      sp.position.x = sp.userData.dir.x * L / 2;
      sp.position.y = sp.userData.dir.y * L / 2;
      setA(sp, t > 0 ? 1 : 0);
    });
    const hk = seg(k, 0.68, 0.95);
    hub.scale.setScalar(Math.max(0.001, easeOutElastic(hk)));
    setA(hub, hk > 0 ? 1 : 0);
  };
  return root;
}

// ---- Captions overlay (drawn onto the 2D composite) ----------------------------------------------------
export function drawCaption(ctx, W, H, label, a) {
  if (!label || a <= 0) return;
  ctx.save();
  ctx.globalAlpha = a;
  ctx.font = `600 34px ${SANS}`;
  const tw = ctx.measureText(label).width;
  const pw = tw + 72, ph = 66, x = (W - pw) / 2, y = H - 118;
  rr(ctx, x, y, pw, ph, 33);
  ctx.fillStyle = 'rgba(10,10,22,0.72)';
  ctx.fill();
  ctx.strokeStyle = 'rgba(160,140,255,0.35)';
  ctx.lineWidth = 2;
  ctx.stroke();
  ctx.fillStyle = '#ffffff';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(label, W / 2, y + ph / 2 + 1);
  ctx.restore();
}
