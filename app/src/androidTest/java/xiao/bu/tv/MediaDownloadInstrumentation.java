package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** Byte-exact HTTP streaming / ranges / interrupted downloads, using only loopback fixtures. */
public final class MediaDownloadInstrumentation extends Instrumentation {
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
        while ((count = stream.read(block)) != -1) bytes.write(block, 0, count);
        return bytes.toByteArray();
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1; ServerSocket upstream = null; LocalControlServer server = null;
        try {
            final byte[] media = new byte[512 * 1024];
            for (int i = 0; i < media.length; i++) media[i] = (byte) (i * 17);
            upstream = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            final ServerSocket fixture = upstream;
            final List<Map<String,String>> requests = Collections.synchronizedList(new ArrayList<Map<String,String>>());
            new Thread(() -> {
                while (!fixture.isClosed()) try (Socket socket = fixture.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                    String first = input.readLine(), line;
                    Map<String,String> headers = new HashMap<String,String>(); headers.put("request", first);
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':'); if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(Locale.US), line.substring(colon + 1).trim());
                    }
                    requests.add(headers);
                    OutputStream out = socket.getOutputStream();
                    boolean partial = "bytes=100-199".equals(headers.get("range"));
                    boolean broken = first.contains("/broken.mp4");
                    int start = partial ? 100 : 0, length = partial ? 100 : media.length;
                    out.write(((partial ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK")
                            + "\r\nContent-Type: video/mp4\r\nContent-Length: " + length
                            + (partial ? "\r\nContent-Range: bytes 100-199/" + media.length : "")
                            + "\r\nETag: \"fixture\"\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    out.write(media, start, broken ? 4096 : length); out.flush();
                } catch (IOException ignored) { }
            }, "media-download-fixture").start();
            final String base = "http://127.0.0.1:" + upstream.getLocalPort();
            LocalControlServer.Listener listener = (LocalControlServer.Listener) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{LocalControlServer.Listener.class}, (proxy, method, args) -> {
                        if ("mediaDownload".equals(method.getName())) {
                            String key = (String) args[0];
                            if (!"file".equals(key) && !"broken".equals(key)) throw new IOException("stale source");
                            return MediaFileDownload.open(base + ("broken".equals(key) ? "/broken.mp4" : "/movie.mp4?token=signed"),
                                    "Referer: https://player.example/\r\nCookie: fixture=yes\r\n", "文件下载测试", (String) args[1], (String) args[2]);
                        }
                        return null;
                    });
            server = new LocalControlServer(listener); server.start();
            String download = "http://127.0.0.1:" + server.getPort() + "/api/media/download?sourceKey=";
            HttpURLConnection full = (HttpURLConnection) new URL(download + "file").openConnection();
            check(full.getResponseCode() == 200, "Full download status");
            check(full.getHeaderField("Content-Disposition").contains("attachment;")
                    && full.getHeaderField("Content-Disposition").contains("filename*=UTF-8''"), "Missing download/Unicode filename headers");
            check(Arrays.equals(media, read(full.getInputStream())), "Full file bytes differ"); full.disconnect();
            HttpURLConnection part = (HttpURLConnection) new URL(download + "file").openConnection();
            part.setRequestProperty("Range", "bytes=100-199"); part.setRequestProperty("If-Range", "\"fixture\"");
            check(part.getResponseCode() == 206, "Partial status lost");
            check(part.getHeaderField("Content-Range").equals("bytes 100-199/" + media.length), "Partial range lost");
            check(Arrays.equals(Arrays.copyOfRange(media,100,200), read(part.getInputStream())), "Partial bytes differ"); part.disconnect();
            check("fixture=yes".equals(requests.get(0).get("cookie")), "Cookie not passed");
            check("https://player.example/".equals(requests.get(0).get("referer")), "Referer not passed");
            check(requests.get(0).get("request").contains("token=signed"), "Signed query lost");
            check("\"fixture\"".equals(requests.get(1).get("if-range")), "If-Range not passed");
            try (Socket raw = new Socket("127.0.0.1",server.getPort())) {
                raw.setSoTimeout(5000);
                raw.getOutputStream().write("GET /api/media/download?sourceKey=broken HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                String received = new String(read(raw.getInputStream()), "ISO-8859-1");
                check(received.startsWith("HTTP/1.1 200"), "Broken stream lost initial status");
                check(!received.contains("HTTP/1.1 500"), "Error response appended to binary file");
            }
            HttpURLConnection stale = (HttpURLConnection) new URL(download + "stale").openConnection();
            check(stale.getResponseCode() == 500, "Stale source was accepted"); stale.disconnect();
            for (String extension : new String[]{"mp4","mp3","m4a","flac"}) {
                check(MediaFileDownload.isFile(base + "/test." + extension + "?token=1", 12000, false), "File not detected: " + extension);
            }
            check(!MediaFileDownload.isFile(base + "/live.mp3", 0, true), "Infinite radio was treated as a file");
            check(!MediaFileDownload.isFile(base + "/vod.m3u8", 12000, true), "HLS treated as one file");
            result.putString("stream", "PASS full/range downloads byte-exact; signed URLs, Cookie/Referer/If-Range; Unicode attachment; truncated stream closes without error bytes; stale sources rejected; file/live classification\n");
        } catch (Throwable error) { code = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally { if (server != null) server.close(); if (upstream != null) try { upstream.close(); } catch (IOException ignored) { } }
        finish(code, result);
    }
}
