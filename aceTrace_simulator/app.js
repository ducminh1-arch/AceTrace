/**
 * AceTrace Studio - Interactive Web Simulator
 * Implements 100% of the canonical mathematics and rendering specification
 * from aceTrace_spec/schema.json and SPECIFICATION.md
 */

// Global State adhering to Project JSON Schema
const state = {
  project: {
    schemaVersion: 1,
    id: "proj-" + Date.now(),
    createdAt: new Date().toISOString(),
    video: {
      uri: "demo-golf-shot",
      width: 1080,
      height: 1920,
      rotationDegrees: 0,
      durationUs: 4000000,
      frameCount: 120,
      ptsUs: []
    },
    trajectories: [
      {
        id: "traj-1",
        sport: "golf",
        mode: "bezier",
        keypoints: [
          { role: "start", frameIndex: 30, x: 0.42, y: 0.78, handleOut: null },
          { role: "apex", frameIndex: 68, x: 0.55, y: 0.22, handleIn: null, handleOut: null },
          { role: "landing", frameIndex: 110, x: 0.72, y: 0.65, handleIn: null }
        ],
        style: {
          palette: "neonCyan",
          gradient: ["#00E5FF", "#FF2D95", "#FFB300"],
          lineWidth: 8,
          glow: 0.75,
          trailMode: "tracer",
          cometLengthFraction: 0.25,
          showImpactFlash: true
        },
        distance: {
          value: 285,
          unit: "yd",
          visible: true,
          easing: "easeOut"
        }
      }
    ],
    cameraTrack: null,
    export: {
      resolution: "source",
      fps: "source",
      watermark: true
    }
  },

  // Playback & UI State
  currentFrame: 0,
  isPlaying: false,
  playbackSpeed: 1.0,
  activeRoleToSet: "start",
  isDraggingKp: null,
  isDraggingHandle: null,
  mousePos: { x: 0, y: 0, normX: 0, normY: 0 },
  isMouseInsideViewport: false
};

// Color Palettes Preset
const PALETTES = {
  neonCyan: ["#00E5FF", "#FF2D95", "#FFB300"],
  electricSunset: ["#FF0055", "#FF5500", "#FFAA00"],
  acidGreen: ["#00FF66", "#00E5FF", "#0088FF"],
  cyberPurple: ["#B500FF", "#FF0077", "#00FFFF"],
  iceFire: ["#FFFFFF", "#00E5FF", "#0044FF"]
};

// DOM Elements
const videoEl = document.getElementById("video-element");
const overlayCanvas = document.getElementById("overlay-canvas");
const overlayCtx = overlayCanvas.getContext("2d");
const handlesSvg = document.getElementById("handles-svg");
const viewport = document.getElementById("viewport");
const scrubber = document.getElementById("scrubber-range");
const statFrame = document.getElementById("stat-frame");
const statTime = document.getElementById("stat-time");
const playPauseBtn = document.getElementById("btn-play-pause");
const playIcon = document.getElementById("play-icon");
const pauseIcon = document.getElementById("pause-icon");
const selectSpeed = document.getElementById("select-speed");

// Magnifier
const magnifier = document.getElementById("magnifier");
const magnifierCanvas = document.getElementById("magnifier-canvas");
const magnifierCtx = magnifierCanvas.getContext("2d");
const magnifierLabel = document.getElementById("magnifier-label");

// Demo Scene Canvas (used to generate realistic sports video background)
let demoCanvas = document.createElement("canvas");
let demoCtx = demoCanvas.getContext("2d");
let demoStream = null;

// ==========================================
// 1. CANONICAL MATHEMATICAL ENGINE
// ==========================================

function evaluateCubicBezier(p0, p1, p2, p3, t) {
  const u = Math.min(Math.max(t, 0), 1);
  const oneMinusU = 1 - u;
  const c0 = oneMinusU * oneMinusU * oneMinusU;
  const c1 = 3 * oneMinusU * oneMinusU * u;
  const c2 = 3 * oneMinusU * u * u;
  const c3 = u * u * u;
  return {
    x: c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x,
    y: c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y
  };
}

function resolveKeypointHandles(keypoints) {
  if (keypoints.length !== 3) return keypoints;

  const [start, apex, landing] = keypoints;

  const startHandleOut = start.handleOut || {
    x: start.x + (apex.x - start.x) / 3,
    y: start.y + (apex.y - start.y) / 3
  };

  const apexHandleIn = apex.handleIn || {
    x: apex.x - (apex.x - start.x) / 3,
    y: apex.y
  };

  const apexHandleOut = apex.handleOut || {
    x: apex.x + (landing.x - apex.x) / 3,
    y: apex.y
  };

  const landingHandleIn = landing.handleIn || {
    x: landing.x - (landing.x - apex.x) / 3,
    y: landing.y - (landing.y - apex.y) / 3
  };

  return [
    { ...start, handleIn: null, handleOut: startHandleOut },
    { ...apex, handleIn: apexHandleIn, handleOut: apexHandleOut },
    { ...landing, handleIn: landingHandleIn, handleOut: null }
  ];
}

