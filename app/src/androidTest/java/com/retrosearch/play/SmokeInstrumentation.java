package com.retrosearch.play;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real Activity/WebView integration using a clearly synthetic emulator input sink. No ROM/gameplay test. */
public class SmokeInstrumentation extends Instrumentation {
    private MainActivity activity;
    private int passed;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; Bundle b = new Bundle(); b.putString("stream", "PASS " + name + "\n"); sendStatus(0, b);
    }
    private String js(boolean catalog, String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> (catalog ? activity.catalog : activity.player).evaluateJavascript(code, result -> {
            value.set(result); latch.countDown();
        }));
        if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("JavaScript timed out: " + code);
        return value.get();
    }
    private void waitFor(boolean catalog, String code) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 45000;
        while (SystemClock.uptimeMillis() < deadline) {
            if ("true".equals(js(catalog, code))) return;
            SystemClock.sleep(200);
        }
        throw new AssertionError("Timed out: " + code + "; catalog=" + js(true,
            "JSON.stringify({status:document.getElementById('online-status').textContent,heading:document.getElementById('heading').textContent,count:document.querySelectorAll('.game').length})"));
    }
    private void key(int code, boolean down, int device) {
        long now = SystemClock.uptimeMillis();
        runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(now, now,
            down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, 0, device, 0, 0, InputDevice.SOURCE_GAMEPAD)));
    }
    private void axis(float x, float y) {
        runOnMainSync(() -> {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties(); p.id = 0;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.setAxisValue(MotionEvent.AXIS_X, x); c.setAxisValue(MotionEvent.AXIS_Y, y);
            long now = SystemClock.uptimeMillis();
            MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, 0, 1, 1, 99, 0, InputDevice.SOURCE_JOYSTICK, 0);
            activity.dispatchGenericMotionEvent(e); e.recycle();
        });
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(), MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            waitFor(true, "!!window.SnesCatalog && document.querySelectorAll('.game').length>50");
            check(true, "catalog loads offline in real Android WebView");
            check("true".equals(js(true, "document.activeElement.classList.contains('game')")), "initial TV focus is a game");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            check("true".equals(js(true, "document.activeElement.dataset.slug==='nba-jam'")), "D-pad moves between games");
            axis(.9f,0); axis(0,0);
            check("true".equals(js(true, "document.activeElement.dataset.slug==='donkey-kong-country'")), "left stick browses catalog");
            js(true, "document.getElementById('search').value='Zelda';document.getElementById('search').dispatchEvent(new Event('input'));true");
            check("1".equals(js(true, "document.querySelectorAll('.game').length")), "local game search filters results");
            String searchFixture = "<!doctype html><html><body><main class='main-cn'><ul class='lst-gams cate-grid'>" +
                "<li><div class='gm-titl'><a href='https://sneslive.com/alien-3/'>Alien 3</a>" +
                "<a href='https://evil.test/escape/'>Unsafe</a></div></li></ul></main>" +
                "<script>if(typeof SnesApp!=='undefined')document.querySelector('a').textContent='UNSAFE BRIDGE';</script></body></html>";
            runOnMainSync(() -> activity.searchLoader = (view,url) ->
                view.loadDataWithBaseURL(url,searchFixture,"text/html","UTF-8",null));
            js(true, "document.getElementById('search').value='Alien';document.getElementById('search').dispatchEvent(new Event('input'));document.getElementById('all').click();true");
            waitFor(true, "document.querySelectorAll('.game').length===1 && document.querySelector('.game').dataset.slug==='alien-3'");
            check(activity.player == null, "online search stays in catalog instead of opening website");
            check("true".equals(js(true,"document.querySelector('.game b').textContent==='Alien 3'")), "search WebView has no privileged bridge and safely returns online results");
            check(activity.searchWeb == null, "search WebView is destroyed after delivering results");
            check("true".equals(js(true,"document.activeElement.dataset.slug==='alien-3'")), "online result receives TV focus");
            js(true, "SnesApp.play('super-mario-world');true");
            long deadline = SystemClock.uptimeMillis()+15000;
            while (activity.player == null && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100);
            check(activity.player != null, "catalog selection opens native player directly");
            check(MainActivity.allowedPlayerUrl("https://sneslive.com/super-mario-world/"), "SNESLive URL allowed");
            check(!MainActivity.allowedPlayerUrl("https://sneslive.com.evil.test/") &&
                !MainActivity.allowedPlayerUrl("https://sneslive.com@evil.test/") &&
                !MainActivity.allowedPlayerUrl("file:///etc/passwd") &&
                !MainActivity.allowedPlayerUrl("http://sneslive.com/"), "external and insecure URLs rejected");
            String fixture = "<!doctype html><html><body><div id='game'><canvas width='256' height='224'></canvas></div>" +
                "<script>window.events=[];window.EJS_emulator={started:true,paused:false,gameManager:{simulateInput:function(p,i,v){events.push([p,i,v])}}," +
                "pause:function(){this.paused=true},play:function(){this.paused=false}};</script></body></html>";
            runOnMainSync(() -> { activity.player.stopLoading(); activity.player.loadDataWithBaseURL(
                "https://sneslive.com/__synthetic_test__/", fixture, "text/html", "UTF-8", null); });
            waitFor(false, "!!window.SnesTV && SnesTV.ready()");
            runOnMainSync(() -> activity.setGameMode(true));
            js(false, "events=[];true");
            check("true".equals(js(false,"document.documentElement.classList.contains('snes-tv-playing')")), "native game mode enables full-screen player layout");
            check("\"undefined\"".equals(js(false, "typeof SnesApp")), "external player has no privileged Android bridge");
            int[] codes = {96,97,99,100,102,103,108,109,19,20,21,22,23,85,89,90};
            int[] buttons = {0,8,1,9,10,11,3,2,4,5,6,7,0,3,1,8};
            for (int i=0;i<codes.length;i++) {
                js(false,"events=[];true"); key(codes[i],true,71); key(codes[i],false,71);
                check(("[[0,"+buttons[i]+",1],[0,"+buttons[i]+",0]]").equals(js(false,"events")), "button mapping " + codes[i]);
            }
            check(activity.gameMode, "gamepad Start reaches game without opening toolbar");
            js(false,"events=[];true"); key(96,true,71); key(96,true,72); key(96,false,71);
            check("[[0,0,1]]".equals(js(false,"events")), "simultaneous sources remain held");
            runOnMainSync(() -> activity.onInputDeviceRemoved(72));
            check("[[0,0,1],[0,0,0]]".equals(js(false,"events")), "disconnect releases game input");
            js(false,"events=[];true"); axis(.9f,0); axis(0,0);
            check("[[0,7,1],[0,7,0]]".equals(js(false,"events")), "left stick and neutral release reach WebView");
            runOnMainSync(() -> { activity.setGameMode(false); activity.player.requestFocus(); });
            js(false,"window.navKeys=[];document.addEventListener('keydown',e=>navKeys.push(e.key));SnesTV.focus();true");
            axis(.9f,0); axis(0,0);
            check("true".equals(js(false,"navKeys.includes('ArrowRight')")), "left stick navigates website outside game mode");
            runOnMainSync(() -> activity.setGameMode(true));
            js(false,"events=[];EJS_emulator.gameManager.simulateInput(0,0,1);true");
            check("[]".equals(js(false,"events")), "duplicate browser gamepad input is gated in native game mode");
            key(21,true,71);
            runOnMainSync(() -> { callActivityOnPause(activity); callActivityOnResume(activity); });
            check("true".equals(js(false,"EJS_emulator.paused && events.some(e=>e[1]===6 && e[2]===0)")), "lifecycle pauses and releases held input");
            runOnMainSync(() -> activity.setGameMode(true));
            key(KeyEvent.KEYCODE_MENU,true,71);key(KeyEvent.KEYCODE_MENU,false,71);
            check(!activity.gameMode, "Menu restores app controls");
            runOnMainSync(() -> activity.onBackPressed());
            check(activity.player == null, "Back returns to game catalog");
            java.util.concurrent.atomic.AtomicBoolean settingsSafe = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> settingsSafe.set(activity.catalog.getSettings().getBlockNetworkLoads() &&
                !activity.catalog.getSettings().getAllowFileAccess()));
            check(settingsSafe.get(), "privileged catalog blocks remote network and file access");
            result.putString("stream", "PASS " + passed + " Android integration checks. Synthetic input fixture only; gameplay not tested.\n");
            result.putInt("passed",passed); finish(ActivityResult.OK,result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL after " + passed + " checks: " + android.util.Log.getStackTraceString(e));
            finish(ActivityResult.FAIL,result);
        }
    }
    private static final class ActivityResult { static final int OK=-1, FAIL=0; }
}
