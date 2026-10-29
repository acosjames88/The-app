package com.retrosearch.play;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RomRepositoryTest {
    private byte[] rom() {
        byte[] data = new byte[32768]; data[0x7fd5] = 0x20; data[0x7ffc] = 0; data[0x7ffd] = (byte)0x80; return data;
    }
    private byte[] zip(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) { z.putNextEntry(new ZipEntry(name)); z.write(content); z.closeEntry(); }
        return bytes.toByteArray();
    }
    private String metadata(String url) { return "EJS_core='snes'; EJS_gameUrl='" + url + "';"; }
    @Test public void extractsOnlyGameDataFromAdPage() throws Exception {
        String html = "<script src='https://ads.example/x.js'></script><iframe src='https://ads.example'></iframe>" + metadata("/emulator/roms/game.zip");
        assertEquals("https://sneslive.com/emulator/roms/game.zip", RomRepository.romFromPage(html,"https://sneslive.com/game/"));
    }
    @Test public void refusesUnrelatedGameAssetsAndHosts() {
        for (String url : new String[]{"https://ads.example/game.sfc", "https://sneslive.com.evil.example/emulator/roms/x.sfc",
            "https://sneslive.com@evil.example/emulator/roms/x.sfc", "http://sneslive.com/emulator/roms/x.sfc",
            "https://sneslive.com/emulator/roms/../data/x.bin", "https://sneslive.com/emulator/roms/%2e%2e/x.bin",
            "https://sneslive.com/emulator/roms/x.sfc?track=1", "https://sneslive.com/ads/x.bin"})
            assertFalse(url, RomRepository.romUrl(url));
    }
    @Test public void doesNotAcceptHtmlDisguisedAsRom() {
        byte[] html = new byte[32768]; Arrays.fill(html, (byte)' ');
        try { RomRepository.validate(html); fail(); } catch (IOException expected) {}
    }
    @Test public void supportsCopierHeaderAndLoRom() throws Exception {
        byte[] data = rom(); assertSame(data, RomRepository.validate(data));
        byte[] header = new byte[33280]; System.arraycopy(data,0,header,512,data.length);
        assertSame(header, RomRepository.validate(header));
    }
    @Test public void readsZipWithoutExtractingPathsToFilesystem() throws Exception {
        byte[] data = rom(); assertArrayEquals(data, RomRepository.unpack(zip("../../outside/game.sfc", data)));
    }
    @Test public void rejectsArchiveWithoutGame() throws Exception {
        try { RomRepository.unpack(zip("banner.html", new byte[10])); fail(); } catch (IOException expected) {}
    }
    @Test public void boundsDecompressedGameSize() throws Exception {
        try { RomRepository.unpack(zip("oversized.sfc", new byte[RomRepository.MAX_ROM+1])); fail(); } catch (IOException expected) {}
    }
    @Test public void cancellationStopsRead() {
        Thread.currentThread().interrupt();
        try { RomRepository.read(new ByteArrayInputStream(new byte[30]),100); fail(); }
        catch(IOException expected) {} finally { Thread.interrupted(); }
    }
    @Test public void cachedGameWorksWithNetworkUnavailable() throws Exception {
        File directory = Files.createTempDirectory("snes-cache").toFile();
        AtomicInteger calls = new AtomicInteger(); byte[] data = rom();
        RomRepository repo = new RomRepository(directory, (url,page) -> {
            calls.incrementAndGet(); return page ? metadata("/emulator/roms/game.sfc").getBytes("UTF-8") : data;
        });
        assertArrayEquals(data, repo.game("game")); assertEquals(2,calls.get());
        RomRepository offline = new RomRepository(directory, (url,page) -> { throw new IOException("Offline"); });
        assertArrayEquals(data, offline.game("game")); assertEquals(1,directory.listFiles().length);
    }
    @Test public void invalidDownloadNeverCreatesPlayableCache() throws Exception {
        File directory = Files.createTempDirectory("snes-cache-failure").toFile();
        RomRepository repo = new RomRepository(directory, (url,page) -> page ? metadata("/emulator/roms/game.sfc").getBytes("UTF-8") : new byte[32768]);
        try { repo.game("game"); fail(); } catch(IOException expected) {}
        assertFalse(repo.file("game").exists());
    }
    @Test public void cachedPathCannotEscapeDirectory() throws Exception {
        RomRepository repo = new RomRepository(Files.createTempDirectory("snes-cache-path").toFile());
        for (String bad : new String[]{"../game","GAME","a/b","",null})
            try { repo.file(bad); fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void nativeSearchReturnsGameRowsAndNoAdLinks() {
        String html = "<article><a href='https://ads.example/ad'>Ad</a><h2><a href='https://sneslive.com/game/'>Game &amp; Fun</a></h2></article>";
        assertEquals(1,RomRepository.parseSearch(html).size());
        assertEquals("Game & Fun",RomRepository.parseSearch(html).get(0).title);
    }
}
