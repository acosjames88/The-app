/* Runs only in a SNESLive page. No Android JavascriptInterface is exposed here. */
(function () {
    'use strict';
    if (window.SnesTV) return;
    var held = {}, ownedEngine = null, originalInput = null;
    function engine() {
        var e = window.EJS_emulator;
        return e && e.started && e.gameManager &&
            typeof e.gameManager.simulateInput === 'function' ? e : null;
    }
    function visible(node) {
        return node && node.getClientRects().length > 0 &&
            getComputedStyle(node).visibility !== 'hidden';
    }
    function presentGame(active) {
        if (active && !document.getElementById('snes-tv-layout')) {
            var style = document.createElement('style');
            style.id = 'snes-tv-layout';
            style.textContent = 'html.snes-tv-playing,html.snes-tv-playing body{margin:0!important;overflow:hidden!important;background:#000!important}' +
                'html.snes-tv-playing #game{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;z-index:2147480000!important;background:#000!important}' +
                'html.snes-tv-playing #game .ejs_parent{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important}' +
                'html.snes-tv-playing #game canvas{object-fit:contain!important;max-width:100%!important;max-height:100%!important}';
            document.head.appendChild(style);
        }
        document.documentElement.classList.toggle('snes-tv-playing', !!active);
    }
    function focus() {
        var node = document.querySelector('#game canvas, .ejs_canvas, #game');
        if (!visible(node)) return false;
        node.setAttribute('tabindex', '0');
        node.focus({preventScroll: true});
        node.scrollIntoView({block: 'center', inline: 'nearest'});
        return true;
    }
    function release() {
        var e = ownedEngine || engine();
        Object.keys(held).forEach(function (key) {
            try {
                if (e) (originalInput || e.gameManager.simulateInput).call(e.gameManager, 0, Number(key), 0);
            } catch (_) {}
        });
        held = {};
    }
    function setActive(active) {
        release();
        // Browser gamepad callbacks are already bound in EmulatorJS. Gate its input
        // sink, rather than replacing the callback and leaving the bound copy active.
        if (!active && ownedEngine) {
            ownedEngine.gameManager.simulateInput = originalInput;
            ownedEngine = null; originalInput = null;
        }
        var e = engine();
        if (active && e && ownedEngine !== e) {
            ownedEngine = e;
            originalInput = e.gameManager.simulateInput;
            for (var i = 0; i < 12; i++) originalInput.call(e.gameManager, 0, i, 0);
            e.gameManager.simulateInput = function (player, index, value) {
                if (player === 0 && index >= 0 && index <= 11) return;
                return originalInput.call(e.gameManager, player, index, value);
            };
        }
        presentGame(active && !!e);
        if (active) focus();
        return !!e;
    }
    window.SnesTV = {
        ready: function () { return !!engine(); },
        start: function () {
            var game = document.getElementById('game');
            if (game && /failed to start game|failed to download|error downloading/i.test(game.innerText)) return 'failed';
            if (engine()) {
                var e = engine();
                if (e.paused && typeof e.play === 'function') e.play();
                focus(); return 'ready';
            }
            // Let the site's pre-roll finish normally. Never click advertisement links or skip controls.
            var ad = document.querySelector('[id^="cpmstar_modalVideoPopup_"]');
            if (visible(ad)) {
                var video = ad.querySelector('video');
                if (video && video.paused) {
                    try { var playback = video.play(); if (playback) playback.catch(function () {}); } catch (_) {}
                }
                return 'preroll';
            }
            // Click only the site's visible emulator start button; respect its loading/ad flow.
            var button = document.querySelector('.ejs_start_button, .ejs_start');
            if (visible(button)) { button.click(); return 'starting'; }
            return 'waiting';
        },
        active: setActive,
        button: function (index, down) {
            var e = engine();
            if (!e || index < 0 || index > 11 || index % 1) return false;
            if (e.paused && down) return false;
            if (!!held[index] === !!down) return true;
            (originalInput || e.gameManager.simulateInput).call(e.gameManager, 0, index, down ? 1 : 0);
            if (down) held[index] = true; else delete held[index];
            return true;
        },
        release: release,
        suspend: function () {
            release();
            var e = engine();
            if (e && !e.paused && typeof e.pause === 'function') e.pause();
        },
        focus: focus
    };
    window.addEventListener('blur', release);
    document.addEventListener('visibilitychange', function () { if (document.hidden) release(); });
})();
