package xiao.bu.tv;

import org.json.JSONObject;
import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Latest absolute cursor state, separate from the encoded video and its queues. */
final class CastCursorChannel {
    interface Listener { void receive(JSONObject state) throws Exception; }

    static final class Receiver implements Closeable {
        private final DatagramSocket socket;
        private volatile boolean closed;
        Receiver(final InetAddress peer, final String session, final Listener listener) throws Exception {
            socket = new DatagramSocket(0);
            Thread worker = new Thread(() -> {
                byte[] bytes = new byte[1536];
                long latest = -1;
                while (!closed) {
                    try {
                        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                        socket.receive(packet);
                        if (!peer.equals(packet.getAddress())) continue;
                        JSONObject state = new JSONObject(new String(bytes, 0, packet.getLength(), "UTF-8"));
                        if (!session.equals(state.optString("sessionId"))) continue;
                        long sequence = state.optLong("sequence", -1);
                        if (sequence <= latest) continue;
                        listener.receive(state);
                        latest = sequence;
                        byte[] ack = Long.toString(sequence).getBytes("UTF-8");
                        socket.send(new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort()));
                    } catch (Exception ignored) { /* malformed/stale packets never affect the lease */ }
                }
            }, "cast-cursor-receive");
            worker.setDaemon(true);
            worker.start();
        }
        int port() { return socket.getLocalPort(); }
        public void close() { closed = true; socket.close(); }
    }

}
