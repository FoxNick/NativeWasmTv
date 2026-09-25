package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import org.json.JSONObject;

/** Real documents/tabs with deterministic resource events; no external websites required. */
public final class BrowserMediaStateInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebSourceView source;
    private interface Work { void run() throws Exception; }
    private static Object get(Object owner, String key) throws Exception {
        Field field = owner.getClass().getDeclaredField(key); field.setAccessible(true); return field.get(owner);
    }
    private static Object call(Object owner, String key, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(key, types); method.setAccessible(true); return method.invoke(owner, args);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void main(Work work) throws Exception {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private JSONObject state() throws Exception {
        JSONObject[] value = {null};
        main(() -> value[0] = (JSONObject) call(activity, "buildLocalMediaState", new Class<?>[]{boolean.class}, false));
        return value[0];
    }
    private JSONObject awaitPage(String title) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        JSONObject value;
        do {
            value = state();
            if (title.equals(value.optString("name"))) return value;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected page " + title + ": " + value);
    }
    private void observe(String url) throws Exception {
        main(() -> ((WebViewClient) get(source, "sourceClient")).onLoadResource((WebView) get(source, "webView"), url));
        waitForIdleSync();
        check(state().getJSONArray("sniffedResources").length() == 1, "Resource not observed");
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1; ServerSocket server = null;
        try {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            final ServerSocket fixture = server;
            new Thread(() -> {
                while (!fixture.isClosed()) try (Socket socket = fixture.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                    String request = input.readLine(), line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    String title = request.contains("/b") ? "Page B" : request.contains("/c") ? "Page C" : "Page A";
                    byte[] body = ("<!doctype html><title>" + title + "</title><body>" + title + "</body>").getBytes("UTF-8");
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII")); output.write(body); output.flush();
                } catch (Exception ignored) { }
            }, "browser-media-fixture").start();
            final String base = "http://127.0.0.1:" + server.getLocalPort();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Let deferred startup tasks register before isolating this local fixture.
            // Otherwise a native startup URL resolved during the first tab switch
            // can replace the test document without exercising browser navigation.
            SystemClock.sleep(1200);
            waitForIdleSync();
            source = (WebSourceView) get(activity, "webSourceView");
            main(() -> {
                ((java.util.concurrent.atomic.AtomicInteger) get(activity, "catalogLoadGeneration")).incrementAndGet();
                Field generation = MainActivity.class.getDeclaredField("playRequestId");
                generation.setAccessible(true);
                generation.setInt(activity, generation.getInt(activity) + 1);
                call(activity, "cancelCustomSourceTimeout", new Class<?>[0]);
                call(activity, "clearPendingPlayer", new Class<?>[0]);
                call(activity, "cancelRemoteResolve", new Class<?>[0]);
                call(activity, "releasePlayer", new Class<?>[0]);
                call(activity, "closeWebSource", new Class<?>[0]);
                Field auto = MainActivity.class.getDeclaredField("webViewAutoPlaySniffed"); auto.setAccessible(true); auto.setBoolean(activity, false);
                source.open((Integer) get(activity, "playRequestId"), base + "/a");
            });
            JSONObject a = awaitPage("Page A");
            check(a.getBoolean("webPageVisible") && (base + "/a").equals(a.getString("pageUrl")), "Controller still describes entry channel");
            WebTabBar bar = (WebTabBar) get(source, "tabBar");
            WebTabBar.Tab tabA = bar.active();
            WebView viewA = (WebView) get(source, "webView");
            WebViewClient clientA = (WebViewClient) get(source, "sourceClient");
            observe(base + "/shared.mp4");
            String keyA = state().getString("webPageKey");
            main(() -> source.openLinkInNewTab(base + "/b"));
            JSONObject b = awaitPage("Page B");
            check(!keyA.equals(b.getString("webPageKey")) && b.getJSONArray("sniffedResources").length() == 0, "New tab inherited A resources");
            main(() -> clientA.onLoadResource(viewA, base + "/late-a.mp4")); waitForIdleSync();
            check(state().getJSONArray("sniffedResources").length() == 0, "Late background callback contaminated B");
            observe(base + "/shared.mp4");
            boolean rejected = false;
            try { call(activity, "handleWebControl", new Class<?>[]{JSONObject.class}, new JSONObject()
                    .put("action", "playSniffed").put("url", base + "/shared.mp4").put("pageKey", keyA)); }
            catch (InvocationTargetException expected) { rejected = expected.getCause() instanceof org.json.JSONException; }
            check(rejected, "Stale resource button accepted same URL on another tab");
            check(((java.util.Map<?,?>) get(source, "retainedTabWebViews")).containsKey(tabA), "Test needs enough memory for retained WebView");
            main(() -> call(bar, "select", new Class<?>[]{WebTabBar.Tab.class}, tabA));
            JSONObject restored = awaitPage("Page A");
            check(keyA.equals(restored.getString("webPageKey")) && restored.getJSONArray("sniffedResources").length() == 1,
                    "Retained A did not restore its own resource list");
            main(() -> {
                JSONObject receiver = new JSONObject().put("name", "Old receiver channel").put("audioOnly", true).put("fileDownloadAvailable", true);
                call(activity, "applyVisibleWebPageState", new Class<?>[]{JSONObject.class}, receiver);
                check("Page A".equals(receiver.getString("name")) && !receiver.getBoolean("fileDownloadAvailable"), "Receiver overlay not updated");
                source.hideForStreamPlayback();
                JSONObject nativeMedia = new JSONObject().put("name", "Native file");
                call(activity, "applyVisibleWebPageState", new Class<?>[]{JSONObject.class}, nativeMedia);
                check("Native file".equals(nativeMedia.getString("name")), "Hidden page overwrote native media title");
                source.restoreAfterStreamPlayback();
                call(source, "navigateAddress", new Class<?>[]{String.class}, base + "/c");
            });
            JSONObject c = awaitPage("Page C");
            check(!keyA.equals(c.getString("webPageKey")) && c.getJSONArray("sniffedResources").length() == 0, "Navigation retained previous document resources");
            final org.json.JSONArray emptySnapshot = (org.json.JSONArray) call(activity, "sniffedResourcesJson", new Class<?>[0]);
            main(() -> {
                WebView view = (WebView) get(source, "webView");
                WebViewClient client = (WebViewClient) get(source, "sourceClient");
                for (int i = 0; i < 1000; i++) client.onLoadResource(view, base + "/load-" + i + ".mp4");
                check(((java.util.Set<?>) get(source, "discoveredStreamUrls")).size() == 30,
                        "Resource flood was not bounded before entering the main queue");
            });
            waitForIdleSync();
            check(state().getJSONArray("sniffedResources").length() == 30, "Resource admission lost the first 30 entries");
            check(emptySnapshot.length() == 0, "Published JSON snapshot was mutated");
            final long[] cacheTime = {0};
            main(() -> {
                synchronized (get(activity, "sniffedResources")) {
                    Method json = MainActivity.class.getDeclaredMethod("sniffedResourcesJson"); json.setAccessible(true);
                    Object snapshot = json.invoke(activity);
                    long start = System.nanoTime();
                    for (int i = 0; i < 10000; i++) check(json.invoke(activity) == snapshot, "Unchanged resource JSON rebuilt");
                    cacheTime[0] = (System.nanoTime() - start) / 1000000;
                }
                call(activity, "clearSniffedResources", new Class<?>[0]);
                check(((org.json.JSONArray) call(activity, "sniffedResourcesJson", new Class<?>[0])).length() == 0, "Cleared resources left stale snapshot");
            });
            SniffedMediaProbe.Result manifest = new SniffedMediaProbe.Result();
            SniffedMediaProbe.inspectManifest("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720\nmain.m3u8\n", manifest);
            check(manifest.width == 1280 && manifest.height == 720 && manifest.bitrate == 2400000, "Manifest parser regression");
            result.putString("stream", "PASS active page title/URL; new-tab isolation; late callback rejected; same-URL stale action rejected; retained-tab resource restoration; receiver overlay; native playback preserved; document navigation reset; 1000 resource events bounded to 30; immutable JSON snapshot/reset; 10k cached reads=" + cacheTime[0] + "ms; manifest parser\n");
        } catch (Throwable error) { code = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally {
            if (activity != null) try { main(() -> call(activity, "closeWebSource", new Class<?>[0])); } catch (Exception ignored) { }
            if (server != null) try { server.close(); } catch (IOException ignored) { }
        }
        finish(code, result);
    }
}
