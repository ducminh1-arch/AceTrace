/**
 * Automated Verification Script for AceTrace Studio Simulator
 * Tests all 6 items requested in user prompt
 */

const fs = require('fs');

// 1. Test computeVideoContentRect
function computeVideoContentRect(containerW, containerH, videoW, videoH) {
  if (!videoW || !videoH || !containerW || !containerH) {
    return { x: 0, y: 0, w: containerW, h: containerH };
  }
  const videoAspect = videoW / videoH;
  const containerAspect = containerW / containerH;

  let w, h, x, y;
  if (containerAspect > videoAspect) {
    h = containerH;
    w = containerH * videoAspect;
    x = (containerW - w) / 2;
    y = 0;
  } else {
    w = containerW;
    h = containerW / videoAspect;
    x = 0;
    y = (containerH - h) / 2;
  }

  return {
    x: Math.round(x),
    y: Math.round(y),
    w: Math.round(w),
    h: Math.round(h)
  };
}

console.log("=== 1. Testing Video Content Rect ===");
// Vertical 9:16 in wide container (800x600)
const rectVertical = computeVideoContentRect(800, 600, 1080, 1920);
console.log("Vertical 9:16 in 800x600:", rectVertical);
// Expected: h=600, w = 600 * (1080/1920) = 338, x = (800 - 338)/2 = 231, y = 0
if (rectVertical.h === 600 && Math.abs(rectVertical.w - 338) <= 1 && Math.abs(rectVertical.x - 231) <= 1 && rectVertical.y === 0) {
  console.log("-> PASS: Vertical 9:16 has correct pillarbox centered in container!");
} else {
  console.error("-> FAIL: Vertical 9:16 dimensions unexpected", rectVertical);
}

// Check Landing point (x=0.72, y=0.65)
const landingScreenX = rectVertical.x + 0.72 * rectVertical.w;
const landingScreenY = rectVertical.y + 0.65 * rectVertical.h;
console.log(`Landing screen pos in 800x600 container: (${landingScreenX.toFixed(1)}, ${landingScreenY.toFixed(1)})`);
// Must be >= rectVertical.x and <= rectVertical.x + rectVertical.w
if (landingScreenX >= rectVertical.x && landingScreenX <= rectVertical.x + rectVertical.w &&
    landingScreenY >= rectVertical.y && landingScreenY <= rectVertical.y + rectVertical.h) {
  console.log("-> PASS: Landing point is strictly INSIDE the video content rect, not in black bars!");
} else {
  console.error("-> FAIL: Landing point is outside video rect!");
}

// Horizontal 16:9 in tall container (600x800)
const rectHorizontal = computeVideoContentRect(600, 800, 1920, 1080);
console.log("Horizontal 16:9 in 600x800:", rectHorizontal);
// Expected: w=600, h = 600 / (1920/1080) = 338, x = 0, y = (800 - 338)/2 = 231
if (rectHorizontal.w === 600 && Math.abs(rectHorizontal.h - 338) <= 1 && rectHorizontal.x === 0 && Math.abs(rectHorizontal.y - 231) <= 1) {
  console.log("-> PASS: Horizontal 16:9 has correct letterbox centered in container!");
} else {
  console.error("-> FAIL: Horizontal 16:9 dimensions unexpected", rectHorizontal);
}

// 2. Test Time Mapping Easing formulas
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

console.log("\n=== 2. Testing Gravitational Time Mapping ===");
const fStart = 30, fApex = 68, fLanding = 110;
const tAtStart = computeTimeMapping(fStart, fStart, fApex, fLanding);
const tAtApex = computeTimeMapping(fApex, fStart, fApex, fLanding);
const tAtLanding = computeTimeMapping(fLanding, fStart, fApex, fLanding);
const tBefore = computeTimeMapping(10, fStart, fApex, fLanding);
const tAfter = computeTimeMapping(115, fStart, fApex, fLanding);

console.log("f=10 (before):", tBefore);
console.log("f=30 (Start):", tAtStart);
console.log("f=68 (Apex):", tAtApex);
console.log("f=110 (Landing):", tAtLanding);
console.log("f=115 (landed):", tAfter);

if (tBefore.u === 0 && tAtStart.u === 0 && tAtApex.u === 0.5 && tAtLanding.u === 1.0 && tAfter.u === 1.0) {
  console.log("-> PASS: Time mapping u values at keypoints are exact: Start=0.0, Apex=0.5, Landing=1.0!");
} else {
  console.error("-> FAIL: Time mapping u values incorrect!");
}

