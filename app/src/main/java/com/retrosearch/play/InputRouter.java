package com.retrosearch.play;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Merge simultaneous input sources and emit only edges, including release on disconnect. */
public final class InputRouter {
    public interface Sink { void send(int button, boolean down); }
    private final Sink sink;
    private final Map<String, Set<Integer>> held = new HashMap<>();
    public InputRouter(Sink sink) { this.sink = sink; }
    public void set(String source, int button, boolean down) {
        if (button < 0 || button > 11) return;
        boolean before = pressed(button);
        Set<Integer> buttons = held.get(source);
        if (down) {
            if (buttons == null) { buttons = new HashSet<>(); held.put(source, buttons); }
            buttons.add(button);
        } else if (buttons != null) {
            buttons.remove(button);
            if (buttons.isEmpty()) held.remove(source);
        }
        boolean after = pressed(button);
        if (before != after) sink.send(button, after);
    }
    private boolean pressed(int button) {
        for (Set<Integer> buttons : held.values()) if (buttons.contains(button)) return true;
        return false;
    }
    public void releaseDevice(int deviceId) {
        String prefix = deviceId + ":";
        for (String source : new HashSet<>(held.keySet())) {
            if (source.startsWith(prefix)) {
                for (Integer button : new HashSet<>(held.get(source))) set(source, button, false);
            }
        }
    }
    public void releaseAll() {
        Set<Integer> buttons = new HashSet<>();
        for (Set<Integer> values : held.values()) buttons.addAll(values);
        held.clear();
        for (Integer button : buttons) sink.send(button, false);
    }
}
