package com.chessbot.assist;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Lists and installs official Android Stockfish release binaries. */
public final class StockfishCatalog {

    public static final String BUNDLED_ENGINE_ID = "bundled";
    private static final String API_URL =
            "https://api.github.com/repos/official-stockfish/Stockfish/releases?per_page=100";
    private static final String ENGINE_DIRECTORY = "stockfish_engines";
    private static final int MAX_ARCHIVE_BYTES = 250 * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L;

    private StockfishCatalog() {
    }

    public static final class Release {
        public final String tag;
        public final String label;
        public final String assetName;
        public final String downloadUrl;
        public final String sha256;
        public final long size;

        private Release(String tag, String label, String assetName, String downloadUrl,
                        String sha256, long size) {
            this.tag = tag;
            this.label = label;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.sha256 = sha256;
            this.size = size;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class InstalledEngine {
        public final String id;
        public final String label;

        private InstalledEngine(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static List<Release> fetchReleases() throws IOException, JSONException {
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
        boolean arm64 = abi.startsWith("arm64");
        if (!arm64 && !abi.startsWith("armeabi-v7a")) {
            throw new IOException("Stockfish Android không hỗ trợ kiến trúc " + abi);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "ChessBotAssist");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Không lấy được danh sách Stockfish (HTTP " + status + ")");
            }
            JSONArray releases = new JSONArray(readText(connection.getInputStream(), 8 * 1024 * 1024));
            List<Release> result = new ArrayList<>();
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                    continue;
                }
                String tag = release.optString("tag_name", "");
                if (!isSafeTag(tag)) {
                    continue;
                }
                JSONObject asset = findAndroidAsset(release.optJSONArray("assets"), arm64);
                if (asset == null) {
                    continue;
                }
                String assetName = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                String digest = asset.optString("digest", "");
                long size = asset.optLong("size", 0);
                if (!isSupportedArchive(assetName) || !isOfficialDownloadUrl(url, tag, assetName)
                        || !digest.matches("sha256:[0-9a-fA-F]{64}")
                        || size <= 0 || size > MAX_ARCHIVE_BYTES) {
                    continue;
                }
                String label = release.optString("name", "").trim();
                if (label.isEmpty()) {
                    label = displayName(tag);
                }
                result.add(new Release(tag, label, assetName, url,
                        digest.substring("sha256:".length()).toLowerCase(Locale.ROOT), size));
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    public static List<InstalledEngine> getInstalledEngines(Context context) {
        List<InstalledEngine> result = new ArrayList<>();
        result.add(new InstalledEngine(BUNDLED_ENGINE_ID, "Stockfish 16 (có sẵn)"));
        File root = engineRoot(context);
        File[] versions = root.listFiles(File::isDirectory);
        if (versions != null) {
            for (File version : versions) {
                if (!version.getName().endsWith(".installing")
                        && findEngineBinary(version) != null && isSafeTag(version.getName())) {
                    result.add(new InstalledEngine(version.getName(), displayName(version.getName())));
                }
            }
        }
        Collections.sort(result.subList(1, result.size()),
                Comparator.comparing(engine -> engine.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public static String resolveEnginePath(Context context) {
        String id = Prefs.getEngineId(context);
        if (BUNDLED_ENGINE_ID.equals(id)) {
            return context.getApplicationInfo().nativeLibraryDir + "/libstockfish.so";
        }
        if (!isSafeTag(id)) {
            return new File(engineRoot(context), "invalid-selection/stockfish").getAbsolutePath();
        }
        File directory = new File(engineRoot(context), id);
        File binary = findEngineBinary(directory);
        return binary != null ? binary.getAbsolutePath()
                : new File(directory, "stockfish").getAbsolutePath();
    }

    public static boolean isInstalled(Context context, String tag) {
        return isSafeTag(tag) && findEngineBinary(new File(engineRoot(context), tag)) != null;
    }

    /** Downloads, verifies, extracts, and UCI-checks an official release into app-private storage. */
    public static void downloadAndInstall(Context context, Release release) throws IOException {
        if (!isSafeTag(release.tag)
                || !isOfficialDownloadUrl(release.downloadUrl, release.tag, release.assetName)
                || !isSupportedArchive(release.assetName)
                || !release.sha256.matches("[0-9a-f]{64}")
                || release.size <= 0 || release.size > MAX_ARCHIVE_BYTES) {
            throw new IOException("Thông tin bản Stockfish không hợp lệ");
        }
        File root = engineRoot(context);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Không tạo được thư mục lưu Stockfish");
        }
        File target = new File(root, release.tag);
        if (findEngineBinary(target) != null) {
            return;
        }

        File archive = new File(context.getCacheDir(), release.tag + ".download");
        File staging = new File(root, release.tag + ".installing");
        deleteTree(archive);
        deleteTree(staging);
        try {
            downloadArchive(release, archive);
            if (!staging.mkdirs()) {
                throw new IOException("Không tạo được thư mục cài Stockfish");
            }
            extractArchive(archive, staging, release.assetName.endsWith(".gz"));
            File binary = findEngineBinary(staging);
            if (binary == null || binary.length() < 1024 * 1024) {
                throw new IOException("Gói tải về không có bộ máy Stockfish hợp lệ");
            }

            StockfishEngine testEngine = new StockfishEngine(binary.getAbsolutePath());
            try {
                testEngine.start();
            } finally {
                testEngine.quit();
            }

            if (target.exists()) {
                deleteTree(target);
            }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveError) {
                if (!staging.renameTo(target)) {
                    throw new IOException("Không thể hoàn tất cài đặt Stockfish", atomicMoveError);
                }
            }
        } finally {
            deleteTree(archive);
            deleteTree(staging);
        }
        if (findEngineBinary(target) == null) {
            throw new IOException("Không tìm thấy bộ máy sau khi cài đặt");
        }
    }

    private static JSONObject findAndroidAsset(JSONArray assets, boolean arm64)
            throws JSONException {
        if (assets == null) {
            return null;
        }
        String[] preferred = arm64
                ? new String[]{"stockfish-android-arm64-universal.tar.gz",
                    "stockfish-android-armv8.tar"}
                : new String[]{"stockfish-android-armv7-neon.tar.gz",
                    "stockfish-android-armv7-neon.tar", "stockfish-android-armv7.tar"};
        for (String name : preferred) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (name.equals(asset.optString("name"))) {
                    return asset;
                }
            }
        }
        return null;
    }

    private static void downloadArchive(Release release, File destination) throws IOException {
        URL url = new URL(release.downloadUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", "ChessBotAssist");
        MessageDigest digest = sha256();
        try {
            int status = connection.getResponseCode();
            String finalHost = connection.getURL().getHost();
            if (status != HttpURLConnection.HTTP_OK
                    || !("github.com".equalsIgnoreCase(finalHost)
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(finalHost)
                    || "objects.githubusercontent.com".equalsIgnoreCase(finalHost))) {
                throw new IOException("Không tải được Stockfish (HTTP " + status + ")");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[32768];
                int count;
                long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > release.size || total > MAX_ARCHIVE_BYTES) {
                        throw new IOException("Gói tải về lớn hơn thông tin từ Stockfish");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                if (total != release.size) {
                    throw new IOException("Gói tải về chưa đầy đủ");
                }
            }
            if (!toHex(digest.digest()).equals(release.sha256)) {
                throw new IOException("SHA-256 không khớp; đã hủy cài đặt Stockfish");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void extractArchive(File archive, File destination, boolean gzip)
            throws IOException {
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             InputStream input = gzip ? new GZIPInputStream(file) : file) {
            byte[] header = new byte[512];
            String longName = null;
            long extractedBytes = 0;
            int entries = 0;
            while (readBlock(input, header)) {
                if (isZeroBlock(header)) {
                    break;
                }
                if (++entries > 1000) {
                    throw new IOException("Gói Stockfish có quá nhiều tệp");
                }
                String name = longName != null ? longName : tarName(header);
                longName = null;
                long size = tarOctal(header, 124, 12);
                long mode = tarOctal(header, 100, 8);
                int type = header[156] & 0xff;
                if (size < 0 || size > MAX_EXTRACTED_BYTES
                        || extractedBytes + size > MAX_EXTRACTED_BYTES) {
                    throw new IOException("Gói Stockfish vượt quá dung lượng cho phép");
                }

                if (type == 'L') {
                    if (size > 4096) {
                        throw new IOException("Tên tệp trong gói Stockfish quá dài");
                    }
                    byte[] longNameBytes = readPayload(input, size);
                    longName = new String(longNameBytes, StandardCharsets.UTF_8)
                            .replace("\0", "").trim();
                    skipPadding(input, size);
                    continue;
                }

                File output = safeTarFile(destination, name);
                if (type == '5') {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Không tạo được thư mục trong gói Stockfish");
                    }
                    skipPayload(input, size);
                } else if (type == 0 || type == '0') {
                    File parent = output.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Không tạo được thư mục trong gói Stockfish");
                    }
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        copyPayload(input, stream, size);
                    }
                    if ((mode & 0111) != 0 || isEngineName(output.getName())) {
                        output.setExecutable(true, true);
                    }
                    extractedBytes += size;
                } else {
                    skipPayload(input, size);
                }
                skipPadding(input, size);
            }
        }
    }

    private static File safeTarFile(File root, String entryName) throws IOException {
        String name = entryName;
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Đường dẫn không hợp lệ trong gói Stockfish");
        }
        for (String component : name.split("/")) {
            if ("..".equals(component)) {
                throw new IOException("Đường dẫn không hợp lệ trong gói Stockfish");
            }
        }
        File output = new File(root, name);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!output.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("Đường dẫn thoát khỏi thư mục cài Stockfish");
        }
        return output;
    }

