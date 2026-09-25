package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.*;
import java.net.*;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Tests sender metadata, receiver audio playback and webpage-only notifications.
 * Supply a generated radio.mp3 via -PcjsV5Assets=<fixture directory>.
 */
public final class RemoteMediaRoutingInstrumentation extends Instrumentation {
    private MainActivity activity;
    private static Field field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private Object get(String name) throws Exception { return field(name).get(activity); }
    private void set(String name, Object value) throws Exception { field(name).set(activity, value); }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(activity, args);
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void main(RunnableWithException work) throws Exception {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private interface RunnableWithException { void run() throws Exception; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1; ServerSocket server = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = getContext().getAssets().open("radio.mp3")) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            final byte[] audio = bytes.toByteArray();
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            final ServerSocket serving = server;
            Thread worker = new Thread(() -> {
                while (!serving.isClosed()) {
                    try (Socket socket = serving.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                        String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: "
                                + audio.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                        out.write(audio); out.flush();
                    } catch (IOException ignored) { }
                }
            }, "radio-fixture"); worker.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/radio-without-extension";
            HttpStreamResolver.Result resolved = HttpStreamResolver.resolve(url);
            check(resolved.directMedia, "Extensionless audio/mpeg not detected");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            Channel channel = new Channel("1", "MP3 fixture", "", url, null, null);
            main(() -> {
                call("cancelRemoteResolve", new Class<?>[0]);
                call("releasePlayer", new Class<?>[0]);
                call("closeWebSource", new Class<?>[0]);
                int request = (Integer) get("playRequestId") + 1;
                set("playRequestId", request); set("remoteReceiverRequestId", request);
                call("startResolvedPlayer", new Class<?>[] {Channel.class, String.class, boolean.class}, channel, resolved.url, resolved.directMedia);
                check(get("player") == null, "Sender started a decoder instead of handing off audio");
                JSONObject state = (JSONObject) call("remotePlaybackJson", new Class<?>[] {Channel.class}, channel);
                check(state.getBoolean("available") && "direct".equals(state.getString("sourceMode")), "MP3 was advertised as HLS: " + state);
                check(url.equals(state.getString("sourceUrl")), "MP3 URL lost during handoff");
                for (String address : new String[] {"http://example.test/radio.mp3", "https://example.test/radio.mp3?token=1", "https://example.test/radio.aac"}) {
                    check((Boolean) call("isRemoteDirectSource", new Class<?>[] {String.class}, address), "Known audio routed to HLS: " + address);
                }
                check(!(Boolean) call("isRemoteDirectSource", new Class<?>[] {String.class}, "https://example.test/live.m3u8"), "HLS bypassed its proxy");
                call("clearRemotePlaybackGateway", new Class<?>[0]);
                check(!(Boolean) get("remoteGatewayDirectHttpMedia"), "Stale media type survived a channel switch");
                set("remoteReceiverRequestId", -1);
                call("startResolvedPlayer", new Class<?>[] {Channel.class, String.class, boolean.class}, channel, state.getString("sourceUrl"), true);
            });
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!(Boolean) get("prepared") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            check((Boolean) get("prepared"), "Receiver did not prepare MP3");
            check((Boolean) get("audioOnlyPlayback"), "MP3 not recognized as audio-only");
            IjkMediaPlayer player = (IjkMediaPlayer) get("player");
            long from = player.getCurrentPosition(); SystemClock.sleep(700);
            check(player.getCurrentPosition() > from, "MP3 playback clock did not advance");
            final JSONObject[] mediaState = {null};
            main(() -> mediaState[0] = (JSONObject) call("buildLocalMediaState", new Class<?>[]{boolean.class}, false));
            check(mediaState[0].getBoolean("fileDownloadAvailable"), "Prepared extensionless MP3 did not expose direct download");
            LocalControlServer control = (LocalControlServer) get("controlServer");
            HttpURLConnection download = (HttpURLConnection) new URL("http://127.0.0.1:" + control.getPort()
                    + "/api/media/download?sourceKey=" + URLEncoder.encode(mediaState[0].getString("sourceKey"), "UTF-8")).openConnection();
            download.setReadTimeout(5000);
            ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
            try (InputStream input = download.getInputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) downloaded.write(block, 0, count);
            } finally { download.disconnect(); }
            check(java.util.Arrays.equals(audio, downloaded.toByteArray()), "Player-to-phone download bytes differ");
            main(() -> {
                call("detachPlayerForRemotePlayback", new Class<?>[0]);
                JSONObject state = (JSONObject) call("remotePlaybackJson", new Class<?>[] {Channel.class}, channel);
                check("direct".equals(state.getString("sourceMode")), "Detached audio lost MIME-based routing");
                WebSourceView source = (WebSourceView) get("webSourceView");
                source.open((Integer) get("playRequestId"), "about:blank");
                Field listenerField = WebSourceView.class.getDeclaredField("listener"); listenerField.setAccessible(true);
                WebSourceView.Listener listener = (WebSourceView.Listener) listenerField.get(source);
                call("showLoading", new Class<?>[] {String.class, String.class}, "fixture", "loading");
                listener.onPageStarted((Integer) get("playRequestId"), "about:blank");
                check(((View) get("channelBar")).getVisibility() != View.VISIBLE, "Navigation displayed native loading card");
                listener.onPageReady((Integer) get("playRequestId"), "about:blank", "fixture");
                check(((View) get("channelBar")).getVisibility() != View.VISIBLE, "Ready displayed native channel card");
                set("webViewAutoPlaySniffed", false);
                listener.onStreamDiscovered((Integer) get("playRequestId"), url, "about:blank", "fixture", "");
                check(((View) get("channelBar")).getVisibility() != View.VISIBLE, "Sniffing displayed native channel card");
                listener.onPageError((Integer) get("playRequestId"), "fixture error");
                check(((View) get("channelBar")).getVisibility() == View.VISIBLE, "Navigation error was silently hidden");
                source.closePage();
            });
            result.putString("stream", "PASS extensionless MP3 MIME -> direct handoff -> IJK audio clock; HTTP/HTTPS audio vs HLS; detach/reset; webpage loading/ready/sniff without bottom card; errors remain visible\n");
        } catch (Throwable error) { code = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally { if (server != null) try { server.close(); } catch (IOException ignored) { } }
        finish(code, result);
    }
}
