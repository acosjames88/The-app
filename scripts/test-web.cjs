const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
let playwright;
try { playwright = require('playwright'); }
catch (_) { playwright = require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES + '/playwright'); }

(async () => {
  const browser = await playwright.chromium.launch({
    headless: true,
    ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {}),
    args: process.env.SINGLE_PROCESS === '1' ? ['--single-process', '--no-zygote', '--in-process-gpu'] : []
  });
  let passed = 0;
  function check(value, name) { assert.ok(value, name); console.log('PASS ' + name); passed++; }
  try {
    const page = await browser.newPage({viewport: {width:1280,height:720}});
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => {
      window.calls = [];
      window.SnesApp = {play: slug => calls.push(['play',slug]),
        searchAll: query => calls.push(['search',query]), help: () => calls.push(['help'])};
    });
    await page.goto('file://' + path.resolve('app/src/main/assets/index.html'));
    check(await page.locator('.game').count() === 57, '57 local catalog entries');
    check(await page.evaluate(() => document.activeElement.dataset.slug) === 'super-mario-world', 'initial TV focus');
    await page.keyboard.press('ArrowRight');
    check(await page.evaluate(() => document.activeElement.dataset.slug) === 'nba-jam', 'D-pad moves right');
    await page.keyboard.press('ArrowDown');
    check(await page.evaluate(() => document.activeElement.dataset.slug) === 'super-metroid', 'D-pad moves one grid row');
    await page.locator('#search').fill('  mOrTaL   kombat  ');
    check(await page.locator('.game').count() === 3, 'search ignores case and extra spaces');
    await page.locator('#search').fill("Yoshi's");
    check(await page.locator('.game').count() === 1, 'search normalizes apostrophes');
    await page.locator('#search').fill('Zelda'); await page.keyboard.press('Enter');
    check(await page.evaluate(() => calls.at(-1)[1]) === 'the-legend-of-zelda-a-link-to-the-past', 'one-result Enter opens exact game');
    await page.locator('#search').fill('Mario'); await page.keyboard.press('Enter');
    check(await page.evaluate(() => document.activeElement.classList.contains('game')), 'multi-result Enter focuses choices');
    await page.locator('#search').fill('no-such-title-xyz');
    check(await page.locator('.game').count() === 0 && await page.locator('#empty').isVisible(), 'empty result state');
    await page.keyboard.press('Enter');
    check(await page.evaluate(() => calls.at(-1).join('|')) === 'search|no-such-title-xyz', 'full-site search receives original query');
    await page.locator('#search').fill(''); await page.keyboard.press('ArrowUp'); await page.keyboard.press('ArrowRight');
    check(await page.evaluate(() => document.activeElement.id) === 'help', 'remote reaches Controls button');
    await page.keyboard.press('Enter');
    check(await page.evaluate(() => calls.at(-1)[0]) === 'help', 'remote opens control guide');
    check(errors.length === 0, 'catalog has no JavaScript errors');

    await page.setContent("<div id='game'><canvas></canvas></div><button class='ejs_start_button'>Start Game</button>");
    await page.evaluate(() => {
      window.events=[]; window.starts=0;
      document.querySelector('button').onclick=()=>starts++;
      window.EJS_emulator={started:false,paused:false,
        gameManager:{simulateInput:function(p,b,v){events.push([p,b,v]);}},
        pause:function(){this.paused=true;}, play:function(){this.paused=false;}};
      window.originalInput=EJS_emulator.gameManager.simulateInput;
    });
    const player = fs.readFileSync('app/src/main/assets/player.js','utf8');
    await page.addScriptTag({content:player});
    check(await page.evaluate(() => !SnesTV.ready() && !SnesTV.button(0,true)), 'unready runtime cannot accept input');
    await page.evaluate(() => {document.querySelector('button').style.display='none';SnesTV.start();});
    check(await page.evaluate(() => starts) === 0, 'hidden site Start button is respected');
    await page.evaluate(() => {document.querySelector('button').style.display='';SnesTV.start();});
    check(await page.evaluate(() => starts) === 1, 'visible site Start button is activated');
    await page.evaluate(() => {EJS_emulator.started=true;SnesTV.active(true);events=[];
      SnesTV.button(0,true);SnesTV.button(0,true);SnesTV.button(0,false);});
    check(JSON.stringify(await page.evaluate(() => events)) === '[[0,0,1],[0,0,0]]', 'input edges reach EmulatorJS once');
    await page.evaluate(() => {events=[];EJS_emulator.gameManager.simulateInput(0,0,1);});
    check((await page.evaluate(() => events)).length === 0, 'browser gamepad duplicates are gated');
    await page.evaluate(() => {SnesTV.button(6,true);window.dispatchEvent(new Event('blur'));});
    check(JSON.stringify(await page.evaluate(() => events)) === '[[0,6,1],[0,6,0]]', 'blur releases held directions');
    await page.evaluate(() => {events=[];SnesTV.button(1,true);SnesTV.suspend();});
    check(await page.evaluate(() => EJS_emulator.paused && events.at(-1)[2]===0), 'suspend pauses and releases');
    check(await page.evaluate(() => SnesTV.start()==='ready' && !EJS_emulator.paused), 'explicit Start resumes runtime');
    await page.evaluate(() => SnesTV.active(false));
    check(await page.evaluate(() => EJS_emulator.gameManager.simulateInput === originalInput), 'site input handler is restored');
    await page.addScriptTag({content:player});
    check(await page.evaluate(() => !!SnesTV.ready()), 'bridge injection is idempotent');
    console.log(`${passed} browser checks passed. Synthetic emulator fixture only; no gameplay verified.`);
  } finally { await browser.close(); }
})().catch(e => { console.error(e);process.exitCode=1; });