function evaluateBezierTrajectory(resolvedKps, uGlobal) {
  const clampedU = Math.min(Math.max(uGlobal, 0), 1);
  if (clampedU <= 0.5) {
    const segU = clampedU / 0.5;
    const p0 = { x: resolvedKps[0].x, y: resolvedKps[0].y };
    const p1 = resolvedKps[0].handleOut;
    const p2 = resolvedKps[1].handleIn;
    const p3 = { x: resolvedKps[1].x, y: resolvedKps[1].y };
    return evaluateCubicBezier(p0, p1, p2, p3, segU);
  } else {
    const segU = (clampedU - 0.5) / 0.5;
    const p0 = { x: resolvedKps[1].x, y: resolvedKps[1].y };
    const p1 = resolvedKps[1].handleOut;
    const p2 = resolvedKps[2].handleIn;
    const p3 = { x: resolvedKps[2].x, y: resolvedKps[2].y };
    return evaluateCubicBezier(p0, p1, p2, p3, segU);
  }
}

function evaluateCatmullRom(p0, p1, p2, p3, t, alpha = 0.5) {
  function distSq(a, b) {
    return (a.x - b.x) ** 2 + (a.y - b.y) ** 2;
  }
  const t0 = 0.0;
  const t1 = t0 + Math.max(Math.pow(distSq(p0, p1), alpha * 0.5), 1e-4);
  const t2 = t1 + Math.max(Math.pow(distSq(p1, p2), alpha * 0.5), 1e-4);
  const t3 = t2 + Math.max(Math.pow(distSq(p2, p3), alpha * 0.5), 1e-4);

  const actualT = t1 + Math.min(Math.max(t, 0), 1) * (t2 - t1);

  function interp(a, b, ta, tb, tVal) {
    const factor = (tVal - ta) / (tb - ta);
    return { x: a.x + factor * (b.x - a.x), y: a.y + factor * (b.y - a.y) };
  }

  const a1 = interp(p0, p1, t0, t1, actualT);
  const a2 = interp(p1, p2, t1, t2, actualT);
  const a3 = interp(p2, p3, t2, t3, actualT);

  const b1 = interp(a1, a2, t0, t2, actualT);
  const b2 = interp(a2, a3, t1, t3, actualT);

  return interp(b1, b2, t1, t2, actualT);
}

function computeTimeMapping(frameIndex, startFrame, apexFrame, landingFrame) {
  if (frameIndex < startFrame) {
    return { frame: frameIndex, progress: 0, u: 0, status: "before_start" };
  }
  if (frameIndex > landingFrame) {
    return { frame: frameIndex, progress: 1, u: 1, status: "landed" };
  }
  if (frameIndex <= apexFrame) {
    const totalAscent = Math.max(apexFrame - startFrame, 1);
    const s = (frameIndex - startFrame) / totalAscent;
    const sEased = 1 - (1 - s) * (1 - s); // Ease-out ascent
    const u = Math.min(Math.max(0.5 * sEased, 0), 0.5);
    return { frame: frameIndex, progress: u, u: u, status: "ascending" };
  } else {
    const totalDescent = Math.max(landingFrame - apexFrame, 1);
    const s = (frameIndex - apexFrame) / totalDescent;
    const sEased = s * s; // Ease-in descent
    const u = Math.min(Math.max(0.5 + 0.5 * sEased, 0.5), 1);
    return { frame: frameIndex, progress: u, u: u, status: "descending" };
  }
}

function computeEasedDistance(total, progress, easing = "easeOut") {
  const p = Math.min(Math.max(progress, 0), 1);
  let factor = p;
  if (easing === "easeOut") {
    factor = 1 - (1 - p) * (1 - p);
  } else if (easing === "easeInOut") {
    factor = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
  }
  return total * factor;
}

// ==========================================
// 2. OVERLAY RENDERER (VFX, 3-LAYER GLOW, HUD)
// ==========================================

