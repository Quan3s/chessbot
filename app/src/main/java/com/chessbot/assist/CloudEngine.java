package com.chessbot.assist;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Client for the self-hosted ChessBot Python API. */
public final class CloudEngine implements ChessEngine {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final String baseUrl;
    private final String apiKey;
    private final String engineType;
    private final int elo;
    private volatile String name;

    public CloudEngine(String baseUrl, String apiKey, int engineType, int elo) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.engineType = engineType == Prefs.BOT_MAIA_CLOUD ? "maia" : "stockfish";
        this.elo = elo;
        this.name = engineType == Prefs.BOT_MAIA_CLOUD
                ? "Maia-2 Cloud · " + elo + " Elo" : "Stockfish Cloud";
    }

    @Override
    public void start() throws IOException {
        JSONObject health = request("GET", "/health", null, 7000);
        String actualEngine = health.optString("engine", "");
        if (!engineType.equals(actualEngine)) {
            throw new IOException("Server đang chạy " + actualEngine
                    + " nhưng app đang chọn " + engineType
                    + "; hãy chọn cùng bot ở máy chủ rồi thử lại.");
        }
        name = health.optString("name", name);
        if ("maia".equals(engineType)) {
            name += " · " + elo + " Elo";
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void configure(int skill, int threads, int hashMb) {
        // Server-side engine settings are selected when the Python host starts.
    }

    @Override
    public String bestMove(String fen, int movetimeMs) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("engine", engineType);
            body.put("fen", fen);
            body.put("movetime_ms", Math.max(100, Math.min(60000, movetimeMs)));
            if ("maia".equals(engineType)) {
                body.put("elo", elo);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Không tạo được yêu cầu tới server", e);
        }
        JSONObject response = request("POST", "/bestmove", body.toString(),
                Math.min(30000, Math.max(12000, movetimeMs + 10000)));
        if (!engineType.equals(response.optString("engine", ""))) {
            throw new IOException("Server trả lời bằng engine không đúng lựa chọn.");
        }
        String move = response.optString("move", "");
        if (move.isEmpty()) {
            throw new IOException("Server không trả về nước đi hợp lệ.");
        }
        return move;
    }

    @Override
    public void quit() {
    }

    private JSONObject request(String method, String path, String body, int timeoutMs)
            throws IOException {
        URI base = parseBaseUrl(baseUrl);
        String basePath = base.getRawPath();
        if (basePath == null || "/".equals(basePath)) {
            basePath = "";
        } else if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String address = base.getScheme() + "://" + base.getRawAuthority()
                + basePath + path;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(address).openConnection();
            boolean retry = false;
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "ChessBotAssist");
                if (!apiKey.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                if (body != null) {
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setRequestProperty(
                            "Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(data);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String text = stream == null ? "" : readLimited(stream);
                if (status < 200 || status >= 300) {
                    if (attempt == 0 && isTransientStatus(status)) {
                        retry = true;
                    } else {
                        String detail;
                        try {
                            detail = new JSONObject(text).optString("error", "");
                        } catch (org.json.JSONException ignored) {
                            detail = "";
                        }
                        throw new IOException("Server phản hồi HTTP " + status
                                + (detail.isEmpty() ? "" : ": " + detail));
                    }
                } else {
                    try {
                        return new JSONObject(text);
                    } catch (org.json.JSONException e) {
                        throw new IOException("Phản hồi server không phải JSON hợp lệ.", e);
                    }
                }
            } finally {
                connection.disconnect();
            }
            if (retry) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Đã dừng khi thử kết nối lại server.", e);
                }
            }
        }
        throw new IOException("Server tạm thời không phản hồi.");
    }

    private static boolean isTransientStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static URI parseBaseUrl(String text) throws IOException {
        try {
            URI uri = URI.create(text);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                throw new IOException("URL server phải bắt đầu bằng https:// hoặc http://.");
            }
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IOException("URL server không hợp lệ.");
            }
            if ("http".equalsIgnoreCase(scheme) && !isPrivateHost(uri.getHost())) {
                throw new IOException("HTTP chỉ được phép trên mạng LAN; dùng HTTPS trên Internet.");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("URL server không hợp lệ.", e);
        }
    }

    private static boolean isPrivateHost(String input) {
        if ("localhost".equalsIgnoreCase(input)) {
            return true;
        }
        String host = input;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                return address instanceof Inet6Address
                        && (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress());
            } catch (IOException e) {
                return false;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    return false;
                }
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return octets[0] == 10 || octets[0] == 127
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254);
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IOException("Phản hồi server quá lớn.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
