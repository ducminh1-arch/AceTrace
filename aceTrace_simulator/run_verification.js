const puppeteer = require('puppeteer-core');
const path = require('path');
const fs = require('fs');

async function runVerification() {
  console.log('--- Launching Chrome for AceTrace Studio Verification ---');
  const browser = await puppeteer.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1366,850']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1366, height: 850 });

  page.on('console', msg => console.log('BROWSER LOG:', msg.text()));
  page.on('pageerror', err => console.error('BROWSER ERROR:', err.toString()));

  await page.goto('http://localhost:3000', { waitUntil: 'networkidle0' });
  await page.waitForTimeout ? page.waitForTimeout(500) : new Promise(r => setTimeout(r, 600));

  // Verify Part A.1: computeVideoRect test inside page
  const rectTestResult = await page.evaluate(() => {
    const vrVertical = computeVideoRect(800, 600, 1080, 1920);
    const vrHorizontal = computeVideoRect(600, 800, 1920, 1080);
    return {
      vrVertical,
      vrHorizontal,
      currentRect: state.videoContentRect,
      sourceW: state.project.video.width,
      sourceH: state.project.video.height,
      debugRect: state.debugRect
    };
  });
  console.log('Video Rect Test Results:', JSON.stringify(rectTestResult, null, 2));

  // Ensure debug checkbox is checked
  await page.evaluate(() => {
    const cb = document.getElementById('check-debug');
    if (cb && !cb.checked) {
      cb.checked = true;
      cb.dispatchEvent(new Event('change'));
    }
  });

  // Capture 1: Vertical 9:16 - Frame 0
  await page.evaluate(() => {
    seekToFrame(0);
  });
  await new Promise(r => setTimeout(r, 300));
  const shot0 = path.resolve(__dirname, 'shot_vertical_frame_0.png');
  await page.screenshot({ path: shot0 });
  console.log('Captured:', shot0);

  // Capture 2: Vertical 9:16 - Frame 68 (Mid flight Apex, playing state)
  await page.evaluate(() => {
    seekToFrame(68);
    // Simulate playing or active mode
    state.isPlaying = true;
    renderSvgHandles();
    renderCurrentFrame();
  });
  await new Promise(r => setTimeout(r, 300));
  const shot68 = path.resolve(__dirname, 'shot_vertical_frame_68.png');
  await page.screenshot({ path: shot68 });
  console.log('Captured:', shot68);

  // Capture 3: Vertical 9:16 - Final Frame (Landing frame 110)
  await page.evaluate(() => {
    state.isPlaying = false;
    seekToFrame(110);
    renderSvgHandles();
    renderCurrentFrame();
  });
  await new Promise(r => setTimeout(r, 300));
  const shotFinal = path.resolve(__dirname, 'shot_vertical_frame_final.png');
  await page.screenshot({ path: shotFinal });
  console.log('Captured:', shotFinal);

  // Capture 4: Horizontal 16:9 - Frame 68
  await page.evaluate(async () => {
    initDemoScene('golf-horizontal');
    await new Promise(r => setTimeout(r, 100));
    resizeCanvas();
    seekToFrame(68);
  });
  await new Promise(r => setTimeout(r, 500));
  const shotHorizontal = path.resolve(__dirname, 'shot_horizontal_16_9.png');
  await page.screenshot({ path: shotHorizontal });
  console.log('Captured:', shotHorizontal);

  // Test JSON Save & Load equivalence
  const jsonEquivalence = await page.evaluate(() => {
    initDemoScene('golf-vertical');
    seekToFrame(50);
    const json1 = getNormalizedProjectJSON();
    const str1 = JSON.stringify(json1);
    
    // Simulate loading str1
    const parsed = JSON.parse(str1);
    const traj = parsed.trajectories[0];
    const keypoints = traj.keypoints;
    state.project.trajectories[0].keypoints = keypoints;
    state.project.trajectories[0].style = traj.style;
    state.project.trajectories[0].distance = traj.distance;
    
    const json2 = getNormalizedProjectJSON();
    const str2 = JSON.stringify(json2);
    return {
      match: str1 === str2,
      taper: json2.trajectories[0].style.taper,
      lineWidth: json2.trajectories[0].style.lineWidth,
      keypointsCount: json2.trajectories[0].keypoints.length
    };
  });
  console.log('JSON Save/Restore Test:', jsonEquivalence);

  await browser.close();
  console.log('--- Verification Complete ---');
}

runVerification().catch(console.error);
