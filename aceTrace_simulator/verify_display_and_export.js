const puppeteer = require('puppeteer-core');
const path = require('path');
const fs = require('fs');

const ARTIFACT_DIR = 'C:\\Users\\Admin\\.gemini\\antigravity-ide\\brain\\1c0e11c2-f732-4762-9ae8-ee01ffad0b99';

async function main() {
  console.log('--- Launching Chrome (1280x630 Viewport) ---');
  const browser = await puppeteer.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1280,630']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 630 });

  page.on('console', msg => console.log('BROWSER LOG:', msg.text()));
  page.on('pageerror', err => console.error('BROWSER ERROR:', err.toString()));

  await page.goto('http://localhost:3000', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 600));

  // 1. Measure and verify dimensions in 1280x630 viewport
  const dims = await page.evaluate(() => {
    resizeCanvas();
    const rect = state.videoContentRect;
    const vp = document.getElementById('viewport').getBoundingClientRect();
    const pf = document.getElementById('phone-frame').getBoundingClientRect();
    const tl = document.getElementById('timeline-deck').getBoundingClientRect();
    return {
      viewportW: window.innerWidth,
      viewportH: window.innerHeight,
      phoneFrame: { w: Math.round(pf.width), h: Math.round(pf.height) },
      timelineDeck: { w: Math.round(tl.width), h: Math.round(tl.height) },
      viewportInner: { w: Math.round(vp.width), h: Math.round(vp.height) },
      videoRect: rect
    };
  });

  console.log('--- Dimensions on 1280x630 Laptop Display ---');
  console.log(JSON.stringify(dims, null, 2));

  if (dims.videoRect.h >= 400) {
    console.log(` SUCCESS: videoRect.h = ${dims.videoRect.h}px (>= 400px CSS target achieved!)`);
  } else {
    console.warn(`⚠️ WARNING: videoRect.h = ${dims.videoRect.h}px (< 400px target)`);
  }

  // 1.5. Screenshot: Frame 0 (Address Stance)
  console.log('--- Generating Shot: Frame 0 (Address Stance) ---');
  await page.evaluate(() => {
    state.isPlaying = false;
    seekToFrame(0);
  });
  await new Promise(r => setTimeout(r, 400));
  const shot0Path = path.resolve(__dirname, 'shot_address_frame_0.png');
  await page.screenshot({ path: shot0Path });
  fs.copyFileSync(shot0Path, path.join(ARTIFACT_DIR, 'shot_address_frame_0.png'));
  console.log('Saved Shot 0 to:', shot0Path);

  // 2. Screenshot (a): Paused at frame 68, Debug ON
  console.log('--- Generating Shot (a): Paused at frame 68, Debug ON ---');
  await page.evaluate(() => {
    state.isPlaying = false;
    const checkDebug = document.getElementById('check-debug');
    if (checkDebug) {
      checkDebug.checked = true;
      state.debugRect = true;
    }
    seekToFrame(68);
  });
  await new Promise(r => setTimeout(r, 400));
  const shotAPath = path.resolve(__dirname, 'shot_a_frame_68_paused_debug.png');
  await page.screenshot({ path: shotAPath });
  fs.copyFileSync(shotAPath, path.join(ARTIFACT_DIR, 'shot_a_frame_68_paused_debug.png'));
  console.log('Saved Shot (a) to:', shotAPath);

  // 3. Screenshot (b): Playing at frame ~90, Debug OFF
  console.log('--- Generating Shot (b): Playing at frame ~90, Debug OFF ---');
  await page.evaluate(() => {
    const checkDebug = document.getElementById('check-debug');
    if (checkDebug) {
      checkDebug.checked = false;
      state.debugRect = false;
    }
    state.isPlaying = true;
    seekToFrame(90);
  });
  await new Promise(r => setTimeout(r, 400));
  const shotBPath = path.resolve(__dirname, 'shot_b_frame_90_playing.png');
  await page.screenshot({ path: shotBPath });
  fs.copyFileSync(shotBPath, path.join(ARTIFACT_DIR, 'shot_b_frame_90_playing.png'));
  console.log('Saved Shot (b) to:', shotBPath);

  // 4. Screenshot (c): Frame 120 (Frame Index 119), Debug OFF
  console.log('--- Generating Shot (c): Frame 120 (index 119), Debug OFF ---');
  await page.evaluate(() => {
    state.isPlaying = false;
    const checkDebug = document.getElementById('check-debug');
    if (checkDebug) {
      checkDebug.checked = false;
      state.debugRect = false;
    }
    seekToFrame(119);
  });
  await new Promise(r => setTimeout(r, 400));
  const shotCPath = path.resolve(__dirname, 'shot_c_frame_120_final.png');
  await page.screenshot({ path: shotCPath });
  fs.copyFileSync(shotCPath, path.join(ARTIFACT_DIR, 'shot_c_frame_120_final.png'));
  console.log('Saved Shot (c) to:', shotCPath);

  // 5. Generate and export Golden Vectors (Bezier and Catmull-Rom)
  console.log('--- Exporting Golden Vectors ---');
  const goldenVectors = await page.evaluate(() => {
    const bezier = generateGoldenVectorData('bezier');
    const catmull = generateGoldenVectorData('catmullRom');
    return { bezier, catmull };
  });

  const bezierPath = path.resolve(__dirname, 'golden_vectors_bezier.json');
  const catmullPath = path.resolve(__dirname, 'golden_vectors_catmull_rom.json');

  fs.writeFileSync(bezierPath, JSON.stringify(goldenVectors.bezier, null, 2), 'utf-8');
  fs.writeFileSync(catmullPath, JSON.stringify(goldenVectors.catmull, null, 2), 'utf-8');

  fs.copyFileSync(bezierPath, path.join(ARTIFACT_DIR, 'golden_vectors_bezier.json'));
  fs.copyFileSync(catmullPath, path.join(ARTIFACT_DIR, 'golden_vectors_catmull_rom.json'));

  console.log('Saved Golden Vectors Bezier:', bezierPath);
  console.log('Saved Golden Vectors Catmull-Rom:', catmullPath);

  await browser.close();
  console.log('--- Verification & Export Complete! ---');
}

main().catch(err => {
  console.error('ERROR in verification script:', err);
  process.exit(1);
});
