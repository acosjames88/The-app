package com.retrosearch.play;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdFreePlayerTest {
    private static final String PAGE = "https://sneslive.com/super-mario-world/";
    private static final String SOURCE = "<script>EJS_player='#game';EJS_gameUrl='/emulator/roms/supermarioworld.smc';EJS_core='snes';</script>";
    private static final String TEMPLATE = "<div id='game'></div><script>__SNES_GAME_CONFIG__</script>";

    @Test public void pageHasOnlyTrustedMetadataAndNoAds() throws Exception {
        String source = "<script src='https://ads.evil.test/video.js'></script><div>ADVERTISEMENT</div>" + SOURCE +
            "<script>EJS_AdUrl='https://ads.evil.test/iframe';window.siteScript=true;</script>";
        String html = AdFreePlayer.document(source,PAGE,TEMPLATE);
        assertTrue(html.contains("https://sneslive.com/emulator/roms/supermarioworld.smc"));
        assertTrue(html.contains("EJS_startOnLoaded=true"));
        assertTrue(html.contains("EJS_AdUrl=''"));
        assertFalse(html.contains("ADVERTISEMENT"));
        assertFalse(html.contains("ads.evil.test"));
        assertFalse(html.contains("siteScript"));
    }
    @Test public void emulatorAndRomAssetsAreAllowed() {
        for (String path : new String[]{"/emulator/data/loader.js", "/emulator/data/cores/snes9x-wasm.data",
                "/emulator/data/cores/reports/snes9x.json", "/emulator/data/emulator.css?v=4",
                "/emulator/roms/Super%20Mario.smc"}) assertTrue(path,AdFreePlayer.allowedAsset("https://sneslive.com"+path));
    }
    @Test public void adHostsPagesAndNetworkLookalikesAreBlocked() {
        for (String url : new String[]{"https://ads.cpmstar.com/video.js", "https://sneslive.com/ads.js",
                "https://sneslive.com/emulator/data/ad.html", "https://sneslive.com.evil.test/emulator/data/loader.js",
                "https://sneslive.com@evil.test/emulator/data/loader.js", "http://sneslive.com/emulator/data/loader.js",
                "https://sneslive.com:444/emulator/data/loader.js", "file:///emulator/data/loader.js"})
            assertFalse(url,AdFreePlayer.allowedAsset(url));
    }
    @Test public void traversalAndEncodedTraversalAreBlocked() {
        for (String path : new String[]{"/emulator/data/../ads.js", "/emulator/data/%2e%2e/ads.js",
                "/emulator/data/%252e%252e/ads.js", "/emulator/data/%5cads.js", "/emulator/data//ads.js"})
            assertFalse(path,AdFreePlayer.allowedAsset("https://sneslive.com"+path));
    }
    @Test public void gamePagesRequireOneSafeSlug() {
        assertTrue(AdFreePlayer.gamePage(PAGE));
        assertTrue(AdFreePlayer.gamePage("https://www.sneslive.com/alien-3"));
        for (String suffix : new String[]{"?ad=1", "#ad", "/nested"}) assertFalse(AdFreePlayer.gamePage(PAGE+suffix));
    }
    @Test(expected=IOException.class) public void externalRomRejected() throws Exception {
        AdFreePlayer.document(SOURCE.replace("/emulator/roms/supermarioworld.smc", "https://evil.test/game.smc"),PAGE,TEMPLATE);
    }
    @Test(expected=IOException.class) public void scriptInRomRejected() throws Exception {
        AdFreePlayer.document(SOURCE.replace("supermarioworld.smc", "payload.js"),PAGE,TEMPLATE);
    }
    @Test(expected=IOException.class) public void missingMetadataFailsClosed() throws Exception {
        AdFreePlayer.document("<html>Service unavailable</html>",PAGE,TEMPLATE);
    }
    @Test(expected=IOException.class) public void expressionInsteadOfLiteralRejected() throws Exception {
        AdFreePlayer.document("EJS_core='snes';EJS_gameUrl=stealCookies();",PAGE,TEMPLATE);
    }
    @Test(expected=IOException.class) public void unknownSystemRejected() throws Exception {
        AdFreePlayer.document(SOURCE.replace("'snes'", "'n64'"),PAGE,TEMPLATE);
    }
    @Test public void relativeRomAndWwwHostWork() throws Exception {
        String result=AdFreePlayer.document(SOURCE.replace("/emulator/roms/", "../emulator/roms/"),
            "https://www.sneslive.com/super-mario-world/",TEMPLATE);
        assertTrue(result.contains("https://www.sneslive.com/emulator/roms/supermarioworld.smc"));
    }
    @Test public void errorsContainNoSiteContent() {
        assertTrue(AdFreePlayer.errorDocument().contains("Failed to download game"));
        assertTrue(AdFreePlayer.errorDocument().contains("default-src 'none'"));
        assertFalse(AdFreePlayer.errorDocument().contains("<script"));
    }
}
