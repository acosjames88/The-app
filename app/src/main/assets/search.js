/* Read only the site's main search results. No Android bridge exists on this page. */
(function () {
    'use strict';
    var main = document.querySelector('main.main-cn, main');
    if (!main) return {valid: false, games: [], more: false};
    var games = [], seen = {};
    Array.prototype.forEach.call(main.querySelectorAll('.lst-gams.cate-grid .gm-titl a'), function (a) {
        try {
            var url = new URL(a.href), slug = url.pathname.replace(/^\/+|\/+$/g, '');
            if (url.protocol !== 'https:' || !/^(www\.)?sneslive\.com$/i.test(url.hostname) ||
                url.username || url.password || url.port || url.search || url.hash ||
                !/^[a-z0-9-]{1,100}$/.test(slug) || seen[slug] || games.length >= 60) return;
            var title = a.textContent.replace(/\s+/g, ' ').trim().slice(0,160);
            if (!title) return;
            seen[slug] = true;
            games.push([title, slug, 'Online game']);
        } catch (_) {}
    });
    var current = Number((location.pathname.match(/\/page\/(\d+)/) || [0,1])[1]);
    var more = Array.prototype.some.call(main.querySelectorAll('a[href]'), function (a) {
        try {
            var next = new URL(a.href), match = next.pathname.match(/^\/page\/(\d+)\/?$/);
            return next.protocol === 'https:' && /^(www\.)?sneslive\.com$/i.test(next.hostname) &&
                !next.username && !next.password && !next.port && match &&
                Number(match[1]) === current + 1 && next.searchParams.get('s') === new URL(location.href).searchParams.get('s');
        } catch (_) { return false; }
    });
    return {valid: true, games: games, more: more};
})();
