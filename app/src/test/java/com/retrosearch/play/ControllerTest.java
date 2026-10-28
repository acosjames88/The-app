package com.retrosearch.play;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ControllerTest {
    @Test public void xboxAndPlayStationPhysicalPositions() {
        assertEquals(0, ControllerMapper.button(96, false));
        assertEquals(8, ControllerMapper.button(97, false));
        assertEquals(1, ControllerMapper.button(99, false));
        assertEquals(9, ControllerMapper.button(100, false));
    }
    @Test public void shouldersStartAndSelect() {
        assertEquals(10, ControllerMapper.button(102, false));
        assertEquals(11, ControllerMapper.button(103, false));
        assertEquals(3, ControllerMapper.button(108, false));
        assertEquals(2, ControllerMapper.button(109, false));
    }
    @Test public void remoteBanksProvideAllSnesButtons() {
        assertEquals(0, ControllerMapper.button(23, false));
        assertEquals(1, ControllerMapper.button(89, false));
        assertEquals(8, ControllerMapper.button(90, false));
        assertEquals(3, ControllerMapper.button(85, false));
        assertEquals(9, ControllerMapper.button(23, true));
        assertEquals(10, ControllerMapper.button(89, true));
        assertEquals(11, ControllerMapper.button(90, true));
        assertEquals(2, ControllerMapper.button(85, true));
        assertEquals(0, ControllerMapper.button(96, true)); // remote bank never remaps the pad
    }
    @Test public void systemButtonsStayUnmapped() {
        for (int key : new int[]{3, 4, 24, 25, 26, 82, 110}) assertEquals(-1, ControllerMapper.button(key, false));
    }
    @Test public void stickDeadzoneAndDiagonal() {
        assertEquals(0, ControllerMapper.directions(.12f, -.20f, 0, 0, .05f));
        assertEquals((1 << 4) | (1 << 7), ControllerMapper.directions(.8f, -.8f, 0, 0, .1f));
        assertEquals(0, ControllerMapper.directions(0, 0, 0, 0, .1f));
    }
    @Test public void hatOverridesStick() {
        assertEquals(1 << 6, ControllerMapper.directions(.9f, -.9f, -1, 0, 0));
        assertEquals(1 << 5, ControllerMapper.directions(0, 0, 0, 1, 0));
    }
    @Test public void duplicateDownAndUpEmitOnce() {
        List<String> edges = new ArrayList<>();
        InputRouter r = new InputRouter((b, d) -> edges.add(b + ":" + d));
        r.set("1:key", 0, true); r.set("1:key", 0, true);
        r.set("1:key", 0, false); r.set("1:key", 0, false);
        assertEquals(Arrays.asList("0:true", "0:false"), edges);
    }
    @Test public void independentSourcesDoNotReleaseEachOther() {
        List<String> edges = new ArrayList<>();
        InputRouter r = new InputRouter((b, d) -> edges.add(b + ":" + d));
        r.set("1:hat", 6, true); r.set("1:dpad", 6, true);
        r.set("1:hat", 6, false);
        assertEquals(Arrays.asList("6:true"), edges);
        r.set("1:dpad", 6, false);
        assertEquals(Arrays.asList("6:true", "6:false"), edges);
    }
    @Test public void disconnectPreservesOtherController() {
        List<String> edges = new ArrayList<>();
        InputRouter r = new InputRouter((b, d) -> edges.add(b + ":" + d));
        r.set("1:key", 0, true); r.set("2:key", 0, true);
        r.releaseDevice(1); assertEquals(1, edges.size());
        r.releaseDevice(2); assertEquals(Arrays.asList("0:true", "0:false"), edges);
    }
    @Test public void releaseAllClearsEveryHeldButton() {
        List<String> edges = new ArrayList<>();
        InputRouter r = new InputRouter((b, d) -> edges.add(b + ":" + d));
        r.set("2:key", 0, true); r.set("2:key2", 1, true);
        r.releaseAll(); assertEquals(4, edges.size());
        assertTrue(edges.contains("0:false")); assertTrue(edges.contains("1:false"));
        r.releaseAll(); assertEquals(4, edges.size());
    }
    @Test public void devicePrefixDoesNotMatchAnotherDevice() {
        List<String> edges = new ArrayList<>();
        InputRouter r = new InputRouter((b, d) -> edges.add(b + ":" + d));
        r.set("12:key", 0, true); r.releaseDevice(1); assertEquals(1, edges.size());
    }
    @Test public void invalidButtonsCannotReachPlayer() {
        List<String> edges = new ArrayList<>();
        InputRouter r = new InputRouter((b, d) -> edges.add(b + ":" + d));
        r.set("1:key", -1, true); r.set("1:key", 99, true); assertTrue(edges.isEmpty());
    }
}
