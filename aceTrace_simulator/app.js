/**
 * AceTrace Studio - Interactive Web Simulator
 * Implements 100% of the canonical mathematics and rendering specification
 * from aceTrace_spec/schema.json and docs/algorithms.md
 */

// ==========================================
// GLOBAL STATE (Adhering to Project JSON Schema v1)
// ==========================================
const state = {
  project: {
    schemaVersion: 1,
    id: "proj-" + Date.now(),
    createdAt: new Date().toISOString(),
    video: {
      uri: "demo-golf-vertical",
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
          { role: "start", frameIndex: 30, x: 0.42, y: 0.78, handleIn: null, handleOut: null },
          { role: "apex", frameIndex: 68, x: 0.55, y: 0.22, handleIn: null, handleOut: null },
          { role: "landing", frameIndex: 110, x: 0.72, y: 0.65, handleIn: null, handleOut: null }
        ],
        style: {
          palette: "neonCyan",
          gradient: ["#00E5FF", "#FF2D95", "#FFB300"],
          lineWidth: 12,
          taper: 0.40,
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

  // Authoritative Video Rect inside viewport (container px, letterbox/pillarbox accounted for)
  videoContentRect: { x: 0, y: 0, w: 1080, h: 1920 },
  debugRect: true,

  // Playback & UI State
  currentFrame: 0,
  isPlaying: false,
  playbackSpeed: 1.0,
  activeRoleToSet: "start",
  isDraggingKp: null,
  isDraggingHandle: null,
  mousePos: { x: 0, y: 0, normX: 0, normY: 0 },
  isMouseInsideRect: false
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
const statRect = document.getElementById("stat-rect");
const playPauseBtn = document.getElementById("btn-play-pause");
const playIcon = document.getElementById("play-icon");
const pauseIcon = document.getElementById("pause-icon");
const selectSpeed = document.getElementById("select-speed");

// Magnifier
const magnifier = document.getElementById("magnifier");
const magnifierCanvas = document.getElementById("magnifier-canvas");
const magnifierCtx = magnifierCanvas.getContext("2d");
const magnifierLabel = document.getElementById("magnifier-label");

// Demo Scene Canvas (offscreen render source for synthetic sports video)
let demoCanvas = document.createElement("canvas");
let demoCtx = demoCanvas.getContext("2d");
let demoStream = null;
let isUsingUserVideo = false;

// Preload realistic golf background images matching official Ace Trace App Store app
const demoBgVertical = new Image();
demoBgVertical.src = "assets/golf_vertical.jpg";
demoBgVertical.onload = () => { renderCurrentFrame(); };

const demoBgHorizontal = new Image();
demoBgHorizontal.src = "assets/golf_horizontal.jpg";
demoBgHorizontal.onload = () => { renderCurrentFrame(); };

// ==========================================
// 1. VIDEO RECT & COORDINATE ENGINE (Requirement A)
// ==========================================

/**
 * ONLY ONE function to compute videoRect = {x, y, w, h} (pixel in container).
 * Fits video into container preserving exact aspect ratio AFTER rotation.
 * 
 * Formula:
 * scale = Math.min(containerW / videoW, containerH / videoH);
 * w = Math.round(videoW * scale);
 * h = Math.round(videoH * scale);
 * x = Math.round((containerW - w) / 2);
 * y = Math.round((containerH - h) / 2);
 */
function computeVideoRect(containerW, containerH, videoW, videoH, rotationDegrees = 0) {
  if (!videoW || !videoH || !containerW || !containerH) {
    return { x: 0, y: 0, w: containerW || 0, h: containerH || 0, scale: 1 };
  }
  // Video dimensions after rotation (e.g. 90 or 270 degrees swaps width and height)
  let vW = videoW;
  let vH = videoH;
  if (rotationDegrees === 90 || rotationDegrees === 270) {
    vW = videoH;
    vH = videoW;
  }

  const scale = Math.min(containerW / vW, containerH / vH);
  const w = Math.round(vW * scale);
  const h = Math.round(vH * scale);
  const x = Math.round((containerW - w) / 2);
  const y = Math.round((containerH - h) / 2);

  return { x, y, w, h, scale, vW, vH };
}

// Canonical alias ensuring 100% interoperability
const computeVideoContentRect = computeVideoRect;

function clamp01(val) {
  return Math.min(Math.max(val, 0.0), 1.0);
}

function normToScreen(nx, ny, rect) {
  return {
    x: rect.x + nx * rect.w,
    y: rect.y + ny * rect.h
  };
}

function screenToNorm(sx, sy, rect) {
  return {
    nx: (sx - rect.x) / rect.w,
    ny: (sy - rect.y) / rect.h
  };
}

// ==========================================
// 2. CANONICAL MATHEMATICAL ENGINE (Splines & Easing)
// ==========================================

function evaluateCubicBezier(p0, p1, p2, p3, t) {
  const u = clamp01(t);
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

/**
 * Resolves default handles for 3-point setups (Start -> Apex -> Landing).
 * All handle coordinates are normalized 0..1 relative to video rect.
 */
function resolveKeypointHandles(keypoints) {
  if (keypoints.length !== 3) return keypoints;

  const [start, apex, landing] = keypoints;

  const startHandleOut = start.handleOut || {
    x: clamp01(start.x + (apex.x - start.x) / 3),
    y: clamp01(start.y + (apex.y - start.y) / 3)
  };

  const apexHandleIn = apex.handleIn || {
    x: clamp01(apex.x - (apex.x - start.x) / 3),
    y: clamp01(apex.y)
  };

  const apexHandleOut = apex.handleOut || {
    x: clamp01(apex.x + (landing.x - apex.x) / 3),
    y: clamp01(apex.y)
  };

  const landingHandleIn = landing.handleIn || {
    x: clamp01(landing.x - (landing.x - apex.x) / 3),
    y: clamp01(landing.y - (landing.y - apex.y) / 3)
  };

  return [
    { ...start, handleIn: null, handleOut: startHandleOut },
    { ...apex, handleIn: apexHandleIn, handleOut: apexHandleOut },
    { ...landing, handleIn: landingHandleIn, handleOut: null }
  ];
}

function evaluateBezierTrajectory(resolvedKps, uGlobal) {
  const clampedU = clamp01(uGlobal);
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

  const actualT = t1 + clamp01(t) * (t2 - t1);

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

function evaluateCatmullRomTrajectory(resolvedKps, u) {
  const pStart = resolvedKps[0];
  const pApex = resolvedKps[1];
  const pLanding = resolvedKps[2];
  if (u <= 0.5) {
    const t = u / 0.5;
    return evaluateCatmullRom(pStart, pStart, pApex, pLanding, t);
  } else {
    const t = (u - 0.5) / 0.5;
    return evaluateCatmullRom(pStart, pApex, pLanding, pLanding, t);
  }
}

/**
 * Time Mapping with gravitational easing:
 * Ascent (Start -> Apex): Quadratic Ease-Out (decelerating against gravity)
 * Descent (Apex -> Landing): Quadratic Ease-In (accelerating due to gravity)
 */
function computeTimeMapping(frameIndex, startFrame, apexFrame, landingFrame) {
  if (frameIndex < startFrame) {
    return { frame: frameIndex, progress: 0.0, u: 0.0, status: "before_start" };
  }
  if (frameIndex > landingFrame) {
    return { frame: frameIndex, progress: 1.0, u: 1.0, status: "landed" };
  }
  if (frameIndex <= apexFrame) {
    const totalAscent = Math.max(apexFrame - startFrame, 1);
    const s = (frameIndex - startFrame) / totalAscent;
    const sEased = 1 - (1 - s) * (1 - s); // Quadratic Ease-out ascent
    const u = Math.min(Math.max(0.5 * sEased, 0.0), 0.5);
    return { frame: frameIndex, progress: u, u: u, status: "ascending" };
  } else {
    const totalDescent = Math.max(landingFrame - apexFrame, 1);
    const s = (frameIndex - apexFrame) / totalDescent;
    const sEased = s * s; // Quadratic Ease-in descent
    const u = Math.min(Math.max(0.5 + 0.5 * sEased, 0.5), 1.0);
    return { frame: frameIndex, progress: u, u: u, status: "descending" };
  }
}

function computeEasedDistance(total, progress, easing = "easeOut") {
  const p = clamp01(progress);
  let factor = p;
  if (easing === "easeOut") {
    factor = 1 - (1 - p) * (1 - p);
  } else if (easing === "easeInOut") {
    factor = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
  }
  return total * factor;
}

// ==========================================
// 3. UNIFIED OVERLAY RENDERER (Used identically by Preview & Export)
// ==========================================

/**
 * Pure rendering function: draws trajectories, glow layers, impact flash,
 * dynamic distance HUD, and watermark onto any target context within given rect.
 * 
 * @param {CanvasRenderingContext2D} ctx Target 2D context
 * @param {Object} project Project data adhering to Schema v1
 * @param {number} frameIndex Current frame index
 * @param {Object} rect { x, y, w, h } Target content rectangle
 */
function renderOverlay(ctx, project, frameIndex, rect) {
  if (!rect || rect.w <= 0 || rect.h <= 0) return;

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
    const numSamples = 100;
    const points = [];
    const step = (uEnd - uStart) / Math.max(numSamples, 1);

    for (let i = 0; i <= numSamples; i++) {
      const u = clamp01(uStart + i * step);
      let pt;
      if (trajectory.mode === "catmullRom") {
        pt = evaluateCatmullRomTrajectory(resolvedKps, u);
      } else {
        pt = evaluateBezierTrajectory(resolvedKps, u);
      }
      points.push({
        x: rect.x + pt.x * rect.w,
        y: rect.y + pt.y * rect.h
      });
    }

    if (points.length < 2) continue;

    // Normal vectors at each sampled point
    const normals = [];
    for (let i = 0; i < points.length; i++) {
      let tx, ty;
      if (i === 0) {
        tx = points[1].x - points[0].x;
        ty = points[1].y - points[0].y;
      } else if (i === points.length - 1) {
        tx = points[i].x - points[i - 1].x;
        ty = points[i].y - points[i - 1].y;
      } else {
        tx = points[i + 1].x - points[i - 1].x;
        ty = points[i + 1].y - points[i - 1].y;
      }
      const len = Math.hypot(tx, ty);
      if (len > 1e-5) {
        // Unit normal (-ty/len, tx/len)
        normals.push({ x: -ty / len, y: tx / len });
      } else {
        normals.push({ x: 0, y: -1 });
      }
    }

    // Width scale and taper calculation
    const scaleFactor = Math.max(rect.w / 1080, 0.45);
    const baseWidth = Math.max((style.lineWidth || 12) * scaleFactor * 1.5, 4);
    const taper = style.taper !== undefined ? clamp01(style.taper) : 0.4;
    const glow = clamp01(style.glow);

    // Compute width w(s) at each point: s from 0 (tail) to 1 (head)
    const leftEdges = [];
    const rightEdges = [];

    for (let i = 0; i < points.length; i++) {
      const s = i / (points.length - 1);
      let w;
      if (style.trailMode === "comet") {
        // Comet tapers sharply to 0 at tail
        w = baseWidth * Math.pow(s, 1.25);
      } else {
        // Tracer & Full: tapers from tail to head
        w = baseWidth * (taper + (1 - taper) * Math.sqrt(s));
      }
      const halfW = Math.max(w * 0.5, 0.5);
      const nx = normals[i].x;
      const ny = normals[i].y;

      leftEdges.push({
        x: points[i].x + nx * halfW,
        y: points[i].y + ny * halfW
      });
      rightEdges.push({
        x: points[i].x - nx * halfW,
        y: points[i].y - ny * halfW
      });
    }

    // Build closed Ribbon Polygon Path
    const ribbonPath = new Path2D();
    ribbonPath.moveTo(leftEdges[0].x, leftEdges[0].y);
    for (let i = 1; i < leftEdges.length; i++) {
      ribbonPath.lineTo(leftEdges[i].x, leftEdges[i].y);
    }
    // Rounded bullet head cap
    const headPt = points[points.length - 1];
    const headTangentX = points[points.length - 1].x - points[points.length - 2].x;
    const headTangentY = points[points.length - 1].y - points[points.length - 2].y;
    const headLen = Math.hypot(headTangentX, headTangentY);
    if (headLen > 1e-4) {
      const utx = headTangentX / headLen;
      const uty = headTangentY / headLen;
      const capTip = {
        x: headPt.x + utx * (baseWidth * 0.4),
        y: headPt.y + uty * (baseWidth * 0.4)
      };
      ribbonPath.quadraticCurveTo(capTip.x, capTip.y, rightEdges[rightEdges.length - 1].x, rightEdges[rightEdges.length - 1].y);
    } else {
      ribbonPath.lineTo(rightEdges[rightEdges.length - 1].x, rightEdges[rightEdges.length - 1].y);
    }

    for (let i = rightEdges.length - 2; i >= 0; i--) {
      ribbonPath.lineTo(rightEdges[i].x, rightEdges[i].y);
    }
    ribbonPath.closePath();

    // Centerline Spine Path for sharp core line
    const spinePath = new Path2D();
    spinePath.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length; i++) {
      spinePath.lineTo(points[i].x, points[i].y);
    }

    // Gradient along trajectory
    const grad = ctx.createLinearGradient(
      points[0].x, points[0].y,
      points[points.length - 1].x, points[points.length - 1].y
    );
    const colors = style.gradient || PALETTES.neonCyan;
    colors.forEach((c, idx) => {
      grad.addColorStop(idx / Math.max(colors.length - 1, 1), c);
    });

    // --- GLOW LAYER 1: Wide Blur Bloom ---
    ctx.save();
    ctx.shadowColor = colors[0];
    ctx.shadowBlur = baseWidth * 3.8 * glow;
    ctx.fillStyle = colors[0];
    ctx.globalAlpha = 0.22 * glow;
    ctx.fill(ribbonPath);
    ctx.restore();

    // --- GLOW LAYER 2: Vibrant Mid Glow ---
    ctx.save();
    ctx.shadowColor = colors[1] || colors[0];
    ctx.shadowBlur = baseWidth * 1.6 * glow;
    ctx.fillStyle = grad;
    ctx.globalAlpha = 0.70 * glow;
    ctx.fill(ribbonPath);
    ctx.restore();

    // --- GLOW LAYER 3: Sharp Whitish Core Highlight ---
    ctx.save();
    ctx.lineWidth = Math.max(baseWidth * 0.32, 1.8);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.strokeStyle = "#FFFFFF";
    ctx.shadowColor = "#FFFFFF";
    ctx.shadowBlur = Math.max(baseWidth * 0.5 * glow, 2);
    ctx.globalAlpha = 0.95;
    ctx.stroke(spinePath);
    ctx.restore();

    // Impact Flash at Start point
    if (style.showImpactFlash && flightState.progress < 0.15) {
      const flashAlpha = Math.max(0, 1 - flightState.progress / 0.15);
      ctx.save();
      ctx.fillStyle = "#FFFFFF";
      ctx.shadowColor = "#FFFFFF";
      ctx.shadowBlur = baseWidth * 3;
      ctx.globalAlpha = flashAlpha;
      ctx.beginPath();
      ctx.arc(points[0].x, points[0].y, baseWidth * 2.2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

    // --- Ball / Spark at Head ---
    const ballPos = points[points.length - 1];
    const ballRadius = Math.max(baseWidth * 0.85, 4.0);

    ctx.save();
    // Glowing radial flare
    const flareGrad = ctx.createRadialGradient(ballPos.x, ballPos.y, 0, ballPos.x, ballPos.y, ballRadius * 2.8);
    flareGrad.addColorStop(0, "#FFFFFF");
    flareGrad.addColorStop(0.35, colors[colors.length - 1] || "#00E5FF");
    flareGrad.addColorStop(1, "rgba(0, 229, 255, 0)");
    ctx.fillStyle = flareGrad;
    ctx.beginPath();
    ctx.arc(ballPos.x, ballPos.y, ballRadius * 2.8, 0, Math.PI * 2);
    ctx.fill();

    // White core sphere
    ctx.fillStyle = "#FFFFFF";
    ctx.shadowColor = "#FFFFFF";
    ctx.shadowBlur = ballRadius * 2.2;
    ctx.beginPath();
    ctx.arc(ballPos.x, ballPos.y, ballRadius, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    // Distance HUD Label (Scaled to ~6% of videoRect.h, clamped inside rect)
    if (trajectory.distance.visible && points.length > 0) {
      const dist = Math.round(
        computeEasedDistance(trajectory.distance.value, flightState.progress, trajectory.distance.easing)
      );
      const distText = `${dist} ${trajectory.distance.unit}`;

      // Large bold text (~6% video height)
      let hudFontSize = Math.max(Math.round(rect.h * 0.060), 12);
      ctx.font = `800 ${hudFontSize}px 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif`;
      let metrics = ctx.measureText(distText);

      // Measure and ensure badge fits comfortably inside rect width
      const maxAllowedBadgeW = rect.w * 0.82;
      let paddingX = Math.round(hudFontSize * 0.55);
      let badgeW = metrics.width + paddingX * 2;
      if (badgeW > maxAllowedBadgeW) {
        hudFontSize = Math.max(Math.floor(hudFontSize * (maxAllowedBadgeW / badgeW)), 10);
        ctx.font = `800 ${hudFontSize}px 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif`;
        metrics = ctx.measureText(distText);
        paddingX = Math.round(hudFontSize * 0.55);
        badgeW = metrics.width + paddingX * 2;
      }
      const paddingY = Math.round(hudFontSize * 0.32);
      const badgeH = hudFontSize + paddingY * 2;

      const headNormal = normals[normals.length - 1];
      const offsetDist = Math.max(hudFontSize * 1.3, 22);

      // Offset along normal direction (away from trajectory curve)
      let badgeX = ballPos.x + headNormal.x * offsetDist;
      let badgeY = ballPos.y + headNormal.y * offsetDist;

      // Safe clamp inside videoRect so entire badge capsule is inside rect
      const halfW = badgeW / 2;
      const halfH = badgeH / 2;
      const pad = 4;
      badgeX = Math.min(Math.max(badgeX, rect.x + halfW + pad), rect.x + rect.w - halfW - pad);
      badgeY = Math.min(Math.max(badgeY, rect.y + halfH + pad), rect.y + rect.h - halfH - pad);

      renderDistanceBadge(ctx, badgeX, badgeY, distText, hudFontSize, badgeW, badgeH);
    }

    ctx.restore();
  }

  // Watermark (~2.2% of videoRect.h, positioned strictly inside bottom-right of videoContentRect)
  if (project.export && project.export.watermark) {
    ctx.save();
    let wmFontSize = Math.max(Math.round(rect.h * 0.022), 8);
    const wmText = "Traced with AceTrace";
    ctx.font = `bold ${wmFontSize}px 'JetBrains Mono', monospace`;
    let wmMetrics = ctx.measureText(wmText);
    const maxWmW = rect.w * 0.85;
    if (wmMetrics.width > maxWmW) {
      wmFontSize = Math.max(Math.floor(wmFontSize * (maxWmW / wmMetrics.width)), 7);
      ctx.font = `bold ${wmFontSize}px 'JetBrains Mono', monospace`;
      wmMetrics = ctx.measureText(wmText);
    }
    const padX = Math.max(Math.round(rect.w * 0.03), 8);
    const padY = Math.max(Math.round(rect.h * 0.02), 6);
    ctx.fillStyle = "rgba(255, 255, 255, 0.45)";
    ctx.textAlign = "right";
    ctx.textBaseline = "bottom";
    ctx.fillText(wmText, rect.x + rect.w - padX, rect.y + rect.h - padY);
    ctx.restore();
  }
}

function renderDistanceBadge(ctx, x, y, text, fontSize, w, h) {
  ctx.save();
  ctx.font = `800 ${fontSize}px 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif`;

  const rx = x - w / 2;
  const ry = y - h / 2;

  // Frosted dark badge capsule
  ctx.save();
  ctx.fillStyle = "rgba(10, 10, 16, 0.88)";
  ctx.strokeStyle = "rgba(0, 229, 255, 0.75)";
  ctx.lineWidth = Math.max(fontSize * 0.08, 1.8);
  ctx.shadowColor = "rgba(0, 0, 0, 0.7)";
  ctx.shadowBlur = 10;
  ctx.shadowOffsetY = 3;

  ctx.beginPath();
  ctx.roundRect(rx, ry, w, h, Math.round(h / 2));
  ctx.fill();
  ctx.stroke();
  ctx.restore();

  // Text with heavy dark stroke and bright fill for readability on any background
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";

  ctx.lineWidth = Math.max(fontSize * 0.16, 3.0);
  ctx.strokeStyle = "rgba(0, 0, 0, 0.9)";
  ctx.strokeText(text, x, y);

  ctx.fillStyle = "#FFFFFF";
  ctx.fillText(text, x, y);

  ctx.restore();
}

// ==========================================
// 4. SYNTHETIC SPORTS DEMO VIDEO GENERATOR (Vertical 9:16 & Horizontal 16:9)
// ==========================================

function initDemoScene(preset = "golf-vertical") {
  isUsingUserVideo = false;
  videoEl.classList.add("hidden");

  let w = 1080;
  let h = 1920;
  let sport = "golf";
  let mode = "bezier";

  if (preset === "golf-horizontal") {
    w = 1920;
    h = 1080;
    sport = "golf";
    mode = "bezier";
  } else if (preset === "disc-vertical") {
    w = 1080;
    h = 1920;
    sport = "disc";
    mode = "catmullRom";
  } else if (preset === "disc-horizontal") {
    w = 1920;
    h = 1080;
    sport = "disc";
    mode = "catmullRom";
  }

  demoCanvas.width = w;
  demoCanvas.height = h;

  // Generate PTS timestamps (30 fps, 120 frames = 4.0s)
  const pts = [];
  for (let f = 0; f < 120; f++) {
    pts.push(Math.round((f / 30) * 1000000));
  }

  state.project.video = {
    uri: "demo-" + preset,
    width: w,
    height: h,
    rotationDegrees: 0,
    durationUs: 4000000,
    frameCount: 120,
    ptsUs: pts
  };

  state.project.trajectories[0].sport = sport;
  state.project.trajectories[0].mode = mode;

  // Adjust keypoints to be well-proportioned for 16:9 or 9:16
  if (w > h) {
    // Horizontal 16:9 keypoints
    state.project.trajectories[0].keypoints = [
      { role: "start", frameIndex: 30, x: 0.22, y: 0.80, handleIn: null, handleOut: null },
      { role: "apex", frameIndex: 68, x: 0.52, y: 0.25, handleIn: null, handleOut: null },
      { role: "landing", frameIndex: 110, x: 0.82, y: 0.70, handleIn: null, handleOut: null }
    ];
  } else {
    // Vertical 9:16 keypoints (Landing x=0.72 is cleanly inside the video!)
    state.project.trajectories[0].keypoints = [
      { role: "start", frameIndex: 30, x: 0.42, y: 0.78, handleIn: null, handleOut: null },
      { role: "apex", frameIndex: 68, x: 0.55, y: 0.22, handleIn: null, handleOut: null },
      { role: "landing", frameIndex: 110, x: 0.72, y: 0.65, handleIn: null, handleOut: null }
    ];
  }

  // Update UI toggles
  document.getElementById("mode-bezier").classList.toggle("active", mode === "bezier");
  document.getElementById("mode-catmull").classList.toggle("active", mode === "catmullRom");

  const phoneFrame = document.getElementById("phone-frame");
  if (phoneFrame) {
    phoneFrame.classList.toggle("horizontal", w > h);
  }

  scrubber.max = 119;
  updateScrubberMarks();
  resizeCanvas();
  setTimeout(resizeCanvas, 40);
  seekToFrame(0);
}

function drawAthleteFigure(ctx, ballX, ballY, w, h, frameIndex, sport = "golf") {
  ctx.save();

  const charScale = (Math.min(w, h) / 1080) * 3.4;
  // Position athlete clearly to the left of the ball/tee so the START marker does NOT cover them
  const charX = ballX - 38 * charScale;
  const charY = ballY + 4 * charScale; // feet ground baseline

  // 1. Soft Ambient Ground Shadows
  ctx.fillStyle = "rgba(0, 0, 0, 0.4)";
  ctx.beginPath();
  // Shadow under lead (front) foot
  ctx.ellipse(charX + 16 * charScale, charY, 14 * charScale, 4.5 * charScale, 0, 0, Math.PI * 2);
  // Shadow under rear (back) foot
  ctx.ellipse(charX - 16 * charScale, charY, 12 * charScale, 4 * charScale, 0, 0, Math.PI * 2);
  ctx.fill();

  // 2. Tee in ground at (ballX, ballY)
  ctx.fillStyle = "#E2E8F0";
  ctx.beginPath();
  ctx.moveTo(ballX - 1.5 * charScale, ballY + 7 * charScale);
  ctx.lineTo(ballX + 1.5 * charScale, ballY + 7 * charScale);
  ctx.lineTo(ballX + 3.5 * charScale, ballY);
  ctx.lineTo(ballX - 3.5 * charScale, ballY);
  ctx.closePath();
  ctx.fill();

  if (sport === "golf") {
    // GOLF ATHLETE
    const isSwung = frameIndex >= 30;

    // --- LEGS & SHOES ---
    if (!isSwung) {
      // Address / Backswing Stance
      // Rear Foot & Shoe (Left side)
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.roundRect(charX - 25 * charScale, charY - 6 * charScale, 16 * charScale, 6 * charScale, 3 * charScale);
      ctx.fill();
      ctx.fillStyle = "#0F172A"; // Shoe sole
      ctx.fillRect(charX - 25 * charScale, charY - 1.5 * charScale, 16 * charScale, 1.5 * charScale);

      // Lead Foot & Shoe (Right side)
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.roundRect(charX + 8 * charScale, charY - 6 * charScale, 16 * charScale, 6 * charScale, 3 * charScale);
      ctx.fill();
      ctx.fillStyle = "#0F172A";
      ctx.fillRect(charX + 8 * charScale, charY - 1.5 * charScale, 16 * charScale, 1.5 * charScale);

      // Trousers (Athletic Charcoal Golf Slacks)
      ctx.fillStyle = "#334155";
      // Left leg
      ctx.beginPath();
      ctx.moveTo(charX - 20 * charScale, charY - 5 * charScale);
      ctx.lineTo(charX - 22 * charScale, charY - 28 * charScale);
      ctx.lineTo(charX - 7 * charScale, charY - 50 * charScale);
      ctx.lineTo(charX - 3 * charScale, charY - 50 * charScale);
      ctx.lineTo(charX - 10 * charScale, charY - 26 * charScale);
      ctx.lineTo(charX - 10 * charScale, charY - 5 * charScale);
      ctx.closePath();
      ctx.fill();

      // Right leg
      ctx.beginPath();
      ctx.moveTo(charX + 10 * charScale, charY - 5 * charScale);
      ctx.lineTo(charX + 14 * charScale, charY - 26 * charScale);
      ctx.lineTo(charX + 4 * charScale, charY - 50 * charScale);
      ctx.lineTo(charX - 3 * charScale, charY - 50 * charScale);
      ctx.lineTo(charX + 5 * charScale, charY - 28 * charScale);
      ctx.lineTo(charX + 18 * charScale, charY - 5 * charScale);
      ctx.closePath();
      ctx.fill();
    } else {
      // Classic Professional Follow-through Finish Stance
      // Front Foot firmly planted, pointed slightly open
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.roundRect(charX + 8 * charScale, charY - 6 * charScale, 17 * charScale, 6 * charScale, 3 * charScale);
      ctx.fill();
      ctx.fillStyle = "#0F172A";
      ctx.fillRect(charX + 8 * charScale, charY - 1.5 * charScale, 17 * charScale, 1.5 * charScale);

      // Back Foot on tiptoe (cleats visible, heel lifted!)
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.ellipse(charX - 16 * charScale, charY - 4 * charScale, 4.5 * charScale, 8 * charScale, Math.PI / 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#0F172A";
      ctx.fillRect(charX - 19 * charScale, charY - 3 * charScale, 5 * charScale, 2 * charScale);

      // Trousers in Follow-through (Hips rotated toward target)
      ctx.fillStyle = "#334155";
      // Back bent leg
      ctx.beginPath();
      ctx.moveTo(charX - 16 * charScale, charY - 6 * charScale);
      ctx.lineTo(charX - 12 * charScale, charY - 25 * charScale);
      ctx.lineTo(charX - 2 * charScale, charY - 48 * charScale);
      ctx.lineTo(charX + 5 * charScale, charY - 48 * charScale);
      ctx.lineTo(charX - 5 * charScale, charY - 25 * charScale);
      ctx.lineTo(charX - 11 * charScale, charY - 6 * charScale);
      ctx.closePath();
      ctx.fill();

      // Front straight leg
      ctx.beginPath();
      ctx.moveTo(charX + 10 * charScale, charY - 6 * charScale);
      ctx.lineTo(charX + 12 * charScale, charY - 28 * charScale);
      ctx.lineTo(charX + 8 * charScale, charY - 48 * charScale);
      ctx.lineTo(charX + 1 * charScale, charY - 48 * charScale);
      ctx.lineTo(charX + 4 * charScale, charY - 28 * charScale);
      ctx.lineTo(charX + 16 * charScale, charY - 6 * charScale);
      ctx.closePath();
      ctx.fill();
    }

    // --- BELT & HIPS ---
    ctx.fillStyle = "#1E293B";
    ctx.fillRect(charX - 7 * charScale, charY - 52 * charScale, 18 * charScale, 4 * charScale);
    ctx.fillStyle = "#CBD5E1"; // Buckle
    ctx.fillRect(charX + 1 * charScale, charY - 52 * charScale, 4 * charScale, 4 * charScale);

    // --- POLO SHIRT (Torso & Shoulders) ---
    // Vibrant Tour Red Polo with subtle shading
    const poloGrad = ctx.createLinearGradient(charX, charY - 88 * charScale, charX, charY - 52 * charScale);
    poloGrad.addColorStop(0, "#EF4444");
    poloGrad.addColorStop(1, "#B91C1C");
    ctx.fillStyle = poloGrad;

    ctx.beginPath();
    ctx.moveTo(charX - 8 * charScale, charY - 52 * charScale);
    ctx.lineTo(charX + 12 * charScale, charY - 52 * charScale);
    ctx.lineTo(charX + 16 * charScale, charY - 82 * charScale);
    ctx.lineTo(charX - 12 * charScale, charY - 82 * charScale);
    ctx.closePath();
    ctx.fill();

    // White Polo Collar
    ctx.fillStyle = "#FFFFFF";
    ctx.beginPath();
    ctx.moveTo(charX - 3 * charScale, charY - 82 * charScale);
    ctx.lineTo(charX + 6 * charScale, charY - 82 * charScale);
    ctx.lineTo(charX + 2 * charScale, charY - 76 * charScale);
    ctx.closePath();
    ctx.fill();

    // --- HEAD, FACE & TOUR CAP ---
    // Neck
    ctx.fillStyle = "#F5D0B5";
    ctx.fillRect(charX - 1 * charScale, charY - 88 * charScale, 6 * charScale, 8 * charScale);

    // Head
    ctx.fillStyle = "#F5D0B5";
    ctx.beginPath();
    ctx.ellipse(charX + 2 * charScale, charY - 94 * charScale, 7 * charScale, 8.5 * charScale, 0, 0, Math.PI * 2);
    ctx.fill();

    // Face features (Profile looking toward target / sky)
    ctx.fillStyle = "#1E293B"; // Sunglasses
    ctx.beginPath();
    ctx.roundRect(charX + 4 * charScale, charY - 96 * charScale, 5.5 * charScale, 3 * charScale, 1 * charScale);
    ctx.fill();

    // White Tour Cap with Visor
    ctx.fillStyle = "#FFFFFF";
    // Cap dome
    ctx.beginPath();
    ctx.ellipse(charX + 1 * charScale, charY - 99 * charScale, 7.5 * charScale, 5 * charScale, 0.1, Math.PI, 0);
    ctx.fill();
    // Cap visor (Branded curved visor pointing toward shot direction)
    ctx.beginPath();
    ctx.moveTo(charX + 3 * charScale, charY - 97 * charScale);
    ctx.lineTo(charX + 13 * charScale, charY - 99 * charScale);
    ctx.lineTo(charX + 11 * charScale, charY - 95 * charScale);
    ctx.lineTo(charX + 3 * charScale, charY - 94 * charScale);
    ctx.closePath();
    ctx.fill();

    // --- ARMS, GOLF GLOVE & CLUB ---
    if (!isSwung) {
      // Address / Swing Animation before frame 30
      const swingProg = frameIndex / 30; // 0..1
      // Arms hanging down towards ball at address
      ctx.fillStyle = "#EF4444"; // Sleeve
      ctx.beginPath();
      ctx.ellipse(charX + 4 * charScale, charY - 78 * charScale, 5 * charScale, 7 * charScale, 0.3, 0, Math.PI * 2);
      ctx.fill();

      // Forearms (Skin)
      ctx.fillStyle = "#F5D0B5";
      ctx.beginPath();
      ctx.moveTo(charX + 2 * charScale, charY - 74 * charScale);
      ctx.lineTo(charX + 15 * charScale, charY - 62 * charScale);
      ctx.lineTo(charX + 17 * charScale, charY - 64 * charScale);
      ctx.lineTo(charX + 4 * charScale, charY - 76 * charScale);
      ctx.closePath();
      ctx.fill();

      // White Golf Glove on hands
      const handsX = charX + 16 * charScale;
      const handsY = charY - 62 * charScale;
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.arc(handsX, handsY, 4 * charScale, 0, Math.PI * 2);
      ctx.fill();

      // Shaft & Club Head
      const clubAngle = -Math.PI * 0.7 + swingProg * Math.PI * 1.1;
      const shaftLen = 42 * charScale;
      const clubHeadX = !isSwung && swingProg < 0.1 ? ballX : handsX + Math.cos(clubAngle) * shaftLen;
      const clubHeadY = !isSwung && swingProg < 0.1 ? ballY : handsY + Math.sin(clubAngle) * shaftLen;

      // Shaft (Metallic steel)
      ctx.strokeStyle = "#CBD5E1";
      ctx.lineWidth = 2.5 * charScale;
      ctx.beginPath();
      ctx.moveTo(handsX, handsY);
      ctx.lineTo(clubHeadX, clubHeadY);
      ctx.stroke();

      // Driver head
      ctx.fillStyle = "#0F172A";
      ctx.beginPath();
      ctx.ellipse(clubHeadX, clubHeadY, 5 * charScale, 3.5 * charScale, 0.4, 0, Math.PI * 2);
      ctx.fill();

      // Golf Ball sitting on tee
      ctx.fillStyle = "#FFFFFF";
      ctx.shadowColor = "#FFFFFF";
      ctx.shadowBlur = 6;
      ctx.beginPath();
      ctx.arc(ballX, ballY, 4.5 * charScale, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    } else {
      // Classic Majestic High Follow-Through Finish (Arms up & wrap behind head)
      ctx.fillStyle = "#EF4444";
      ctx.beginPath();
      ctx.moveTo(charX + 4 * charScale, charY - 82 * charScale);
      ctx.lineTo(charX - 6 * charScale, charY - 100 * charScale);
      ctx.lineTo(charX - 12 * charScale, charY - 96 * charScale);
      ctx.lineTo(charX - 2 * charScale, charY - 80 * charScale);
      ctx.closePath();
      ctx.fill();

      // Forearms & Hands raised behind shoulders
      ctx.fillStyle = "#F5D0B5";
      ctx.beginPath();
      ctx.moveTo(charX - 6 * charScale, charY - 100 * charScale);
      ctx.lineTo(charX - 16 * charScale, charY - 108 * charScale);
      ctx.lineTo(charX - 18 * charScale, charY - 104 * charScale);
      ctx.lineTo(charX - 10 * charScale, charY - 96 * charScale);
      ctx.closePath();
      ctx.fill();

      // White Golf Glove Hands holding grip high
      const handsX = charX - 18 * charScale;
      const handsY = charY - 108 * charScale;
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.arc(handsX, handsY, 4.5 * charScale, 0, Math.PI * 2);
      ctx.fill();

      // Club Shaft resting over shoulder at finish
      const clubHeadX = charX + 16 * charScale;
      const clubHeadY = charY - 118 * charScale;
      ctx.strokeStyle = "#CBD5E1";
      ctx.lineWidth = 2.5 * charScale;
      ctx.beginPath();
      ctx.moveTo(handsX, handsY);
      ctx.lineTo(clubHeadX, clubHeadY);
      ctx.stroke();

      // Club Head
      ctx.fillStyle = "#0F172A";
      ctx.beginPath();
      ctx.ellipse(clubHeadX, clubHeadY, 5 * charScale, 3.5 * charScale, 0.8, 0, Math.PI * 2);
      ctx.fill();
    }
  } else {
    // DISC GOLF ATHLETE (Throwing pose)
    const isThrown = frameIndex >= 30;
    // Athletic shorts & athletic tee
    ctx.fillStyle = "#1E293B"; // Shorts
    ctx.fillRect(charX - 12 * charScale, charY - 40 * charScale, 24 * charScale, 20 * charScale);
    // Legs
    ctx.fillStyle = "#F5D0B5";
    ctx.fillRect(charX - 10 * charScale, charY - 20 * charScale, 7 * charScale, 16 * charScale);
    ctx.fillRect(charX + 3 * charScale, charY - 20 * charScale, 7 * charScale, 16 * charScale);
    // Shoes
    ctx.fillStyle = "#00E5FF";
    ctx.fillRect(charX - 12 * charScale, charY - 4 * charScale, 11 * charScale, 5 * charScale);
    ctx.fillRect(charX + 3 * charScale, charY - 4 * charScale, 11 * charScale, 5 * charScale);
    // Shirt
    ctx.fillStyle = "#0284C7";
    ctx.fillRect(charX - 14 * charScale, charY - 76 * charScale, 28 * charScale, 36 * charScale);
    // Head & Cap
    ctx.fillStyle = "#F5D0B5";
    ctx.beginPath();
    ctx.arc(charX, charY - 88 * charScale, 9 * charScale, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#0F172A";
    ctx.fillRect(charX - 9 * charScale, charY - 98 * charScale, 22 * charScale, 6 * charScale);

    // Throwing Arm
    ctx.fillStyle = "#F5D0B5";
    if (!isThrown) {
      // Wind up
      ctx.fillRect(charX - 28 * charScale, charY - 72 * charScale, 16 * charScale, 6 * charScale);
    } else {
      // Extended forward release
      ctx.fillRect(charX + 12 * charScale, charY - 72 * charScale, 26 * charScale, 6 * charScale);
    }
  }

  ctx.restore();
}

function renderDemoFrame(frameIndex, sport = "golf") {
  const ctx = demoCtx;
  const w = demoCanvas.width;
  const h = demoCanvas.height;

  // 0. Photorealistic Championship Golf Course Image (matches official Ace Trace App Store app!)
  const bgImg = (w > h) ? demoBgHorizontal : demoBgVertical;
  if (bgImg && bgImg.complete && bgImg.naturalWidth > 0 && sport === "golf") {
    ctx.drawImage(bgImg, 0, 0, w, h);

    // Ball flying in background after frame 30
    if (frameIndex >= 30) {
      const startKp = state.project.trajectories[0].keypoints[0];
      const apexKp = state.project.trajectories[0].keypoints[1];
      const landingKp = state.project.trajectories[0].keypoints[2];
      const fState = computeTimeMapping(frameIndex, startKp.frameIndex, apexKp.frameIndex, landingKp.frameIndex);

      const resolved = resolveKeypointHandles(state.project.trajectories[0].keypoints);
      let ballPos;
      if (state.project.trajectories[0].mode === "catmullRom") {
        ballPos = evaluateCatmullRomTrajectory(resolved, fState.u);
      } else {
        ballPos = evaluateBezierTrajectory(resolved, fState.u);
      }

      ctx.save();
      ctx.fillStyle = "#FFFFFF";
      ctx.shadowColor = "#FFFFFF";
      ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.arc(ballPos.x * w, ballPos.y * h, 7 * (Math.min(w, h) / 1080) * 2.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }
    return;
  }

  // 1. Fallback: Sky & Atmosphere
  const skyGrad = ctx.createLinearGradient(0, 0, 0, h * 0.6);
  skyGrad.addColorStop(0, "#122538");
  skyGrad.addColorStop(0.6, "#2E5576");
  skyGrad.addColorStop(1, "#E89B58");
  ctx.fillStyle = skyGrad;
  ctx.fillRect(0, 0, w, h * 0.6);

  // Distant Sun
  ctx.fillStyle = "#FFEAA6";
  ctx.shadowColor = "#FFAA00";
  ctx.shadowBlur = 35;
  ctx.beginPath();
  ctx.arc(w * 0.78, h * 0.35, Math.min(w, h) * 0.045, 0, Math.PI * 2);
  ctx.fill();
  ctx.shadowBlur = 0;

  // Mountains / Distant Hills
  ctx.fillStyle = "#1E303B";
  ctx.beginPath();
  ctx.moveTo(0, h * 0.6);
  ctx.lineTo(w * 0.25, h * 0.53);
  ctx.lineTo(w * 0.60, h * 0.57);
  ctx.lineTo(w, h * 0.51);
  ctx.lineTo(w, h * 0.6);
  ctx.closePath();
  ctx.fill();

  // Green Fairway / Turf
  const grassGrad = ctx.createLinearGradient(0, h * 0.55, 0, h);
  grassGrad.addColorStop(0, "#275021");
  grassGrad.addColorStop(0.4, "#366B2C");
  grassGrad.addColorStop(1, "#203E1A");
  ctx.fillStyle = grassGrad;
  ctx.fillRect(0, h * 0.55, w, h * 0.45);

  // Distant Trees
  const treeSpacing = w * 0.1;
  for (let x = treeSpacing / 2; x < w; x += treeSpacing) {
    ctx.fillStyle = "#193717";
    ctx.beginPath();
    ctx.arc(x, h * 0.56, Math.min(w, h) * 0.03, 0, Math.PI * 2);
    ctx.fill();
  }

  // 2. Putting Green and Flagstick at Landing position
  const landingKp = state.project.trajectories[0].keypoints[2];
  const landX = landingKp.x * w;
  const landY = landingKp.y * h;

  ctx.save();
  // Green fringe & putting surface
  ctx.fillStyle = "#2D6822";
  ctx.beginPath();
  ctx.ellipse(landX, landY, Math.min(w, h) * 0.12, Math.min(w, h) * 0.055, 0, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = "#3BA328";
  ctx.beginPath();
  ctx.ellipse(landX, landY, Math.min(w, h) * 0.09, Math.min(w, h) * 0.04, 0, 0, Math.PI * 2);
  ctx.fill();

  if (sport === "disc") {
    // Disc Golf Basket
    const poleH = Math.min(w, h) * 0.08;
    ctx.strokeStyle = "#CCCCCC";
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.moveTo(landX, landY);
    ctx.lineTo(landX, landY - poleH);
    ctx.stroke();

    // Basket tray
    ctx.fillStyle = "#FFCC00";
    ctx.fillRect(landX - 16, landY - poleH * 0.45, 32, 10);
    // Yellow top band
    ctx.fillRect(landX - 18, landY - poleH, 36, 8);
  } else {
    // Golf Flagstick
    const flagH = Math.min(w, h) * 0.09;
    ctx.strokeStyle = "#FFFFFF";
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.moveTo(landX, landY);
    ctx.lineTo(landX, landY - flagH);
    ctx.stroke();

    // Red flag banner fluttering
    ctx.fillStyle = "#FF2233";
    ctx.beginPath();
    ctx.moveTo(landX, landY - flagH);
    ctx.lineTo(landX + Math.min(w, h) * 0.045, landY - flagH + 8);
    ctx.lineTo(landX, landY - flagH + 16);
    ctx.closePath();
    ctx.fill();

    // Cup hole
    ctx.fillStyle = "#111111";
    ctx.beginPath();
    ctx.ellipse(landX, landY, 5, 2.5, 0, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();

  // 3. Athlete Figure (Professional Golfer / Disc Thrower) positioned beside Start position
  const startKp = state.project.trajectories[0].keypoints[0];
  const ballX = startKp.x * w;
  const ballY = startKp.y * h;
  drawAthleteFigure(ctx, ballX, ballY, w, h, frameIndex, sport);


  const charScale = (Math.min(w, h) / 1080) * 3.4;

  // 4. Projectile (Ball or Disc) flying in background
  if (frameIndex >= 30) {
    const apexKp = state.project.trajectories[0].keypoints[1];
    const landingKp = state.project.trajectories[0].keypoints[2];
    const fState = computeTimeMapping(frameIndex, startKp.frameIndex, apexKp.frameIndex, landingKp.frameIndex);

    const resolved = resolveKeypointHandles(state.project.trajectories[0].keypoints);
    let ballPos;
    if (state.project.trajectories[0].mode === "catmullRom") {
      ballPos = evaluateCatmullRomTrajectory(resolved, fState.u);
    } else {
      ballPos = evaluateBezierTrajectory(resolved, fState.u);
    }

    ctx.save();
    ctx.fillStyle = "#FFFFFF";
    ctx.shadowColor = "#FFFFFF";
    ctx.shadowBlur = 12;
    ctx.beginPath();
    if (sport === "disc") {
      ctx.ellipse(ballPos.x * w, ballPos.y * h, 14 * charScale, 5 * charScale, 0.2, 0, Math.PI * 2);
    } else {
      ctx.arc(ballPos.x * w, ballPos.y * h, 7 * charScale, 0, Math.PI * 2);
    }
    ctx.fill();
    ctx.restore();
  }
}

// ==========================================
// 5. VIEWPORT RESIZING & CANVAS SYNCHRONIZATION
// ==========================================

function resizeCanvas() {
  const rect = viewport.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return;

  overlayCanvas.width = rect.width;
  overlayCanvas.height = rect.height;

  magnifierCanvas.width = 120;
  magnifierCanvas.height = 120;

  // Single authoritative computeVideoRect
  state.videoContentRect = computeVideoRect(
    rect.width,
    rect.height,
    state.project.video.width,
    state.project.video.height,
    state.project.video.rotationDegrees || 0
  );

  // Requirement A.3: "Source 1080x1920 | Hiển thị 281x500"
  statRect.textContent = `Source ${state.project.video.width}x${state.project.video.height} | Hiển thị ${state.videoContentRect.w}x${state.videoContentRect.h}`;

  // Toggle compact toolbar if mobile phone frame is narrow
  const phoneFrame = document.getElementById("phone-frame");
  if (phoneFrame) {
    phoneFrame.classList.toggle("compact-toolbar", state.videoContentRect.w < 280);
  }

  // If user video element is active, position it to match videoContentRect exactly
  if (isUsingUserVideo) {
    videoEl.style.left = `${state.videoContentRect.x}px`;
    videoEl.style.top = `${state.videoContentRect.y}px`;
    videoEl.style.width = `${state.videoContentRect.w}px`;
    videoEl.style.height = `${state.videoContentRect.h}px`;
  }

  renderCurrentFrame();
  renderSvgHandles();
}

function renderCurrentFrame() {
  const rect = state.videoContentRect;

  // Clear full canvas with black (letterbox/pillarbox background)
  overlayCtx.fillStyle = "#000000";
  overlayCtx.fillRect(0, 0, overlayCanvas.width, overlayCanvas.height);

  // 1. Draw video background (Demo scene or user video)
  if (!isUsingUserVideo) {
    renderDemoFrame(state.currentFrame, state.project.trajectories[0].sport);
    overlayCtx.drawImage(
      demoCanvas,
      0, 0, demoCanvas.width, demoCanvas.height,
      rect.x, rect.y, rect.w, rect.h
    );
  }

  // 2. Call the SINGLE unified renderOverlay function for preview!
  renderOverlay(overlayCtx, state.project, state.currentFrame, rect);

  // Requirement 5 & 2: Debug label placed inside rect, bottom-left corner, small size scaled with videoRect
  if (state.debugRect) {
    overlayCtx.save();
    overlayCtx.strokeStyle = "#FF3B30";
    overlayCtx.lineWidth = 1.5;
    overlayCtx.strokeRect(rect.x, rect.y, rect.w, rect.h);

    let debugFontSize = Math.max(Math.round(rect.h * 0.020), 8);
    overlayCtx.font = `bold ${debugFontSize}px 'JetBrains Mono', monospace`;
    const debugText = `[DEBUG: ${rect.w}x${rect.h} @ (${rect.x},${rect.y})]`;
    let m = overlayCtx.measureText(debugText);
    const maxDebugW = rect.w * 0.65;
    if (m.width > maxDebugW) {
      debugFontSize = Math.max(Math.floor(debugFontSize * (maxDebugW / m.width)), 7);
      overlayCtx.font = `bold ${debugFontSize}px 'JetBrains Mono', monospace`;
      m = overlayCtx.measureText(debugText);
    }

    const pad = Math.max(Math.round(rect.h * 0.012), 4);
    const bgW = m.width + 6;
    const bgH = debugFontSize + 4;
    overlayCtx.fillStyle = "rgba(0, 0, 0, 0.75)";
    overlayCtx.fillRect(rect.x + pad - 2, rect.y + rect.h - pad - bgH + 2, bgW, bgH);

    overlayCtx.fillStyle = "#FF3B30";
    overlayCtx.textAlign = "left";
    overlayCtx.textBaseline = "bottom";
    overlayCtx.fillText(debugText, rect.x + pad + 1, rect.y + rect.h - pad);
    overlayCtx.restore();
  }

  renderSvgHandles();
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

  if (startKp) document.getElementById("coord-start").textContent = `F: ${startKp.frameIndex} (${(startKp.x * 100).toFixed(1)}%, ${(startKp.y * 100).toFixed(1)}%)`;
  if (apexKp) document.getElementById("coord-apex").textContent = `F: ${apexKp.frameIndex} (${(apexKp.x * 100).toFixed(1)}%, ${(apexKp.y * 100).toFixed(1)}%)`;
  if (landingKp) document.getElementById("coord-landing").textContent = `F: ${landingKp.frameIndex} (${(landingKp.x * 100).toFixed(1)}%, ${(landingKp.y * 100).toFixed(1)}%)`;
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
  // Requirement 3: Only show in edit/paused mode, hide completely when playing
  if (state.isPlaying) {
    handlesSvg.innerHTML = "";
    handlesSvg.style.pointerEvents = "none";
    return;
  }
  handlesSvg.style.pointerEvents = "all";

  const rect = state.videoContentRect;
  const kps = state.project.trajectories[0].keypoints;
  const resolved = resolveKeypointHandles(kps);

  // Scaled font size (~2.5% of videoRect.h)
  let labelFontSize = Math.max(Math.round(rect.h * 0.025), 9);
  const anchorRadius = Math.max(Math.round(rect.h * 0.016), 6);
  const outerRadius = anchorRadius + 5;
  const handleRadius = Math.max(Math.round(anchorRadius * 0.45), 4);

  // Determine positions of all keypoints
  const points = resolved.map(kp => {
    const cx = rect.x + kp.x * rect.w;
    const cy = rect.y + kp.y * rect.h;
    const text = kp.role.toUpperCase();
    return { kp, cx, cy, text, side: "above" };
  });

  // Anti-collision: if two keypoints are within 6% of video height (Math.abs(y1 - y2) < rect.h * 0.06),
  // shift one to opposite side (below)
  const collisionThreshold = rect.h * 0.06;
  for (let i = 0; i < points.length; i++) {
    for (let j = i + 1; j < points.length; j++) {
      if (Math.abs(points[i].cy - points[j].cy) < collisionThreshold) {
        // Shift the physically lower one (larger cy) to "below"
        if (points[i].cy >= points[j].cy) {
          points[i].side = "below";
        } else {
          points[j].side = "below";
        }
      }
    }
  }

  // Also if a point is too close to top edge, place label below; if too close to bottom edge, place above
  points.forEach(p => {
    if (p.cy - outerRadius - labelFontSize - 4 < rect.y) {
      p.side = "below";
    } else if (p.cy + outerRadius + labelFontSize + 4 > rect.y + rect.h) {
      p.side = "above";
    }
  });

  // Check measureText for labels to ensure they don't exceed rect width
  overlayCtx.save();
  overlayCtx.font = `bold ${labelFontSize}px 'JetBrains Mono', monospace`;
  points.forEach(p => {
    let textW = overlayCtx.measureText(p.text).width;
    if (textW > rect.w * 0.35) {
      labelFontSize = Math.max(Math.floor(labelFontSize * (rect.w * 0.35 / textW)), 8);
      overlayCtx.font = `bold ${labelFontSize}px 'JetBrains Mono', monospace`;
    }
  });
  overlayCtx.restore();

  let svgHtml = "";

  points.forEach(p => {
    const { kp, cx, cy, text, side } = p;
    const color = kp.role === "start" ? "#00FF66" : kp.role === "apex" ? "#00E5FF" : "#FF2D95";

    // Handle In & Out Lines
    if (kp.handleIn) {
      const hx = rect.x + kp.handleIn.x * rect.w;
      const hy = rect.y + kp.handleIn.y * rect.h;
      svgHtml += `<line x1="${cx}" y1="${cy}" x2="${hx}" y2="${hy}" stroke="rgba(255,255,255,0.45)" stroke-dasharray="3,3" stroke-width="1.5"/>`;
      svgHtml += `<circle cx="${hx}" cy="${hy}" r="${handleRadius}" fill="#FFFFFF" stroke="#00E5FF" stroke-width="1.5" class="drag-handle" data-role="${kp.role}" data-type="handleIn" style="cursor: pointer;"/>`;
    }
    if (kp.handleOut) {
      const hx = rect.x + kp.handleOut.x * rect.w;
      const hy = rect.y + kp.handleOut.y * rect.h;
      svgHtml += `<line x1="${cx}" y1="${cy}" x2="${hx}" y2="${hy}" stroke="rgba(255,255,255,0.45)" stroke-dasharray="3,3" stroke-width="1.5"/>`;
      svgHtml += `<circle cx="${hx}" cy="${hy}" r="${handleRadius}" fill="#FFFFFF" stroke="#00E5FF" stroke-width="1.5" class="drag-handle" data-role="${kp.role}" data-type="handleOut" style="cursor: pointer;"/>`;
    }

    // Label position calculation
    let labelY;
    if (side === "above") {
      labelY = cy - outerRadius - 4;
    } else {
      labelY = cy + outerRadius + labelFontSize + 2;
    }

    // Clamp labelX inside videoRect so text does not bleed outside
    const halfTextW = (text.length * labelFontSize * 0.65) / 2;
    const labelX = Math.min(Math.max(cx, rect.x + halfTextW + 4), rect.x + rect.w - halfTextW - 4);

    // Anchor Point & Label
    svgHtml += `
      <g class="drag-kp" data-role="${kp.role}" style="cursor: grab;">
        <circle cx="${cx}" cy="${cy}" r="${outerRadius}" fill="${color}" fill-opacity="0.3" stroke="${color}" stroke-width="2"/>
        <circle cx="${cx}" cy="${cy}" r="${Math.max(Math.round(anchorRadius * 0.45), 3)}" fill="#FFFFFF"/>
        <text x="${labelX}" y="${labelY}" fill="#FFFFFF" font-size="${labelFontSize}" font-weight="bold" font-family="'JetBrains Mono', monospace" text-anchor="middle" filter="drop-shadow(0px 1px 3px rgba(0,0,0,0.9))">${text}</text>
      </g>
    `;
  });

  handlesSvg.innerHTML = svgHtml;
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
  const rect = state.videoContentRect;
  if (normX < 0 || normX > 1 || normY < 0 || normY > 1) {
    magnifier.classList.add("hidden");
    return;
  }

  magnifier.classList.remove("hidden");
  magnifierLabel.textContent = `X: ${(normX * 100).toFixed(1)}% Y: ${(normY * 100).toFixed(1)}%`;

  magnifierCtx.clearRect(0, 0, 120, 120);
  const srcW = isUsingUserVideo ? videoEl.videoWidth : demoCanvas.width;
  const srcH = isUsingUserVideo ? videoEl.videoHeight : demoCanvas.height;
  const pxX = normX * srcW;
  const pxY = normY * srcH;
  const cropSize = 90;

  const source = isUsingUserVideo ? videoEl : demoCanvas;
  magnifierCtx.drawImage(
    source,
    pxX - cropSize / 2, pxY - cropSize / 2, cropSize, cropSize,
    0, 0, 120, 120
  );
}

// ==========================================
// 6. EVENT LISTENERS & INTERACTION (Strict Clamping)
// ==========================================

function seekToFrame(frame) {
  state.currentFrame = Math.min(Math.max(frame, 0), state.project.video.frameCount - 1);
  if (isUsingUserVideo && videoEl.duration) {
    videoEl.currentTime = state.currentFrame / 30;
  }
  renderCurrentFrame();
}

function stepFrame(delta) {
  seekToFrame(state.currentFrame + delta);
}

function togglePlay() {
  state.isPlaying = !state.isPlaying;
  renderSvgHandles();
  if (state.isPlaying) {
    playIcon.classList.add("hidden");
    pauseIcon.classList.remove("hidden");
    if (isUsingUserVideo) videoEl.play().catch(() => {});
    playLoop();
  } else {
    playIcon.classList.remove("hidden");
    pauseIcon.classList.add("hidden");
    if (isUsingUserVideo) videoEl.pause();
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

// Viewport Mouse Events with videoContentRect transformation
handlesSvg.addEventListener("mousemove", e => {
  const vpRect = viewport.getBoundingClientRect();
  const clickX = e.clientX - vpRect.left;
  const clickY = e.clientY - vpRect.top;
  const rect = state.videoContentRect;

  const rawNorm = screenToNorm(clickX, clickY, rect);
  state.mousePos = {
    x: clickX,
    y: clickY,
    normX: rawNorm.nx,
    normY: rawNorm.ny
  };

  const isInside = rawNorm.nx >= 0 && rawNorm.nx <= 1 && rawNorm.ny >= 0 && rawNorm.ny <= 1;
  state.isMouseInsideRect = isInside;

  if (isInside) {
    updateMagnifier(rawNorm.nx, rawNorm.ny);
  } else {
    magnifier.classList.add("hidden");
  }

  // Dragging keypoint or handle: CLAMP strictly inside [0..1]
  if (state.isDraggingKp) {
    const kps = state.project.trajectories[0].keypoints;
    const target = kps.find(k => k.role === state.isDraggingKp);
    if (target) {
      target.x = clamp01(rawNorm.nx);
      target.y = clamp01(rawNorm.ny);
      target.handleIn = null;
      target.handleOut = null;
      renderCurrentFrame();
      renderSvgHandles();
    }
  } else if (state.isDraggingHandle) {
    const kps = state.project.trajectories[0].keypoints;
    const target = kps.find(k => k.role === state.isDraggingHandle.role);
    if (target) {
      target[state.isDraggingHandle.type] = {
        x: clamp01(rawNorm.nx),
        y: clamp01(rawNorm.ny)
      };
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

// Click to set keypoint position: IGNORE if clicked outside videoContentRect
handlesSvg.addEventListener("click", e => {
  if (state.isDraggingKp || state.isDraggingHandle) return;

  const vpRect = viewport.getBoundingClientRect();
  const clickX = e.clientX - vpRect.left;
  const clickY = e.clientY - vpRect.top;
  const rect = state.videoContentRect;

  const { nx, ny } = screenToNorm(clickX, clickY, rect);

  // If outside video rect, reject click (do not place keypoint on black bars)
  if (nx < 0 || nx > 1 || ny < 0 || ny > 1) {
    return;
  }

  const role = state.activeRoleToSet;
  if (!role) return;

  const kps = state.project.trajectories[0].keypoints;
  const idx = kps.findIndex(k => k.role === role);
  const newKp = {
    role,
    frameIndex: state.currentFrame,
    x: clamp01(nx),
    y: clamp01(ny),
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

// Transport controls
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

// Taper slider
const inputTaper = document.getElementById("input-taper");
if (inputTaper) {
  inputTaper.addEventListener("input", e => {
    const val = parseInt(e.target.value, 10);
    document.getElementById("taper-val").textContent = `${val}%`;
    state.project.trajectories[0].style.taper = val / 100;
    renderCurrentFrame();
  });
}

// Debug Rect Checkbox
const checkDebug = document.getElementById("check-debug");
if (checkDebug) {
  state.debugRect = checkDebug.checked;
  checkDebug.addEventListener("change", e => {
    state.debugRect = e.target.checked;
    renderCurrentFrame();
  });
}

// Sidebar toggle button (Collapsible desktop sidebar)
const btnToggleSidebar = document.getElementById("btn-toggle-sidebar");
const sidebar = document.getElementById("sidebar");
const toggleIcon = document.getElementById("toggle-icon");
if (btnToggleSidebar && sidebar) {
  btnToggleSidebar.addEventListener("click", () => {
    sidebar.classList.toggle("collapsed");
    const isCollapsed = sidebar.classList.contains("collapsed");
    if (toggleIcon) toggleIcon.textContent = isCollapsed ? "▶" : "◀";
    setTimeout(resizeCanvas, 50);
    setTimeout(resizeCanvas, 280);
  });
}

// Mobile color dots
document.querySelectorAll(".mobile-dot").forEach(dot => {
  dot.addEventListener("click", () => {
    document.querySelectorAll(".mobile-dot").forEach(d => d.classList.remove("active"));
    dot.classList.add("active");
    const pal = dot.dataset.palette;
    state.project.trajectories[0].style.palette = pal;
    state.project.trajectories[0].style.gradient = PALETTES[pal];
    document.querySelectorAll(".palette-swatch").forEach(s => {
      s.classList.toggle("active", s.dataset.palette === pal);
    });
    renderCurrentFrame();
  });
});

// Mobile tab buttons
document.querySelectorAll(".mobile-tab-btn").forEach(tab => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".mobile-tab-btn").forEach(t => t.classList.remove("active"));
    tab.classList.add("active");
    if (sidebar && sidebar.classList.contains("collapsed")) {
      sidebar.classList.remove("collapsed");
      if (toggleIcon) toggleIcon.textContent = "◀";
      setTimeout(resizeCanvas, 280);
    }
  });
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

// Presets Menu Dropdown
const btnPresetsMenu = document.getElementById("btn-presets-menu");
const presetsMenuDropdown = document.getElementById("presets-menu-dropdown");
const currentPresetLabel = document.getElementById("current-preset-label");

btnPresetsMenu.addEventListener("click", e => {
  e.stopPropagation();
  presetsMenuDropdown.classList.toggle("hidden");
});

window.addEventListener("click", () => {
  presetsMenuDropdown.classList.add("hidden");
});

document.querySelectorAll(".dropdown-item").forEach(item => {
  item.addEventListener("click", () => {
    document.querySelectorAll(".dropdown-item").forEach(i => i.classList.remove("active"));
    item.classList.add("active");
    currentPresetLabel.textContent = item.textContent.replace(/[🎬⛳🥏]\s*/g, '');
    presetsMenuDropdown.classList.add("hidden");
    initDemoScene(item.dataset.preset);
  });
});

// Custom video file upload
document.getElementById("video-file-input").addEventListener("change", e => {
  const file = e.target.files[0];
  if (!file) return;

  const url = URL.createObjectURL(file);
  isUsingUserVideo = true;
  videoEl.srcObject = null;
  videoEl.src = url;
  videoEl.classList.remove("hidden");

  videoEl.onloadedmetadata = () => {
    const w = videoEl.videoWidth || 1080;
    const h = videoEl.videoHeight || 1920;
    state.project.video.width = w;
    state.project.video.height = h;
    state.project.video.durationUs = Math.round(videoEl.duration * 1000000);
    state.project.video.frameCount = Math.round(videoEl.duration * 30);

    const pts = [];
    for (let f = 0; f < state.project.video.frameCount; f++) {
      pts.push(Math.round((f / 30) * 1000000));
    }
    state.project.video.ptsUs = pts;

    currentPresetLabel.textContent = `Custom (${w}x${h})`;
    scrubber.max = Math.max(state.project.video.frameCount - 1, 1);
    resizeCanvas();
    seekToFrame(0);
  };
});

// ==========================================
// 7. STANDARDIZED JSON SCHEMA SAVE & LOAD (Schema v1)
// ==========================================

function getNormalizedProjectJSON() {
  const resolvedKps = resolveKeypointHandles(state.project.trajectories[0].keypoints);
  
  // Clone project and embed resolved handles with normalized x,y
  const exportProj = {
    schemaVersion: 1,
    id: state.project.id || ("proj-" + Date.now()),
    video: {
      width: state.project.video.width,
      height: state.project.video.height,
      rotationDegrees: state.project.video.rotationDegrees || 0,
      durationUs: state.project.video.durationUs,
      frameCount: state.project.video.frameCount,
      ptsUs: state.project.video.ptsUs
    },
    trajectories: [
      {
        id: state.project.trajectories[0].id || "traj-1",
        sport: state.project.trajectories[0].sport || "golf",
        mode: state.project.trajectories[0].mode || "bezier",
        keypoints: resolvedKps.map(kp => ({
          role: kp.role,
          frameIndex: kp.frameIndex,
          x: parseFloat(kp.x.toFixed(4)),
          y: parseFloat(kp.y.toFixed(4)),
          ...(kp.handleIn ? { handleIn: { x: parseFloat(kp.handleIn.x.toFixed(4)), y: parseFloat(kp.handleIn.y.toFixed(4)) } } : {}),
          ...(kp.handleOut ? { handleOut: { x: parseFloat(kp.handleOut.x.toFixed(4)), y: parseFloat(kp.handleOut.y.toFixed(4)) } } : {})
        })),
        style: {
          palette: state.project.trajectories[0].style.palette || "neonCyan",
          gradient: state.project.trajectories[0].style.gradient,
          lineWidth: state.project.trajectories[0].style.lineWidth,
          taper: state.project.trajectories[0].style.taper ?? 0.40,
          glow: state.project.trajectories[0].style.glow,
          trailMode: state.project.trajectories[0].style.trailMode,
          showImpactFlash: state.project.trajectories[0].style.showImpactFlash
        },
        distance: {
          value: state.project.trajectories[0].distance.value,
          unit: state.project.trajectories[0].distance.unit,
          visible: state.project.trajectories[0].distance.visible,
          easing: state.project.trajectories[0].distance.easing
        }
      }
    ],
    cameraTrack: state.project.cameraTrack || null,
    export: {
      resolution: state.project.export.resolution || "source",
      fps: state.project.export.fps || "source",
      watermark: state.project.export.watermark ?? true
    }
  };

  return exportProj;
}

document.getElementById("btn-save-project").addEventListener("click", () => {
  const exportProj = getNormalizedProjectJSON();
  const jsonStr = JSON.stringify(exportProj, null, 2);
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
      const parsed = JSON.parse(ev.target.result);
      
      // Auto-migrate legacy files
      const traj = parsed.trajectories?.[0] || {};
      const keypoints = (traj.keypoints || []).map(kp => ({
        role: kp.role,
        frameIndex: kp.frameIndex,
        x: clamp01(kp.x),
        y: clamp01(kp.y),
        handleIn: kp.handleIn ? { x: clamp01(kp.handleIn.x), y: clamp01(kp.handleIn.y) } : null,
        handleOut: kp.handleOut ? { x: clamp01(kp.handleOut.x), y: clamp01(kp.handleOut.y) } : null
      }));

      state.project = {
        schemaVersion: 1,
        id: parsed.id || ("proj-" + Date.now()),
        createdAt: parsed.createdAt || new Date().toISOString(),
        video: {
          uri: parsed.video?.uri || "imported-video",
          width: parsed.video?.width || 1080,
          height: parsed.video?.height || 1920,
          rotationDegrees: parsed.video?.rotationDegrees || 0,
          durationUs: parsed.video?.durationUs || 4000000,
          frameCount: parsed.video?.frameCount || 120,
          ptsUs: parsed.video?.ptsUs || []
        },
        trajectories: [
          {
            id: traj.id || "traj-1",
            sport: traj.sport || "golf",
            mode: traj.mode || "bezier",
            keypoints: keypoints,
            style: {
              palette: traj.style?.palette || "neonCyan",
              gradient: traj.style?.gradient || ["#00E5FF", "#FF2D95", "#FFB300"],
              lineWidth: traj.style?.lineWidth || 12,
              taper: traj.style?.taper ?? 0.40,
              glow: traj.style?.glow ?? 0.75,
              trailMode: traj.style?.trailMode || "tracer",
              cometLengthFraction: traj.style?.cometLengthFraction || 0.25,
              showImpactFlash: traj.style?.showImpactFlash ?? true
            },
            distance: {
              value: traj.distance?.value || 250,
              unit: traj.distance?.unit || "yd",
              visible: traj.distance?.visible ?? true,
              easing: traj.distance?.easing || "easeOut"
            }
          }
        ],
        cameraTrack: parsed.cameraTrack || null,
        export: parsed.export || { resolution: "source", fps: "source", watermark: true }
      };

      demoCanvas.width = state.project.video.width;
      demoCanvas.height = state.project.video.height;
      scrubber.max = Math.max(state.project.video.frameCount - 1, 1);

      // Sync UI sliders
      const loadedTraj = state.project.trajectories[0];
      if (inputWidth) {
        inputWidth.value = loadedTraj.style.lineWidth;
        document.getElementById("width-val").textContent = `${loadedTraj.style.lineWidth} px`;
      }
      if (inputTaper) {
        inputTaper.value = Math.round(loadedTraj.style.taper * 100);
        document.getElementById("taper-val").textContent = `${Math.round(loadedTraj.style.taper * 100)}%`;
      }
      if (inputGlow) {
        inputGlow.value = Math.round(loadedTraj.style.glow * 100);
        document.getElementById("glow-val").textContent = `${Math.round(loadedTraj.style.glow * 100)}%`;
      }

      updateScrubberMarks();
      resizeCanvas();
      seekToFrame(0);
      alert("Đã mở và chuẩn hóa Project JSON thành công!");
    } catch (err) {
      alert("Lỗi khi mở file JSON: " + err.message);
    }
  };
  reader.readAsText(file);
});

// ==========================================
// 8. GOLDEN TEST VECTORS EXPORTER (Item 5)
// ==========================================

function generateGoldenVectorData(mode = "bezier") {
  const proj = getNormalizedProjectJSON();
  proj.trajectories[0].mode = mode;

  const keypoints = proj.trajectories[0].keypoints;
  const startKp = keypoints.find(k => k.role === "start") || keypoints[0];
  const apexKp = keypoints.find(k => k.role === "apex") || keypoints[1] || keypoints[0];
  const landingKp = keypoints.find(k => k.role === "landing") || keypoints[keypoints.length - 1];

  const resolvedKps = resolveKeypointHandles(keypoints);
  const frameCount = proj.video.frameCount;

  // Collect sample frame indices: every 10 frames + exact keypoint frames
  const frameSet = new Set();
  for (let f = 0; f < frameCount; f += 10) {
    frameSet.add(f);
  }
  frameSet.add(startKp.frameIndex);
  frameSet.add(apexKp.frameIndex);
  frameSet.add(landingKp.frameIndex);
  frameSet.add(frameCount - 1);

  const sortedFrames = Array.from(frameSet).sort((a, b) => a - b);

  const samples = sortedFrames.map(frame => {
    const flightState = computeTimeMapping(frame, startKp.frameIndex, apexKp.frameIndex, landingKp.frameIndex);
    let pt;
    if (mode === "catmullRom") {
      pt = evaluateCatmullRomTrajectory(resolvedKps, flightState.u);
    } else {
      pt = evaluateBezierTrajectory(resolvedKps, flightState.u);
    }

    const distShown = Math.round(
      computeEasedDistance(proj.trajectories[0].distance.value, flightState.progress, proj.trajectories[0].distance.easing)
    );

    return {
      frameIndex: frame,
      u: parseFloat(flightState.u.toFixed(6)),
      x: parseFloat(pt.x.toFixed(6)),
      y: parseFloat(pt.y.toFixed(6)),
      distanceShown: distShown
    };
  });

  return {
    input: proj,
    samples: samples
  };
}

document.getElementById("btn-export-golden").addEventListener("click", () => {
  // 1. Export Cubic Bezier Golden Vectors
  const bezierGolden = generateGoldenVectorData("bezier");
  const blobBezier = new Blob([JSON.stringify(bezierGolden, null, 2)], { type: "application/json" });
  const a1 = document.createElement("a");
  a1.href = URL.createObjectURL(blobBezier);
  a1.download = "golden_vectors_bezier.json";
  a1.click();

  // 2. Export Centripetal Catmull-Rom Golden Vectors
  setTimeout(() => {
    const catmullGolden = generateGoldenVectorData("catmullRom");
    const blobCatmull = new Blob([JSON.stringify(catmullGolden, null, 2)], { type: "application/json" });
    const a2 = document.createElement("a");
    a2.href = URL.createObjectURL(blobCatmull);
    a2.download = "golden_vectors_catmull_rom.json";
    a2.click();
  }, 300);
});

// ==========================================
// 9. VIDEO EXPORTER PIPELINE (Item 3: Uses identical renderOverlay with full video rect)
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

  const targetW = state.project.video.width;
  const targetH = state.project.video.height;

  const exportCanvas = document.createElement("canvas");
  exportCanvas.width = targetW;
  exportCanvas.height = targetH;
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
  // Full Video Rect for export: x=0, y=0, w=video.width, h=video.height
  const exportRect = { x: 0, y: 0, w: targetW, h: targetH };

  for (let f = 0; f < totalFrames; f++) {
    // 1. Draw video background
    if (!isUsingUserVideo) {
      renderDemoFrame(f, state.project.trajectories[0].sport);
      exportCtx.drawImage(demoCanvas, 0, 0, targetW, targetH);
    } else {
      exportCtx.drawImage(videoEl, 0, 0, targetW, targetH);
    }

    // 2. Call the EXACT same renderOverlay function with full video rect!
    renderOverlay(exportCtx, state.project, f, exportRect);

    const pct = Math.round(((f + 1) / totalFrames) * 100);
    exportProgressFill.style.width = `${pct}%`;
    exportStatusText.textContent = `Đang render khung hình ${f + 1} / ${totalFrames} (${pct}%)...`;

    await new Promise(r => setTimeout(r, 16));
  }

  recorder.stop();
}

// ==========================================
// 10. INITIALIZATION
// ==========================================

window.addEventListener("resize", resizeCanvas);

// Automatic ResizeObserver on viewport container
if (window.ResizeObserver && viewport) {
  const ro = new ResizeObserver(() => {
    resizeCanvas();
  });
  ro.observe(viewport);
}

// Initialize default scene: Golf Dọc 9:16
initDemoScene("golf-vertical");
window.dispatchEvent(new Event("resize"));
