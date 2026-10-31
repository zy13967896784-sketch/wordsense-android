package com.wordsense.app;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简本地静态服务器：从 APK 的 assets/www 提供页面。
 * 用 localhost 的 http 源，保证 localStorage 与麦克风权限在 WebView 中都可用。
 */
public class AssetServer {
    private final Context context;
    private final int port;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public AssetServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public void start() throws Exception {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "wordsense-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                if (head.size() >= 4) {
                    byte[] data = head.toByteArray();
                    int n = data.length;
                    if (data[n - 4] == '\r' && data[n - 3] == '\n' && data[n - 2] == '\r' && data[n - 1] == '\n') break;
                }
                if (head.size() > 16384) break;
            }
            String request = new String(head.toByteArray(), "US-ASCII");
            String first = request.split("\r\n")[0].trim();
            String[] parts = first.split(" ");
            if (parts.length < 2) {
                respond(socket, "400 Bad Request", null, null);
                return;
            }
            String method = parts[0];
            String target = parts[1];
            if ("/__tts".equals(target.split("\\?")[0])) {
                handleTts(socket, method, target);
                return;
            }
            int q = target.indexOf('?');
            if (q >= 0) target = target.substring(0, q);

            if ("/__ping".equals(target)) {
                respondPing(socket);
                return;
            }

            String path;
            try {
                path = URLDecoder.decode(target, "UTF-8");
            } catch (Exception e) {
                path = target;
            }
            if (path.indexOf("..") >= 0) {
                respond(socket, "404 Not Found", null, null);
                return;
            }
            if (path.startsWith("/")) path = path.substring(1);
            if (path.length() == 0) path = "index.html";
            if (path.endsWith("/")) path = path + "index.html";

            String assetPath = "www/" + path;
            byte[] body;
            try {
                AssetManager am = context.getAssets();
                InputStream is = am.open(assetPath);
                body = readAll(is);
                is.close();
            } catch (Exception e) {
                respond(socket, "404 Not Found", null, null);
                return;
            }

            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType(path) + "\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Cache-Control: max-age=600\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes("US-ASCII"));
            if (!"HEAD".equals(method)) out.write(body);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private void handleTts(Socket socket, String method, String target) {
        try {
            String query = target.indexOf('?') >= 0 ? target.substring(target.indexOf('?') + 1) : "";
            String kind = "";
            String text = "";
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq < 0) continue;
                String k = kv.substring(0, eq);
                String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                if ("kind".equals(k)) kind = v;
                else if ("text".equals(k)) text = v;
            }
            text = text.trim();
            if (text.length() == 0 || text.length() > 500) {
                respond(socket, "400 Bad Request", null, null);
                return;
            }
            String url;
            String referer;
            if ("youdao".equalsIgnoreCase(kind) || "youdao_uk".equalsIgnoreCase(kind)) {
                String type = "youdao_uk".equalsIgnoreCase(kind) ? "1" : "2";
                url = "https://dict.youdao.com/dictvoice?audio=" + URLEncoder.encode(text, "UTF-8") + "&type=" + type;
                referer = "https://dict.youdao.com/";
            } else {
                String lan = "baidu_uk".equalsIgnoreCase(kind) ? "uk" : "en";
                url = "https://fanyi.baidu.com/gettts?lan=" + lan + "&text=" + URLEncoder.encode(text, "UTF-8") + "&spd=3&source=web";
                referer = "https://fanyi.baidu.com/";
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            byte[] body = is == null ? new byte[0] : readAll(is);
            if (is != null) is.close();
            if (code < 200 || code >= 300) {
                respond(socket, "502 Bad Gateway", null, null);
                return;
            }
            String type = conn.getContentType();
            if (type == null || type.length() == 0) type = "audio/mpeg";
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + type + "\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: public, max-age=86400\r\n" +
                    "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes("US-ASCII"));
            if (!"HEAD".equals(method)) out.write(body);
            out.flush();
        } catch (Exception e) {
            try { respond(socket, "502 Bad Gateway", null, null); } catch (Exception ignored) { }
        }
    }

    private void respondPing(Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 204 No Content\r\n" +
                    "X-WordSense: 1\r\n" +
                    "Connection: close\r\n\r\n").getBytes("US-ASCII"));
            out.flush();
        } catch (Exception ignored) { }
    }

    private void respond(Socket socket, String status, String body, String type) {
        try {
            byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
            String headers = "HTTP/1.1 " + status + "\r\n" +
                    "Content-Type: " + (type == null ? "text/plain; charset=utf-8" : type) + "\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes("US-ASCII"));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) { }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = is.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }
}