function renderOverlay(ctx, width, height, frameIndex, project) {
  ctx.clearRect(0, 0, width, height);

  for (const trajectory of project.trajectories) {
    const keypoints = trajectory.keypoints;
    if (keypoints.length < 2) continue;

    const startKp = keypoints.find(k => k.role === "start") || keypoints[0];
    const apexKp = keypoints.find(k => k.role === "apex") || keypoints[1] || keypoints[0];
    const landingKp = keypoints.find(k => k.role === "landing") || keypoints[keypoints.length - 1];

    const flightState = computeTimeMapping(
      frameIndex,
      startKp.frameIndex,
      apexKp.frameIndex,
      landingKp.frameIndex
    );

    if (flightState.status === "before_start") continue;

    const style = trajectory.style;
    const currentU = flightState.u;

    let uStart = 0;
    let uEnd = currentU;
    if (style.trailMode === "full") {
      uStart = 0;
      uEnd = 1.0;
    } else if (style.trailMode === "comet") {
      uStart = Math.max(0, currentU - style.cometLengthFraction);
      uEnd = currentU;
    }

    if (uEnd <= uStart && currentU > 0) continue;

    const resolvedKps = resolveKeypointHandles(keypoints);
    const numSamples = 60;
    const points = [];
    const step = (uEnd - uStart) / Math.max(numSamples, 1);

    for (let i = 0; i <= numSamples; i++) {
      const u = Math.min(Math.max(uStart + i * step, 0), 1);
      let pt;
      if (trajectory.mode === "catmullRom") {
        pt = evaluateCatmullRom(
          resolvedKps[0], resolvedKps[0], resolvedKps[1], resolvedKps[2],
          u <= 0.5 ? u / 0.5 : (u - 0.5) / 0.5
        );
      } else {
        pt = evaluateBezierTrajectory(resolvedKps, u);
      }
      points.push({ x: pt.x * width, y: pt.y * height });
    }

    if (points.length < 2) continue;

    ctx.save();
    ctx.lineCap = "round";
    ctx.lineJoin = "round";

    // Path
    const path = new Path2D();
    path.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length; i++) {
      path.lineTo(points[i].x, points[i].y);
    }

    // Gradient
    const grad = ctx.createLinearGradient(
      points[0].x, points[0].y,
      points[points.length - 1].x, points[points.length - 1].y
    );
    const colors = style.gradient;
    colors.forEach((c, idx) => {
      grad.addColorStop(idx / (colors.length - 1), c);
    });

    const baseWidth = Math.max(style.lineWidth, 1);
    const glow = Math.min(Math.max(style.glow, 0), 1);

    // Layer 1: Outer Glow Bloom
    ctx.save();
    ctx.lineWidth = baseWidth * 3.5;
    ctx.shadowColor = colors[0];
    ctx.shadowBlur = baseWidth * 2.0 * glow * 1.5;
    ctx.strokeStyle = colors[0];
    ctx.globalAlpha = 0.22 * glow;
    ctx.stroke(path);
    ctx.restore();

    // Layer 2: Inner Glow
    ctx.save();
    ctx.lineWidth = baseWidth * 1.8;
    ctx.shadowColor = colors[1] || colors[0];
    ctx.shadowBlur = baseWidth * 0.8 * glow * 1.5;
    ctx.strokeStyle = grad;
    ctx.globalAlpha = 0.50 * glow;
    ctx.stroke(path);
    ctx.restore();

    // Layer 3: Core Sharp Line
    ctx.save();
    ctx.lineWidth = baseWidth;
    ctx.strokeStyle = grad;
    ctx.globalAlpha = 1.0;
    ctx.stroke(path);
    ctx.restore();

    // Impact Flash
    if (style.showImpactFlash && flightState.progress < 0.15) {
      const flashAlpha = Math.max(0, 1 - flightState.progress / 0.15);
      ctx.save();
      ctx.fillStyle = "#FFFFFF";
      ctx.shadowColor = "#FFFFFF";
      ctx.shadowBlur = baseWidth * 3;
      ctx.globalAlpha = flashAlpha;
      ctx.beginPath();
      ctx.arc(points[0].x, points[0].y, baseWidth * 2.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

    // Distance HUD Label
    if (trajectory.distance.visible && points.length > 0) {
      const headPt = points[points.length - 1];
      const dist = Math.round(
        computeEasedDistance(trajectory.distance.value, flightState.progress, trajectory.distance.easing)
      );
      renderDistanceBadge(ctx, headPt.x, headPt.y - 32, `${dist} ${trajectory.distance.unit}`);
    }

    ctx.restore();
  }

  // Watermark
  if (project.export.watermark) {
    ctx.save();
    ctx.font = "bold 13px 'JetBrains Mono', monospace";
    ctx.fillStyle = "rgba(255, 255, 255, 0.4)";
    ctx.textAlign = "right";
    ctx.fillText("Traced with AceTrace", width - 24, height - 20);
    ctx.restore();
  }
}

function renderDistanceBadge(ctx, x, y, text) {
  ctx.save();
  ctx.font = "bold 13px 'JetBrains Mono', monospace";
  const metrics = ctx.measureText(text);
  const paddingX = 10;
  const paddingY = 5;
  const w = metrics.width + paddingX * 2;
  const h = 24;

  const rx = x - w / 2;
  const ry = y - h / 2;

  // Badge background
  ctx.fillStyle = "rgba(12, 12, 18, 0.85)";
  ctx.strokeStyle = "rgba(0, 229, 255, 0.4)";
  ctx.lineWidth = 1.5;

  ctx.beginPath();
  ctx.roundRect(rx, ry, w, h, 6);
  ctx.fill();
  ctx.stroke();

  // Badge text
  ctx.fillStyle = "#FFFFFF";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(text, x, y);

  ctx.restore();
}

// ==========================================
// 3. SYNTHETIC SPORTS DEMO VIDEO GENERATOR
// ==========================================

function initDemoScene(sport = "golf") {
  demoCanvas.width = 1080;
  demoCanvas.height = 1920;

  // Generate 120 PTS timestamps (30 fps)
  const pts = [];
  for (let f = 0; f < 120; f++) {
    pts.push(Math.round((f / 30) * 1000000));
  }
  state.project.video.ptsUs = pts;
  state.project.video.frameCount = 120;
  state.project.video.width = 1080;
  state.project.video.height = 1920;

  scrubber.max = 119;
  updateScrubberMarks();

  renderDemoFrame(0, sport);

  // Bind video element or fallback canvas stream
  try {
    demoStream = demoCanvas.captureStream(30);
    videoEl.srcObject = demoStream;
    videoEl.play().catch(() => {});
  } catch (e) {
    console.log("Canvas stream fallback to direct draw", e);
  }

  resizeCanvas();
  seekToFrame(0);
}

function renderDemoFrame(frameIndex, sport = "golf") {
  const ctx = demoCtx;
  const w = demoCanvas.width;
  const h = demoCanvas.height;

  // 1. Sky & Horizon
  const skyGrad = ctx.createLinearGradient(0, 0, 0, h * 0.6);
  skyGrad.addColorStop(0, "#1A2E44");
  skyGrad.addColorStop(0.6, "#3A6384");
  skyGrad.addColorStop(1, "#E8A366");
  ctx.fillStyle = skyGrad;
  ctx.fillRect(0, 0, w, h * 0.6);

  // Sun
  ctx.fillStyle = "#FFE8A3";
  ctx.shadowColor = "#FFAA00";
  ctx.shadowBlur = 40;
  ctx.beginPath();
  ctx.arc(w * 0.75, h * 0.35, 45, 0, Math.PI * 2);
  ctx.fill();
  ctx.shadowBlur = 0;

  // Mountains / Distant Hills
  ctx.fillStyle = "#20343D";
  ctx.beginPath();
  ctx.moveTo(0, h * 0.6);
  ctx.lineTo(w * 0.3, h * 0.52);
  ctx.lineTo(w * 0.65, h * 0.58);
  ctx.lineTo(w, h * 0.50);
  ctx.lineTo(w, h * 0.6);
  ctx.closePath();
  ctx.fill();

  // Green Fairway / Turf
  const grassGrad = ctx.createLinearGradient(0, h * 0.55, 0, h);
  grassGrad.addColorStop(0, "#2D5A27");
  grassGrad.addColorStop(0.4, "#3D7A32");
  grassGrad.addColorStop(1, "#264B1F");
  ctx.fillStyle = grassGrad;
  ctx.fillRect(0, h * 0.55, w, h * 0.45);

  // Distant Trees
  for (let x = 40; x < w; x += 110) {
    ctx.fillStyle = "#1E421B";
    ctx.beginPath();
    ctx.arc(x, h * 0.56, 35, 0, Math.PI * 2);
    ctx.fill();
  }

  // 2. Athlete Character (Tee box / Thrower at frame 0..30)
  const charX = w * 0.42;
  const charY = h * 0.78;

  ctx.save();
  ctx.fillStyle = "#111116";
  // Body shadow
  ctx.fillStyle = "rgba(0, 0, 0, 0.3)";
  ctx.beginPath();
  ctx.ellipse(charX, charY + 5, 25, 8, 0, 0, Math.PI * 2);
  ctx.fill();

  // Golfer figure
  ctx.fillStyle = "#D43F3F"; // Shirt
  ctx.fillRect(charX - 12, charY - 60, 24, 40);

  ctx.fillStyle = "#1E2A38"; // Pants
  ctx.fillRect(charX - 10, charY - 20, 9, 25);
  ctx.fillRect(charX + 1, charY - 20, 9, 25);

  ctx.fillStyle = "#ECC39E"; // Head
  ctx.beginPath();
  ctx.arc(charX, charY - 72, 12, 0, Math.PI * 2);
  ctx.fill();

  // Club swing rotation
  ctx.strokeStyle = "#CCCCCC";
  ctx.lineWidth = 4;
  ctx.beginPath();
  if (frameIndex < 30) {
    const angle = -Math.PI / 4 + (frameIndex / 30) * Math.PI;
    ctx.moveTo(charX, charY - 45);
    ctx.lineTo(charX + Math.cos(angle) * 55, charY - 45 + Math.sin(angle) * 55);
  } else {
    // Follow through
    ctx.moveTo(charX, charY - 45);
    ctx.lineTo(charX - 35, charY - 75);
  }
  ctx.stroke();
  ctx.restore();

  // 3. Real Ball Physics Animation in background
  if (frameIndex >= 30) {
    const startKp = state.project.trajectories[0].keypoints[0];
    const apexKp = state.project.trajectories[0].keypoints[1];
    const landingKp = state.project.trajectories[0].keypoints[2];
    const fState = computeTimeMapping(frameIndex, startKp.frameIndex, apexKp.frameIndex, landingKp.frameIndex);

    const resolved = resolveKeypointHandles(state.project.trajectories[0].keypoints);
    const ballPos = evaluateBezierTrajectory(resolved, fState.u);

    // Ball render
    ctx.save();
    ctx.fillStyle = "#FFFFFF";
    ctx.shadowColor = "#FFFFFF";
    ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.arc(ballPos.x * w, ballPos.y * h, 7, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }
}

// ==========================================
// 4. INTERACTION, HANDLES & MAGNIFIER
// ==========================================

function resizeCanvas() {
  const rect = viewport.getBoundingClientRect();
  overlayCanvas.width = rect.width;
  overlayCanvas.height = rect.height;
  magnifierCanvas.width = 120;
  magnifierCanvas.height = 120;
  renderCurrentFrame();
  renderSvgHandles();
}

function renderCurrentFrame() {
  renderDemoFrame(state.currentFrame, state.project.trajectories[0].sport);
  renderOverlay(
    overlayCtx,
    overlayCanvas.width,
    overlayCanvas.height,
    state.currentFrame,
    state.project
  );
  updateStatusChips();
}

function updateStatusChips() {
  statFrame.textContent = `${state.currentFrame} / ${state.project.video.frameCount}`;
  const secs = state.currentFrame / 30;
  const mins = Math.floor(secs / 60);
  const remSecs = (secs % 60).toFixed(2);
  statTime.textContent = `00:${String(mins).padStart(2, '0')}:${remSecs.padStart(5, '0')}`;
  scrubber.value = state.currentFrame;

  // Update sidebar info
  const kps = state.project.trajectories[0].keypoints;
  const startKp = kps.find(k => k.role === "start");
  const apexKp = kps.find(k => k.role === "apex");
  const landingKp = kps.find(k => k.role === "landing");

  if (startKp) document.getElementById("coord-start").textContent = `F: ${startKp.frameIndex} (${Math.round(startKp.x * 100)}%, ${Math.round(startKp.y * 100)}%)`;
  if (apexKp) document.getElementById("coord-apex").textContent = `F: ${apexKp.frameIndex} (${Math.round(apexKp.x * 100)}%, ${Math.round(apexKp.y * 100)}%)`;
  if (landingKp) document.getElementById("coord-landing").textContent = `F: ${landingKp.frameIndex} (${Math.round(landingKp.x * 100)}%, ${Math.round(landingKp.y * 100)}%)`;
}

function updateScrubberMarks() {
  const track = document.getElementById("markers-track");
  track.innerHTML = "";
  const total = state.project.video.frameCount || 120;

  state.project.trajectories[0].keypoints.forEach(kp => {
    const mark = document.createElement("div");
    mark.className = "timeline-marker";
    mark.style.left = `${(kp.frameIndex / total) * 100}%`;
    mark.style.backgroundColor =
      kp.role === "start" ? "#00FF66" :
      kp.role === "apex" ? "#00E5FF" : "#FF2D95";
    track.appendChild(mark);
  });
}

function renderSvgHandles() {
  const w = overlayCanvas.width;
  const h = overlayCanvas.height;
  const kps = state.project.trajectories[0].keypoints;
  const resolved = resolveKeypointHandles(kps);

  let svgHtml = "";

  resolved.forEach(kp => {
    const cx = kp.x * w;
    const cy = kp.y * h;
    const color = kp.role === "start" ? "#00FF66" : kp.role === "apex" ? "#00E5FF" : "#FF2D95";

    // Handle Lines
    if (kp.handleIn) {
      const hx = kp.handleIn.x * w;
      const hy = kp.handleIn.y * h;
      svgHtml += `<line x1="${cx}" y1="${cy}" x2="${hx}" y2="${hy}" stroke="rgba(255,255,255,0.4)" stroke-dasharray="3,3" stroke-width="1.5"/>`;
      svgHtml += `<circle cx="${hx}" cy="${hy}" r="5" fill="#FFFFFF" class="drag-handle" data-role="${kp.role}" data-type="handleIn"/>`;
    }
    if (kp.handleOut) {
      const hx = kp.handleOut.x * w;
      const hy = kp.handleOut.y * h;
      svgHtml += `<line x1="${cx}" y1="${cy}" x2="${hx}" y2="${hy}" stroke="rgba(255,255,255,0.4)" stroke-dasharray="3,3" stroke-width="1.5"/>`;
      svgHtml += `<circle cx="${hx}" cy="${hy}" r="5" fill="#FFFFFF" class="drag-handle" data-role="${kp.role}" data-type="handleOut"/>`;
    }

    // Anchor Point
    svgHtml += `
      <g class="drag-kp" data-role="${kp.role}">
        <circle cx="${cx}" cy="${cy}" r="12" fill="${color}" fill-opacity="0.3" stroke="${color}" stroke-width="2"/>
        <circle cx="${cx}" cy="${cy}" r="5" fill="#FFFFFF"/>
        <text x="${cx}" y="${cy - 16}" fill="#FFFFFF" font-size="11" font-weight="bold" font-family="'JetBrains Mono', monospace" text-anchor="middle">${kp.role.toUpperCase()}</text>
      </g>
    `;
  });

  handlesSvg.innerHTML = svgHtml;

  // Attach drag events to SVG handles
  attachHandleListeners();
}

function attachHandleListeners() {
  handlesSvg.querySelectorAll(".drag-kp").forEach(el => {
    el.addEventListener("mousedown", e => {
      e.stopPropagation();
      state.isDraggingKp = el.dataset.role;
    });
  });

  handlesSvg.querySelectorAll(".drag-handle").forEach(el => {
    el.addEventListener("mousedown", e => {
      e.stopPropagation();
      state.isDraggingHandle = { role: el.dataset.role, type: el.dataset.type };
    });
  });
}

function updateMagnifier(normX, normY) {
  const w = demoCanvas.width;
  const h = demoCanvas.height;
  const pxX = normX * w;
  const pxY = normY * h;

  magnifier.classList.remove("hidden");
  magnifierLabel.textContent = `X: ${Math.round(pxX)} Y: ${Math.round(pxY)}`;

  magnifierCtx.clearRect(0, 0, 120, 120);
  const cropSize = 80;
  magnifierCtx.drawImage(
    demoCanvas,
    pxX - cropSize / 2, pxY - cropSize / 2, cropSize, cropSize,
    0, 0, 120, 120
  );
}

// ==========================================
// 5. EVENT LISTENERS & TRANSPORT CONTROLS
// ==========================================

function seekToFrame(frame) {
  state.currentFrame = Math.min(Math.max(frame, 0), state.project.video.frameCount - 1);
  renderCurrentFrame();
}

function stepFrame(delta) {
  seekToFrame(state.currentFrame + delta);
}

function togglePlay() {
  state.isPlaying = !state.isPlaying;
  if (state.isPlaying) {
    playIcon.classList.add("hidden");
    pauseIcon.classList.remove("hidden");
    playLoop();
  } else {
    playIcon.classList.remove("hidden");
    pauseIcon.classList.add("hidden");
  }
}

let lastTimestamp = 0;
function playLoop(timestamp = 0) {
  if (!state.isPlaying) return;

  const fps = 30 * state.playbackSpeed;
  const frameDurationMs = 1000 / fps;

  if (timestamp - lastTimestamp >= frameDurationMs) {
    lastTimestamp = timestamp;
    state.currentFrame++;
    if (state.currentFrame >= state.project.video.frameCount) {
      state.currentFrame = 0;
    }
    renderCurrentFrame();
  }

  requestAnimationFrame(playLoop);
}

// Viewport interactions
handlesSvg.addEventListener("mousemove", e => {
  const rect = handlesSvg.getBoundingClientRect();
  const normX = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1);
  const normY = Math.min(Math.max((e.clientY - rect.top) / rect.height, 0), 1);

  state.mousePos = { x: e.clientX, y: e.clientY, normX, normY };
  updateMagnifier(normX, normY);

  if (state.isDraggingKp) {
    const kps = state.project.trajectories[0].keypoints;
    const target = kps.find(k => k.role === state.isDraggingKp);
    if (target) {
      target.x = normX;
      target.y = normY;
      target.handleIn = null;
      target.handleOut = null;
      renderCurrentFrame();
      renderSvgHandles();
    }
  } else if (state.isDraggingHandle) {
    const kps = state.project.trajectories[0].keypoints;
    const target = kps.find(k => k.role === state.isDraggingHandle.role);
    if (target) {
      target[state.isDraggingHandle.type] = { x: normX, y: normY };
      renderCurrentFrame();
      renderSvgHandles();
    }
  }
});

handlesSvg.addEventListener("mouseleave", () => {
  magnifier.classList.add("hidden");
  state.isDraggingKp = null;
  state.isDraggingHandle = null;
});

window.addEventListener("mouseup", () => {
  state.isDraggingKp = null;
  state.isDraggingHandle = null;
});

handlesSvg.addEventListener("click", e => {
  if (state.isDraggingKp || state.isDraggingHandle) return;

  const rect = handlesSvg.getBoundingClientRect();
  const normX = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1);
  const normY = Math.min(Math.max((e.clientY - rect.top) / rect.height, 0), 1);

  const role = state.activeRoleToSet;
  if (!role) return;

  const kps = state.project.trajectories[0].keypoints;
  const idx = kps.findIndex(k => k.role === role);
  const newKp = {
    role,
    frameIndex: state.currentFrame,
    x: normX,
    y: normY,
    handleIn: null,
    handleOut: null
  };

  if (idx >= 0) {
    kps[idx] = newKp;
  } else {
    kps.push(newKp);
  }

  updateScrubberMarks();
  renderCurrentFrame();
  renderSvgHandles();
});