    private static String tarName(byte[] header) {
        String name = tarString(header, 0, 100);
        String prefix = tarString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String tarString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long tarOctal(byte[] bytes, int offset, int length) throws IOException {
        int end = offset + length;
        int index = offset;
        while (index < end && (bytes[index] == 0 || bytes[index] == ' ')) {
            index++;
        }
        long value = 0;
        boolean found = false;
        while (index < end && bytes[index] >= '0' && bytes[index] <= '7') {
            found = true;
            value = (value << 3) + bytes[index++] - '0';
            if (value > MAX_EXTRACTED_BYTES) {
                throw new IOException("Kích thước tệp trong gói Stockfish không hợp lệ");
            }
        }
        return found ? value : 0;
    }

    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int count = input.read(block, offset, block.length - offset);
            if (count < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new IOException("Gói Stockfish bị cắt giữa chừng");
            }
            offset += count;
        }
        return true;
    }

    private static byte[] readPayload(InputStream input, long size) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
        copyPayload(input, output, size);
        return output.toByteArray();
    }

    private static void copyPayload(InputStream input, java.io.OutputStream output, long size)
            throws IOException {
        byte[] buffer = new byte[32768];
        long remaining = size;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("Gói Stockfish bị cắt giữa chừng");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void skipPayload(InputStream input, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("Gói Stockfish bị cắt giữa chừng");
            }
            remaining -= count;
        }
    }

    private static void skipPadding(InputStream input, long size) throws IOException {
        long padding = (512 - size % 512) % 512;
        skipPayload(input, padding);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static File findEngineBinary(File directory) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                File nested = findEngineBinary(entry);
                if (nested != null) {
                    return nested;
                }
            } else if (isEngineName(entry.getName()) && entry.canExecute()) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isEngineName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("stockfish") || (lower.startsWith("stockfish-")
                && !lower.endsWith(".nnue") && !lower.endsWith(".txt"));
    }

    private static boolean isSupportedArchive(String name) {
        return name.equals("stockfish-android-arm64-universal.tar.gz")
                || name.equals("stockfish-android-armv8.tar")
                || name.equals("stockfish-android-armv7-neon.tar.gz")
                || name.equals("stockfish-android-armv7-neon.tar")
                || name.equals("stockfish-android-armv7.tar");
    }

    private static boolean isOfficialDownloadUrl(String value, String tag, String assetName) {
        try {
            URL url = new URL(value);
            String expectedPath = tag == null || assetName == null
                    ? null : "/official-stockfish/Stockfish/releases/download/"
                    + tag + "/" + assetName;
            return "https".equals(url.getProtocol())
                    && "github.com".equalsIgnoreCase(url.getHost())
                    && (expectedPath == null
                    ? url.getPath().startsWith(
                    "/official-stockfish/Stockfish/releases/download/")
                    : expectedPath.equals(url.getPath()));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSafeTag(String tag) {
        return tag != null && !tag.equals(".") && !tag.equals("..")
                && tag.matches("[A-Za-z0-9._-]{1,100}");
    }

    private static String displayName(String tag) {
        if (tag.startsWith("sf_")) {
            return "Stockfish " + tag.substring(3);
        }
        if (tag.startsWith("stockfish-dev-")) {
            return "Stockfish Dev " + tag.substring("stockfish-dev-".length());
        }
        return "Stockfish " + tag;
    }

    private static File engineRoot(Context context) {
        return new File(context.getFilesDir(), ENGINE_DIRECTORY);
    }

    private static String readText(InputStream input, int maxBytes) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException("Danh sách Stockfish quá lớn");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Thiết bị không hỗ trợ SHA-256", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (file.exists()) {
            file.delete();
        }
    }
}
