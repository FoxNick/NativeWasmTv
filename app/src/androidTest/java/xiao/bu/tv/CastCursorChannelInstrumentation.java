package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Wire-level coverage on legacy Android; no Activity, catalog or settings changes. */
public final class CastCursorChannelInstrumentation extends Instrumentation {
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1;
        CastCursorChannel.Receiver receiver = null;
        try {
            InetAddress peer = InetAddress.getByName("127.0.0.1");
            AtomicInteger latest = new AtomicInteger(-1);
            receiver = new CastCursorChannel.Receiver(peer, "test-session", state -> latest.set(state.getInt("x")));
            // Out-of-order and wrong-session datagrams must not replace the latest position.
            try (DatagramSocket raw = new DatagramSocket()) {
                raw.setSoTimeout(300);
                for (int i = 0; i < 3; i++) {
                    byte[] data = new JSONObject().put("sessionId", i == 2 ? "old-session" : "test-session")
                            .put("sequence", i == 0 ? 10 : i == 1 ? 9 : 11).put("x", i).toString().getBytes("UTF-8");
                    raw.send(new DatagramPacket(data, data.length, peer, receiver.port()));
                    if (i == 0) raw.receive(new DatagramPacket(new byte[32], 32));
                }
                SystemClock.sleep(100);
                check(latest.get() == 0, "Stale packet or old session moved cursor");
            }
            receiver.close();
            receiver = new CastCursorChannel.Receiver(peer, "fresh-session", state -> latest.set(state.getInt("x")));
            try (DatagramSocket raw = new DatagramSocket()) {
                raw.setSoTimeout(2000);
                byte[] data = new JSONObject().put("sessionId", "fresh-session")
                        .put("sequence", 1).put("x", 99).toString().getBytes("UTF-8");
                raw.send(new DatagramPacket(data, data.length, peer, receiver.port()));
                DatagramPacket ack = new DatagramPacket(new byte[32], 32);
                raw.receive(ack);
                check("1".equals(new String(ack.getData(), 0, ack.getLength(), "UTF-8")), "ACK sequence mismatch");
                check(latest.get() == 99, "Fresh receiver did not reset sequence");
            }
            checkSessionProtocol();
            checkRendering();
            result.putString("stream", "PASS stale/session rejection, fresh-session ACK, TCP handshake/heartbeat, UDP cursor, return-path input, pointer rendering and expiry\n");
        } catch (Throwable error) {
            code = 0; result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (receiver != null) receiver.close();
        }
        finish(code, result);
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void checkSessionProtocol() throws Exception {
        AtomicInteger opened = new AtomicInteger(), messages = new AtomicInteger();
        LocalControlServer.Listener listener = (LocalControlServer.Listener) java.lang.reflect.Proxy.newProxyInstance(
                LocalControlServer.Listener.class.getClassLoader(), new Class<?>[]{LocalControlServer.Listener.class},
                (proxy, method, args) -> {
                    if ("takeoverSessionOpened".equals(method.getName())) opened.incrementAndGet();
                    if ("takeoverSessionMessage".equals(method.getName())) messages.incrementAndGet();
                    return method.getReturnType() == String.class ? "{\"ok\":true}" : null;
                });
        LocalControlServer server = new LocalControlServer(listener);
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.getPort()); DatagramSocket udp = new DatagramSocket()) {
                socket.setSoTimeout(2000); udp.setSoTimeout(2000);
                java.io.BufferedWriter out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));
                out.write("NTV-TAKEOVER/1\r\n{\"sessionId\":\"protocol-fixture\"}\r\n"); out.flush();
                JSONObject hello = new JSONObject(in.readLine());
                check(hello.getBoolean("ok") && hello.getInt("protocol") == 1 && opened.get() == 1, "Handshake incompatible");
                int port = hello.getInt("cursorPort"); check(port > 0, "No independent pointer port");
                byte[] packet = new JSONObject().put("sessionId", "protocol-fixture").put("type", "cursor")
                        .put("sequence", 1).put("x", .5).put("y", .5).toString().getBytes("UTF-8");
                udp.send(new DatagramPacket(packet, packet.length, InetAddress.getByName("127.0.0.1"), port));
                udp.receive(new DatagramPacket(new byte[32], 32));
                out.write("{\"sessionId\":\"protocol-fixture\",\"type\":\"heartbeat\"}\r\n"); out.flush();
                check(new JSONObject(in.readLine()).getBoolean("ok") && messages.get() == 2, "Heartbeat or cursor not dispatched");
                check(server.sendTakeoverSessionMessage(new JSONObject().put("type", "pointer").put("key", "back")), "Input return path unavailable");
                JSONObject key = new JSONObject(in.readLine());
                check("back".equals(key.getString("key")) && "protocol-fixture".equals(key.getString("sessionId")), "Wrong return-path session");
                server.closeTakeoverSession("protocol-fixture");
                check(in.readLine() == null, "Session close did not close stream");
            }
        } finally { server.close(); }
    }

    private void checkRendering() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(
                getTargetContext(), MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        final Throwable[] failed = {null};
        try {
            runOnMainSync(() -> {
                try {
                    android.view.View root = (android.view.View) field("root").get(activity);
                    android.view.View video = (android.view.View) field("videoView").get(activity);
                    FlyMouseCursorView cursor = (FlyMouseCursorView) field("flyMouseCursor").get(activity);
                    root.layout(0, 0, 1280, 720);
                    video.layout(0, 0, 1280, 720);
                    cursor.layout(0, 0, 1280, 720);
                    field("remoteCatalogUrl").set(activity, "http://127.0.0.1:19966");
                    field("remoteTakeoverSessionId").set(activity, "render-fixture");
                    field("receiverStreamSessionId").set(activity, "stream-1");
                    field("prepared").setBoolean(activity, true);
                    java.lang.reflect.Method selection = MainActivity.class.getDeclaredMethod("currentReceiverSelection");
                    selection.setAccessible(true);
                    check(selection.invoke(activity) instanceof JSONObject, "Missing route-handover selection");
                    JSONObject packet = new JSONObject().put("type", "cursor")
                            .put("sessionId", "render-fixture").put("stream", "stream-1")
                            .put("visible", true).put("x", .25).put("y", .75)
                            .put("unit", .001).put("click", 1);
                    field("pendingReceiverCursor").set(activity, packet);
                    ((Runnable) field("applyReceiverCursor").get(activity)).run();
                    check(field("receiverCursorActive").getBoolean(activity), "Receiver cursor is hidden");
                    check(Math.abs(cursor.cursorX() - .25f * 1279) < 2, "Wrong normalized X");
                    check(Math.abs(cursor.cursorY() - .75f * 719) < 2, "Wrong normalized Y");
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(1280, 720,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    cursor.draw(new android.graphics.Canvas(bitmap));
                    boolean painted = false;
                    for (int y = 520; y < 610 && !painted; y++)
                        for (int x = 300; x < 390; x++)
                            if ((bitmap.getPixel(x, y) >>> 24) != 0) { painted = true; break; }
                    bitmap.recycle();
                    check(painted, "Cursor view produced no visible pixels");
                    packet.put("stream", "old-stream");
                    field("pendingReceiverCursor").set(activity, packet);
                    ((Runnable) field("applyReceiverCursor").get(activity)).run();
                    check(!field("receiverCursorActive").getBoolean(activity), "Old stream cursor remained visible");
                    packet.put("stream", "stream-1");
                    field("pendingReceiverCursor").set(activity, packet);
                    ((Runnable) field("applyReceiverCursor").get(activity)).run();
                    field("receiverCursorAt").setLong(activity, SystemClock.elapsedRealtime() - 1000);
                    ((Runnable) field("expireReceiverCursor").get(activity)).run();
                    check(!field("receiverCursorActive").getBoolean(activity), "Expired cursor remained visible");
                    field("remoteCatalogUrl").set(activity, "");
                    field("remoteTakeoverSessionId").set(activity, "");
                    field("prepared").setBoolean(activity, false);
                } catch (Throwable error) { failed[0] = error; }
            });
            if (failed[0] != null) throw new AssertionError(failed[0]);
        } finally { runOnMainSync(activity::finish); }
    }
}
