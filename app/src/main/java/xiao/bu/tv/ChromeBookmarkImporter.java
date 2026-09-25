package xiao.bu.tv;

import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts Chrome's exported Netscape bookmark HTML into webview playlist groups. */
final class ChromeBookmarkImporter {
    private static final int MAX_HTML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BOOKMARKS = 5000;
    private static final Pattern TOKEN = Pattern.compile(
            "(?is)<H3\\b[^>]*>(.*?)</H3>|<A\\b([^>]*)>(.*?)</A>|<DL\\b[^>]*>|</DL\\s*>");
    private static final Pattern HREF = Pattern.compile(
            "(?is)\\bHREF\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    static final class Result {
        final byte[] playlist;
        final int count;

        Result(byte[] playlist, int count) {
            this.playlist = playlist;
            this.count = count;
        }
    }

    static Result convert(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法读取书签文件");
        byte[] source = readLimited(input);
        String html = new String(source, Charset.forName("UTF-8"));
        Matcher tokens = TOKEN.matcher(html);
        ArrayList<String> folders = new ArrayList<String>();
        LinkedHashSet<String> urls = new LinkedHashSet<String>();
        String pendingFolder = "";
        StringBuilder playlist = new StringBuilder("#EXTM3U\n");
        int count = 0;
        while (tokens.find() && count < MAX_BOOKMARKS) {
            if (tokens.group(1) != null) {
                pendingFolder = plainText(tokens.group(1));
                continue;
            }
            if (tokens.group(2) != null) {
                String url = href(tokens.group(2));
                if (!isWebUrl(url) || !urls.add(url)) continue;
                String title = plainText(tokens.group(3));
                if (title.length() == 0) title = url;
                String folder = currentFolder(folders);
                String group = folder.length() == 0 || isChromeRoot(folder)
                        ? "Chrome书签" : folder;
                playlist.append("#EXTINF:-1 group-title=\"")
                        .append(attribute(group)).append("\",")
                        .append(line(title)).append('\n')
                        .append("webview://").append(line(url)).append('\n');
                count++;
                continue;
            }
            String token = tokens.group().toLowerCase(Locale.US);
            if (token.startsWith("</dl")) {
                if (!folders.isEmpty()) folders.remove(folders.size() - 1);
            } else {
                folders.add(pendingFolder);
                pendingFolder = "";
            }
        }
        if (count == 0) throw new IOException("文件中没有可导入的 HTTP(S) 书签");
        return new Result(playlist.toString().getBytes("UTF-8"), count);
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_HTML_BYTES) throw new IOException("书签文件超过 8MB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String href(String attributes) {
        Matcher value = HREF.matcher(attributes);
        if (!value.find()) return "";
        String raw = value.group(1) != null ? value.group(1)
                : value.group(2) != null ? value.group(2) : value.group(3);
        return plainText(raw).trim();
    }

    @SuppressWarnings("deprecation")
    private static String plainText(String html) {
        if (html == null) return "";
        return Html.fromHtml(html).toString().replace('\u00a0', ' ').trim();
    }

    private static String currentFolder(ArrayList<String> folders) {
        for (int index = folders.size() - 1; index >= 0; index--) {
            String folder = folders.get(index);
            if (folder != null && folder.trim().length() > 0) return line(folder);
        }
        return "";
    }

    private static boolean isChromeRoot(String folder) {
        String value = folder.toLowerCase(Locale.US);
        return "bookmarks bar".equals(value) || "other bookmarks".equals(value)
                || "mobile bookmarks".equals(value) || "书签栏".equals(folder)
                || "其他书签".equals(folder) || "移动设备书签".equals(folder);
    }

    private static boolean isWebUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String attribute(String value) {
        return line(value).replace('"', '\'');
    }

    private static String line(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private ChromeBookmarkImporter() { }
}
