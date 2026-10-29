package com.retrosearch.play;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Copies only validated emulator metadata into the app-owned player document. */
final class AdFreePlayer {
    private AdFreePlayer() {}

    static boolean siteUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) &&
                ("sneslive.com".equalsIgnoreCase(uri.getHost()) || "www.sneslive.com".equalsIgnoreCase(uri.getHost())) &&
                uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (URISyntaxException invalid) { return false; }
    }

    static boolean gamePage(String value) {
        if (!siteUrl(value)) return false;
        try {
            URI uri = new URI(value);
            return uri.getRawQuery() == null && uri.getRawFragment() == null &&
                uri.getRawPath().matches("/[a-z0-9-]{1,100}/?");
        } catch (URISyntaxException invalid) { return false; }
    }

    static boolean allowedAsset(String value) {
        if (!siteUrl(value)) return false;
        try {
            URI uri = new URI(value);
            String path = uri.getPath();
            if (path == null || path.indexOf('\\') >= 0 || path.indexOf('%') >= 0 || path.indexOf('\0') >= 0 ||
                path.contains("//") || uri.getRawFragment() != null) return false;
            for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) return false;
            if (path.startsWith("/emulator/data/")) {
                return path.matches("(?i).+\\.(js|wasm|data|json|css|png|jpg|jpeg|webp|svg|ttf|woff|woff2|bin|zip|7z)$");
            }
            return path.startsWith("/emulator/roms/") &&
                path.matches("(?i).+\\.(smc|sfc|fig|swc|bin|zip|7z)$");
        } catch (URISyntaxException invalid) { return false; }
    }

    private static String literal(String source, String key) throws IOException {
        Pattern pattern = Pattern.compile("\\b(?:window\\.)?" + Pattern.quote(key) +
            "\\s*=\\s*(['\"])([^\\r\\n]{1,1000}?)\\1\\s*;");
        Matcher match = pattern.matcher(source);
        if (!match.find() || match.group(2).indexOf('\\') >= 0) throw new IOException("Missing game metadata");
        return match.group(2);
    }

    static String document(String source, String pageUrl, String template) throws IOException {
        if (!gamePage(pageUrl) || source == null || source.length() > 2 * 1024 * 1024 ||
            template == null || !template.contains("__SNES_GAME_CONFIG__")) throw new IOException("Invalid game page");
        if (!"snes".equals(literal(source, "EJS_core"))) throw new IOException("Unsupported game system");
        final URI rom;
        try { rom = new URI(pageUrl).resolve(new URI(literal(source, "EJS_gameUrl"))); }
        catch (URISyntaxException invalid) { throw new IOException("Invalid game URL", invalid); }
        String romUrl = rom.toASCIIString();
        if (!allowedAsset(romUrl) || !rom.getPath().startsWith("/emulator/roms/") || rom.getRawQuery() != null)
            throw new IOException("Invalid game asset");
        // No website HTML, scripts, ad URLs, or event handlers enter this document.
        String config = "window.EJS_player='#game';window.EJS_core='snes';" +
            "window.EJS_gameUrl=" + quote(romUrl) + ";" +
            "window.EJS_pathtodata='https://sneslive.com/emulator/data/';" +
            "window.EJS_startOnLoaded=true;window.EJS_AdUrl='';window.EJS_AdTimer=-1;";
        return template.replace("__SNES_GAME_CONFIG__", config);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "\"";
    }

    static String errorDocument() {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none';style-src 'unsafe-inline'\">" +
            "<style>html,body{margin:0;background:#000;color:white;font:22px sans-serif}#game{padding:48px}</style>" +
            "</head><body><div id='game'>Failed to download game. Choose Reload or Games.</div></body></html>";
    }
}
