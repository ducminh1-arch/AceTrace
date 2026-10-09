const puppeteer = require('puppeteer-core');
const path = require('path');

async function testCapture() {
  const browser = await puppeteer.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto('http://localhost:3000', { waitUntil: 'networkidle0' });
  const outPath = path.resolve(__dirname, 'test_output.png');
  await page.screenshot({ path: outPath });
  console.log('Screenshot saved to:', outPath);
  await browser.close();
}

testCapture().catch(console.error);