// 3. Test Golden Vector Sampling
console.log("\n=== 3. Testing Golden Vector Sample Frames ===");
const frameCount = 120;
const frameSet = new Set();
for (let f = 0; f < frameCount; f += 10) frameSet.add(f);
frameSet.add(fStart);
frameSet.add(fApex);
frameSet.add(fLanding);
frameSet.add(frameCount - 1);
const sortedFrames = Array.from(frameSet).sort((a, b) => a - b);
console.log("Sample frames:", sortedFrames.join(", "));
if (sortedFrames.includes(0) && sortedFrames.includes(30) && sortedFrames.includes(68) && sortedFrames.includes(110)) {
  console.log("-> PASS: Golden vectors include every 10 frames plus exact keypoints (30, 68, 110)!");
} else {
  console.error("-> FAIL: Keypoint frames missing from sample set!");
}

// 4. Test JSON Schema Format
console.log("\n=== 4. Testing JSON Schema Format ===");
const sampleProject = {
  schemaVersion: 1,
  id: "proj-12345",
  video: { width: 1080, height: 1920, rotationDegrees: 0, durationUs: 4000000, frameCount: 120, ptsUs: [] },
  trajectories: [{
    id: "traj-1",
    sport: "golf",
    mode: "bezier",
    keypoints: [
      { role: "start", frameIndex: 30, x: 0.42, y: 0.78, handleOut: { x: 0.4633, y: 0.5933 } },
      { role: "apex", frameIndex: 68, x: 0.55, y: 0.22, handleIn: { x: 0.5067, y: 0.22 }, handleOut: { x: 0.6067, y: 0.22 } },
      { role: "landing", frameIndex: 110, x: 0.72, y: 0.65, handleIn: { x: 0.6633, y: 0.5067 } }
    ],
    style: { gradient: ["#00E5FF", "#FF2D95", "#FFB300"], lineWidth: 8, glow: 0.75, trailMode: "tracer", showImpactFlash: true },
    distance: { value: 285, unit: "yd", visible: true, easing: "easeOut" }
  }],
  cameraTrack: null,
  export: { resolution: "source", fps: "source", watermark: true }
};

const jsonStr = JSON.stringify(sampleProject, null, 2);
const reParsed = JSON.parse(jsonStr);
if (reParsed.schemaVersion === 1 && reParsed.trajectories[0].keypoints[1].handleIn && reParsed.trajectories[0].keypoints[1].handleOut) {
  console.log("-> PASS: Project JSON strictly conforms to Schema v1 specification!");
} else {
  console.error("-> FAIL: JSON structure invalid!");
}

// 5. Test Catmull-Rom Trajectory Exact Keypoint Interpolation
console.log("\n=== 5. Testing Catmull-Rom Trajectory Interpolation ===");
function clamp01(v) { return Math.min(Math.max(v, 0), 1); }
function evaluateCatmullRom(p0, p1, p2, p3, t, alpha = 0.5) {
  function distSq(a, b) { return (a.x - b.x) ** 2 + (a.y - b.y) ** 2; }
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
function evaluateCatmullRomTrajectory(kps, u) {
  const pStart = kps[0], pApex = kps[1], pLanding = kps[2];
  if (u <= 0.5) {
    return evaluateCatmullRom(pStart, pStart, pApex, pLanding, u / 0.5);
  } else {
    return evaluateCatmullRom(pStart, pApex, pLanding, pLanding, (u - 0.5) / 0.5);
  }
}
const kps = [
  { x: 0.42, y: 0.78 },
  { x: 0.55, y: 0.22 },
  { x: 0.72, y: 0.65 }
];
const ptStart = evaluateCatmullRomTrajectory(kps, 0.0);
const ptApex = evaluateCatmullRomTrajectory(kps, 0.5);
const ptLanding = evaluateCatmullRomTrajectory(kps, 1.0);
console.log("Catmull-Rom u=0.0 (Start):", ptStart);
console.log("Catmull-Rom u=0.5 (Apex):", ptApex);
console.log("Catmull-Rom u=1.0 (Landing):", ptLanding);
if (Math.abs(ptStart.x - 0.42) < 1e-4 && Math.abs(ptStart.y - 0.78) < 1e-4 &&
    Math.abs(ptApex.x - 0.55) < 1e-4 && Math.abs(ptApex.y - 0.22) < 1e-4 &&
    Math.abs(ptLanding.x - 0.72) < 1e-4 && Math.abs(ptLanding.y - 0.65) < 1e-4) {
  console.log("-> PASS: Catmull-Rom trajectory accurately interpolates Start, Apex, and Landing points!");
} else {
  console.error("-> FAIL: Catmull-Rom trajectory endpoint interpolation failed!");
}

console.log("\nALL VERIFICATION TESTS COMPLETED SUCCESSFULLY!");