// UI Controls
scrubber.addEventListener("input", e => seekToFrame(parseInt(e.target.value, 10)));
playPauseBtn.addEventListener("click", togglePlay);
document.getElementById("btn-step-prev").addEventListener("click", () => stepFrame(-1));
document.getElementById("btn-step-next").addEventListener("click", () => stepFrame(1));
document.getElementById("btn-step-prev-10").addEventListener("click", () => stepFrame(-10));
document.getElementById("btn-step-next-10").addEventListener("click", () => stepFrame(10));
selectSpeed.addEventListener("change", e => state.playbackSpeed = parseFloat(e.target.value));

// Keypoint buttons
document.querySelectorAll(".kp-btn").forEach(btn => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".kp-btn").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    state.activeRoleToSet = btn.dataset.role;

    // Jump to keypoint's frame if exists
    const kp = state.project.trajectories[0].keypoints.find(k => k.role === btn.dataset.role);
    if (kp) seekToFrame(kp.frameIndex);
  });
});

// Curve mode buttons
document.querySelectorAll(".toggle-btn").forEach(btn => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".toggle-btn").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    state.project.trajectories[0].mode = btn.dataset.mode;
    renderCurrentFrame();
    renderSvgHandles();
  });
});

// Palette swatches
document.querySelectorAll(".palette-swatch").forEach(swatch => {
  swatch.addEventListener("click", () => {
    document.querySelectorAll(".palette-swatch").forEach(s => s.classList.remove("active"));
    swatch.classList.add("active");
    const pal = swatch.dataset.palette;
    state.project.trajectories[0].style.palette = pal;
    state.project.trajectories[0].style.gradient = PALETTES[pal];
    renderCurrentFrame();
  });
});

