package com.retrosearch.play;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Website responses are data only. No HTML, scripts, ads, or pages are displayed. */
final class RomRepository {
    static final int MAX_ROM = 16 * 1024 * 1024;
    interface Fetcher { byte[] get(String url, boolean page) throws IOException; }
    private final File cache;
    private final Fetcher fetcher;
    RomRepository(File directory) { this(directory, RomRepository::download); }
    RomRepository(File directory, Fetcher fetcher) { cache = directory; this.fetcher = fetcher; directory.mkdirs(); }
    static boolean site(String value) {
        try {
            URI u = new URI(value);
            return "https".equals(u.getScheme()) && u.getRawUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                && ("sneslive.com".equalsIgnoreCase(u.getHost()) || "www.sneslive.com".equalsIgnoreCase(u.getHost()))
                && u.getRawFragment() == null && safePath(u.getPath());
        } catch (Exception error) { return false; }
    }
    private static boolean safePath(String path) {
        if (path == null || path.contains("\\") || path.contains("%") || path.contains("\0") || path.contains("//")) return false;
        for (String part : path.split("/")) if (".".equals(part) || "..".equals(part)) return false;
        return true;
    }
    static boolean page(String value) {
        if (!site(value)) return false;
        URI u = URI.create(value);
        return u.getRawQuery() == null && u.getPath().matches("/[a-z0-9-]{1,100}/?");
    }
    static boolean romUrl(String value) {
        if (!site(value)) return false;
        URI u = URI.create(value);
        return u.getRawQuery() == null && u.getPath().startsWith("/emulator/roms/")
            && u.getPath().matches("(?i).+\\.(smc|sfc|fig|swc|bin|zip)$");
    }
    static String romFromPage(String html, String url) throws IOException {
        if (!page(url) || html == null || html.length() > 2 * 1024 * 1024) throw new IOException("Game information is unavailable.");
        if (!"snes".equals(literal(html, "EJS_core"))) throw new IOException("This is not a supported SNES game.");
        try {
            String rom = URI.create(url).resolve(literal(html, "EJS_gameUrl")).toASCIIString();
            if (!romUrl(rom)) throw new IOException("A game file is unavailable.");
            return rom;
        } catch (IllegalArgumentException e) { throw new IOException("The game file address is invalid.", e); }
    }
    private static String literal(String html, String key) throws IOException {
        Matcher m = Pattern.compile("\\b(?:window\\.)?" + Pattern.quote(key) + "\\s*=\\s*(['\"])([^\\r\\n]{1,1000}?)\\1\\s*;").matcher(html);
        if (!m.find() || m.group(2).contains("\\")) throw new IOException("A game file is unavailable.");
        return m.group(2);
    }
    File file(String slug) {
        if (slug == null || !slug.matches("[a-z0-9-]{1,100}")) throw new IllegalArgumentException("Invalid game");
        return new File(cache, slug + ".sfc");
    }
    byte[] game(String slug) throws IOException {
        File saved = file(slug);
        if (saved.isFile()) {
            try (InputStream in = new FileInputStream(saved)) { return validate(read(in, MAX_ROM)); }
            catch (IOException invalid) { if (!saved.delete()) throw invalid; }
        }
        String pageUrl = "https://sneslive.com/" + slug + "/";
        String html = new String(fetcher.get(pageUrl, true), StandardCharsets.UTF_8);
        String rom = romFromPage(html, pageUrl);
        byte[] data = unpack(fetcher.get(rom, false));
        File temp = new File(cache, slug + ".partial");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); out.getFD().sync(); }
            if (!temp.renameTo(saved)) throw new IOException("The game could not be saved.");
        } finally { if (temp.exists()) temp.delete(); }
        return data;
    }
    static byte[] unpack(byte[] bytes) throws IOException {
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                int entries = 0; long skipped = 0; byte[] buffer = new byte[8192];
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (++entries > 100 || Thread.currentThread().isInterrupted()) throw new IOException("Game archive is too large.");
                    if (entry.isDirectory()) continue;
                    if (entry.getName().toLowerCase(Locale.ROOT).matches(".*\\.(sfc|smc|fig|swc|bin)$")) return validate(read(zip, MAX_ROM));
                    int n; while ((n = zip.read(buffer)) != -1) {
                        skipped += n; if (skipped > MAX_ROM) throw new IOException("Game archive is too large.");
                    }
                }
            }
            throw new IOException("No SNES game was found in this archive.");
        }
        return validate(bytes);
    }
    static byte[] validate(byte[] bytes) throws IOException {
        if (bytes.length < 32768 || bytes.length > MAX_ROM || !(bytes.length % 1024 == 0 || bytes.length % 1024 == 512))
            throw new IOException("This file is not a supported SNES game.");
        int offset = bytes.length % 1024 == 512 ? 512 : 0;
        boolean valid = false;
        for (int header : new int[]{0x7fc0, 0xffc0, 0x407fc0}) {
            if (offset + header + 64 > bytes.length) continue;
            int mode = bytes[offset + header + 21] & 0x2f;
            int reset = (bytes[offset + header + 60] & 255) | (bytes[offset + header + 61] & 255) << 8;
            if ((mode == 0x20 || mode == 0x21 || mode == 0x22 || mode == 0x25) && reset >= 0x8000) valid = true;
        }
        if (!valid) throw new IOException("This file does not contain a valid SNES header.");
        return bytes;
    }
    static byte[] read(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = stream.read(buffer)) != -1) {
            if (out.size() + n > limit || Thread.currentThread().isInterrupted()) throw new IOException("Game download canceled or too large.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static byte[] download(String initial, boolean isPage) throws IOException {
        String url = initial;
        for (int redirects = 0; redirects < 5; redirects++) {
            if (!(isPage ? site(url) : romUrl(url))) throw new IOException("The game address is unsupported.");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept", isPage ? "text/html" : "application/octet-stream,*/*");
            if (!isPage) c.setRequestProperty("Referer", "https://sneslive.com/");
            try {
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("The game server returned an invalid redirect.");
                    url = new URL(new URL(url), location).toExternalForm(); continue;
                }
                if (status != 200) throw new IOException("The game download is unavailable (HTTP " + status + ").");
                try (InputStream in = c.getInputStream()) { return read(in, isPage ? 2 * 1024 * 1024 : MAX_ROM + 1024 * 1024); }
            } finally { c.disconnect(); }
        }
        throw new IOException("The game download redirected too many times.");
    }
    List<GameCatalog.Game> search(String query, int pageNumber) throws IOException {
        String path = pageNumber == 1 ? "/" : "/page/" + pageNumber + "/";
        String url = "https://sneslive.com" + path + "?s=" + URLEncoder.encode(query, "UTF-8");
        String html = new String(fetcher.get(url, true), StandardCharsets.UTF_8);
        return parseSearch(html);
    }
    static List<GameCatalog.Game> parseSearch(String html) {
        List<GameCatalog.Game> result = new ArrayList<>();
        Matcher articles = Pattern.compile("<article\\b[^>]*>(.*?)</article>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        while (articles.find() && result.size() < 60) {
            Matcher links = Pattern.compile("<a\\b[^>]*href\\s*=\\s*(['\"])(https://(?:www\\.)?sneslive\\.com/([a-z0-9-]{1,100})/?)\\1[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(articles.group(1));
            while (links.find()) {
                String title = links.group(4).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim()
                    .replace("&amp;", "&").replace("&#8217;", "'").replace("&quot;", "\"").replace("&#039;", "'");
                String slug = links.group(3);
                if (title.length() < 2 || title.length() > 160 || !page(links.group(2)) || !seen.add(slug)) continue;
                result.add(new GameCatalog.Game(title, slug)); break;
            }
        }
        return result;
    }
}
