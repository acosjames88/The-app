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
        searchAll: (...args) => calls.push(['search',...args]), help: () => calls.push(['help'])};
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
    check(await page.evaluate(() => calls.at(-1)[0]==='search' && calls.at(-1)[1]==='no-such-title-xyz' && calls.at(-1)[2]===1), 'online search receives query, page and request token');
    await page.evaluate(() => {const c=calls.at(-1);SnesCatalog.receiveSearch(c[3],c[1],1,[['Alien 3','alien-3','Online game']],true,'');});
    check(await page.locator('.game').count()===1 && (await page.locator('.game b').innerText())==='Alien 3', 'online results appear in the app catalog');
    check(await page.evaluate(() => document.activeElement.dataset.slug==='alien-3'), 'online result receives remote focus');
    await page.keyboard.press('Enter');
    check(await page.evaluate(() => calls.at(-1).join('|'))==='play|alien-3','online game opens directly without website navigation');
    await page.keyboard.press('ArrowDown');
    check(await page.evaluate(() => document.activeElement.id)==='next','D-pad reaches next-page control');
    await page.keyboard.press('Enter');
    check(await page.evaluate(() => calls.at(-1)[2])===2,'next page remains an in-app search');
    await page.locator('#search').fill('Zelda');
    await page.evaluate(() => {const c=calls.at(-1);SnesCatalog.receiveSearch(c[3],c[1],2,[['Old result','old-result','Online game']],false,'');});
    check(await page.locator('.game').count()===1 && (await page.locator('.game b').innerText()).includes('Zelda'),'stale search response cannot replace a new query');
    await page.locator('#all').click();
    await page.evaluate(() => {const c=calls.at(-1);SnesCatalog.receiveSearch(c[3],c[1],1,[],false,'Online search is unavailable.');});
    check((await page.locator('#online-status').innerText()).includes('unavailable') && (await page.url()).startsWith('file:'),'network errors stay inside the app');
    await page.locator('#all').click();
    await page.evaluate(()=>SnesCatalog.cancelPending());
    check((await page.locator('#online-status').innerText()).includes('paused'),'paused searches can be retried');
    await page.locator('#featured').click();
    check(await page.locator('.game').count()===57,'Featured restores offline catalog');
    await page.locator('#search').focus(); await page.keyboard.press('ArrowUp'); await page.keyboard.press('ArrowRight');await page.keyboard.press('ArrowRight');
    check(await page.evaluate(() => document.activeElement.id) === 'help', 'remote reaches Controls button');
    await page.keyboard.press('Enter');
    check(await page.evaluate(() => calls.at(-1)[0]) === 'help', 'remote opens control guide');
    check(errors.length === 0, 'catalog has no JavaScript errors');

    await page.setContent("<main class='main-cn'><ul class='lst-gams cate-grid'><li><div class='gm-titl'><a href='https://sneslive.com/alien-3/'>Alien &amp; Friends</a></div><div class='gm-titl'><a href='https://sneslive.com/alien-3/'>Duplicate</a><a href='https://evil.test/game/'>Bad host</a><a href='https://sneslive.com@evil.test/game/'>Bad user info</a><a href='javascript:alert(1)'>Script</a><a href='https://sneslive.com/category/game/'>Nested path</a></div></li></ul></main><aside><div class='gm-titl'><a href='https://sneslive.com/sidebar/'>Sidebar</a></div></aside>");
    const search = fs.readFileSync('app/src/main/assets/search.js','utf8');
    const results=await page.evaluate(search);
    check(results.valid && JSON.stringify(results.games)==='[["Alien & Friends","alien-3","Online game"]]','search parser scopes main results, decodes titles and rejects unsafe links and duplicates');
    await page.setContent('<main><p>No games found</p></main>');
    check((await page.evaluate(search)).games.length===0,'online no-results state is supported');
    await page.setContent('<p>Service unavailable</p>');
    check(!(await page.evaluate(search)).valid,'unexpected search markup is rejected');

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
    await page.evaluate(() => {const ad=document.createElement('div');ad.id='cpmstar_modalVideoPopup_test';ad.textContent='Pre-roll';document.body.appendChild(ad);SnesTV.start();});
    check(await page.evaluate(() => SnesTV.start()==='preroll' && starts===1),'pre-roll is respected before emulator Start');
    await page.evaluate(()=>document.getElementById('cpmstar_modalVideoPopup_test').remove());
    await page.evaluate(() => {EJS_emulator.started=true;SnesTV.active(true);events=[];
      SnesTV.button(0,true);SnesTV.button(0,true);SnesTV.button(0,false);});
    check(JSON.stringify(await page.evaluate(() => events)) === '[[0,0,1],[0,0,0]]', 'input edges reach EmulatorJS once');
    check(await page.evaluate(()=>{const r=document.getElementById('game').getBoundingClientRect();return r.x===0 && r.y===0 && r.width===innerWidth && r.height===innerHeight;}),'running game fills the viewport');
    await page.evaluate(() => {events=[];EJS_emulator.gameManager.simulateInput(0,0,1);});
    check((await page.evaluate(() => events)).length === 0, 'browser gamepad duplicates are gated');
    await page.evaluate(() => {SnesTV.button(6,true);window.dispatchEvent(new Event('blur'));});
    check(JSON.stringify(await page.evaluate(() => events)) === '[[0,6,1],[0,6,0]]', 'blur releases held directions');
    await page.evaluate(() => {events=[];SnesTV.button(1,true);SnesTV.suspend();});
    check(await page.evaluate(() => EJS_emulator.paused && events.at(-1)[2]===0), 'suspend pauses and releases');
    check(await page.evaluate(() => SnesTV.start()==='ready' && !EJS_emulator.paused), 'explicit Start resumes runtime');
    await page.evaluate(() => SnesTV.active(false));
    check(await page.evaluate(() => EJS_emulator.gameManager.simulateInput === originalInput), 'site input handler is restored');
    check(await page.evaluate(()=>!document.documentElement.classList.contains('snes-tv-playing')),'leaving game mode restores the normal page layout');
    await page.addScriptTag({content:player});
    check(await page.evaluate(() => !!SnesTV.ready()), 'bridge injection is idempotent');
    await page.evaluate(()=>{document.getElementById('game').textContent='Failed to start game';});
    check(await page.evaluate(()=>SnesTV.start()==='failed'),'live emulator startup errors are surfaced instead of waiting indefinitely');
    console.log(`${passed} browser checks passed. Synthetic emulator fixture only; no gameplay verified.`);
  } finally { await browser.close(); }
})().catch(e => { console.error(e);process.exitCode=1; });