// Trail mode pills
document.querySelectorAll(".mode-pill").forEach(pill => {
  pill.addEventListener("click", () => {
    document.querySelectorAll(".mode-pill").forEach(p => p.classList.remove("active"));
    pill.classList.add("active");
    state.project.trajectories[0].style.trailMode = pill.dataset.trail;
    renderCurrentFrame();
  });
});

// Glow slider
const inputGlow = document.getElementById("input-glow");
inputGlow.addEventListener("input", e => {
  const val = parseInt(e.target.value, 10);
  document.getElementById("glow-val").textContent = `${val}%`;
  state.project.trajectories[0].style.glow = val / 100;
  renderCurrentFrame();
});

// Width slider
const inputWidth = document.getElementById("input-width");
inputWidth.addEventListener("input", e => {
  const val = parseInt(e.target.value, 10);
  document.getElementById("width-val").textContent = `${val} px`;
  state.project.trajectories[0].style.lineWidth = val;
  renderCurrentFrame();
});

// Distance HUD controls
document.getElementById("check-hud-visible").addEventListener("change", e => {
  state.project.trajectories[0].distance.visible = e.target.checked;
  renderCurrentFrame();
});

document.getElementById("input-distance-val").addEventListener("input", e => {
  state.project.trajectories[0].distance.value = parseFloat(e.target.value) || 250;
  renderCurrentFrame();
});

document.getElementById("select-distance-unit").addEventListener("change", e => {
  state.project.trajectories[0].distance.unit = e.target.value;
  renderCurrentFrame();
});

// Custom video file upload
document.getElementById("video-file-input").addEventListener("change", e => {
  const file = e.target.files[0];
  if (!file) return;

  const url = URL.createObjectURL(file);
  videoEl.srcObject = null;
  videoEl.src = url;
  videoEl.onloadedmetadata = () => {
    state.project.video.width = videoEl.videoWidth || 1080;
    state.project.video.height = videoEl.videoHeight || 1920;
    state.project.video.durationUs = Math.round(videoEl.duration * 1000000);
    state.project.video.frameCount = Math.round(videoEl.duration * 30);
    scrubber.max = state.project.video.frameCount - 1;
    resizeCanvas();
    seekToFrame(0);
  };
});

// Demo switchers
document.getElementById("btn-load-demo").addEventListener("click", () => {
  state.project.trajectories[0].sport = "golf";
  initDemoScene("golf");
});

document.getElementById("btn-load-disc").addEventListener("click", () => {
  state.project.trajectories[0].sport = "disc";
  state.project.trajectories[0].mode = "catmullRom";
  initDemoScene("disc");
});

// JSON Save & Load
document.getElementById("btn-save-project").addEventListener("click", () => {
  const jsonStr = JSON.stringify(state.project, null, 2);
  const blob = new Blob([jsonStr], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `acetrace_project_${Date.now()}.json`;
  a.click();
});

document.getElementById("json-file-input").addEventListener("change", e => {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = ev => {
    try {
      const proj = JSON.parse(ev.target.result);
      state.project = proj;
      updateScrubberMarks();
      renderCurrentFrame();
      renderSvgHandles();
      alert("Đã tải project JSON thành công!");
    } catch (err) {
      alert("Lỗi phân tích cú pháp JSON: " + err.message);
    }
  };
  reader.readAsText(file);
});

// ==========================================
// 6. VIDEO EXPORTER PIPELINE (Canvas MediaRecorder)
// ==========================================

const exportModal = document.getElementById("export-modal");
const exportProgressFill = document.getElementById("export-progress-fill");
const exportStatusText = document.getElementById("export-status-text");
const exportResultPreview = document.getElementById("export-result-preview");
const exportPreviewVideo = document.getElementById("export-preview-video");
const downloadLink = document.getElementById("download-link");
const btnCloseModal = document.getElementById("btn-close-modal");

document.getElementById("btn-export-video").addEventListener("click", () => {
  startExportPipeline();
});

btnCloseModal.addEventListener("click", () => {
  exportModal.classList.add("hidden");
  exportResultPreview.classList.add("hidden");
});

async function startExportPipeline() {
  exportModal.classList.remove("hidden");
  exportResultPreview.classList.add("hidden");
  exportProgressFill.style.width = "0%";
  exportStatusText.textContent = "Đang khởi tạo bộ mã hóa MediaRecorder...";

  const exportCanvas = document.createElement("canvas");
  exportCanvas.width = state.project.video.width;
  exportCanvas.height = state.project.video.height;
  const exportCtx = exportCanvas.getContext("2d");

  const stream = exportCanvas.captureStream(30);
  const recorder = new MediaRecorder(stream, {
    mimeType: MediaRecorder.isTypeSupported("video/webm;codecs=vp9") ? "video/webm;codecs=vp9" : "video/webm",
    videoBitsPerSecond: 8000000
  });

  const chunks = [];
  recorder.ondataavailable = e => {
    if (e.data.size > 0) chunks.push(e.data);
  };

  recorder.onstop = () => {
    const blob = new Blob(chunks, { type: "video/webm" });
    const url = URL.createObjectURL(blob);
    exportPreviewVideo.src = url;
    downloadLink.href = url;
    downloadLink.download = `acetrace_export_${Date.now()}.webm`;
    exportStatusText.textContent = "Xuất video thành công (100%)!";
    exportProgressFill.style.width = "100%";
    exportResultPreview.classList.remove("hidden");
  };

  recorder.start();

  const totalFrames = state.project.video.frameCount;
  for (let f = 0; f < totalFrames; f++) {
    // 1. Draw video background
    renderDemoFrame(f, state.project.trajectories[0].sport);
    exportCtx.drawImage(demoCanvas, 0, 0, exportCanvas.width, exportCanvas.height);

    // 2. Composite identical OverlayRenderer
    renderOverlay(exportCtx, exportCanvas.width, exportCanvas.height, f, state.project);

    const pct = Math.round(((f + 1) / totalFrames) * 100);
    exportProgressFill.style.width = `${pct}%`;
    exportStatusText.textContent = `Đang render khung hình ${f + 1} / ${totalFrames} (${pct}%)...`;

    await new Promise(r => setTimeout(r, 16)); // Frame step delay
  }

  recorder.stop();
}

// ==========================================
// INITIALIZATION
// ==========================================

window.addEventListener("resize", resizeCanvas);

// Initialize with default demo scene
initDemoScene("golf");
window.dispatchEvent(new Event("resize"));
