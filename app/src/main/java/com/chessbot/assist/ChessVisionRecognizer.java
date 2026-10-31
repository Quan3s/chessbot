package com.chessbot.assist;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Offline OpenCV Chess Bot ONNX classifier hosted in a transparent WebView. */
public final class ChessVisionRecognizer {

    private static final String HOST = "chessbot.local";
    private static final String ORIGIN = "https://" + HOST;
    private static final long PAGE_TIMEOUT_SECONDS = 15;
    private static final long SCAN_TIMEOUT_SECONDS = 12;

    public static final class Result {
        public final String placement;
        public final String placementOtherOrientation;
        public final boolean whiteAtBottom;
        public final boolean reliable;
        public final boolean plausible;
        public final float minConfidence;
        public final float meanConfidence;

        private Result(String placement, String placementOtherOrientation,
                       boolean whiteAtBottom, boolean reliable, boolean plausible,
                       float minConfidence, float meanConfidence) {
            this.placement = placement;
            this.placementOtherOrientation = placementOtherOrientation;
            this.whiteAtBottom = whiteAtBottom;
            this.reliable = reliable;
            this.plausible = plausible;
            this.minConfidence = minConfidence;
            this.meanConfidence = meanConfidence;
        }

        /** Occupancy in chesslib square order: a1..h8. */
        public String placementFor(boolean whiteBottom) {
            return whiteBottom == whiteAtBottom ? placement : placementOtherOrientation;
        }

        public int[] boardOccupancy(boolean whiteBottom) {
            int[] occupancy = new int[64];
            String[] ranks = placementFor(whiteBottom).split("/");
            if (ranks.length != 8) {
                throw new IllegalStateException("Classifier returned invalid placement");
            }
            for (int row = 0; row < 8; row++) {
                int file = 0;
                for (int i = 0; i < ranks[row].length(); i++) {
                    char c = ranks[row].charAt(i);
                    if (c >= '1' && c <= '8') {
                        file += c - '0';
                    } else if ("KQRBNPkqrbnp".indexOf(c) >= 0) {
                        if (file >= 8) {
                            throw new IllegalStateException("Classifier returned invalid rank");
                        }
                        int rank = 7 - row;
                        occupancy[rank * 8 + file] = 1;
                        file++;
                    } else {
                        throw new IllegalStateException("Classifier returned invalid piece");
                    }
                }
                if (file != 8) {
                    throw new IllegalStateException("Classifier returned incomplete rank");
                }
            }
            return occupancy;
        }
    }

    private static final class PendingScan {
        final String id;
        final CountDownLatch done = new CountDownLatch(1);
        Result result;
        String error;

        PendingScan(String id) {
            this.id = id;
        }
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CountDownLatch pageLoaded = new CountDownLatch(1);
    private final AtomicLong nextId = new AtomicLong();
    private final File frameFile;
    private final Object pendingLock = new Object();

    private WebView webView;
    private PendingScan pending;
    private volatile boolean closed;

    public ChessVisionRecognizer(Context context, WindowManager windowManager) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Chess WebView must be created on the main thread");
        }
        this.context = context.getApplicationContext();
        this.windowManager = windowManager;
        this.frameFile = new File(this.context.getCacheDir(), "chess-recognition-frame.jpg");
        createWebView(context);
    }

    private void createWebView(Context viewContext) {
        webView = new WebView(viewContext);
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        webView.addJavascriptInterface(this, "ChessBotBridge");
        webView.setWebViewClient(new AssetClient());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        windowManager.addView(webView, params);
        webView.loadUrl(ORIGIN + "/chess-recognition/index.html");
    }

    public Result recognize(ScreenCapture.Frame frame, float[] boardRect) throws Exception {
        if (closed) {
            throw new IllegalStateException("Chess recognizer is closed");
        }
        if (!pageLoaded.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("Chess recognizer page did not load");
        }
        writeBoardImage(frame, boardRect);

        String id = Long.toString(nextId.incrementAndGet());
        PendingScan current = new PendingScan(id);
        synchronized (pendingLock) {
            if (pending != null) {
                throw new IllegalStateException("Chess recognition already in progress");
            }
            pending = current;
        }
        mainHandler.post(() -> {
            if (webView == null || closed) {
                completeWithError(id, "Chess recognizer WebView is unavailable");
                return;
            }
            webView.evaluateJavascript(
                    "(function(){if(typeof window.recognizeFrame!=='function'){"
                            + "ChessBotBridge.onResult('" + id
                            + "',JSON.stringify({error:'recognizer unavailable'}));return;}"
                            + "window.recognizeFrame('" + id + "');})()",
                    null);
        });

        if (!current.done.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            synchronized (pendingLock) {
                if (pending == current) {
                    pending = null;
                }
            }
            throw new IOException("Chess recognition timed out");
        }
        if (current.error != null) {
            throw new IOException(current.error);
        }
        return current.result;
    }

    private void writeBoardImage(ScreenCapture.Frame frame, float[] rect) throws IOException {
        int left = clamp(Math.round(rect[0] * frame.width), 0, frame.width - 1);
        int top = clamp(Math.round(rect[1] * frame.height), 0, frame.height - 1);
        int right = clamp(Math.round(rect[2] * frame.width), left + 8, frame.width);
        int bottom = clamp(Math.round(rect[3] * frame.height), top + 8, frame.height);
        Bitmap bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height,
                Bitmap.Config.ARGB_8888);
        Bitmap crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
        bitmap.recycle();
        try (FileOutputStream stream = new FileOutputStream(frameFile, false)) {
            if (!crop.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                throw new IOException("Could not encode board screenshot");
            }
        } finally {
            crop.recycle();
        }
    }

    @JavascriptInterface
    public void onResult(String requestId, String json) {
        try {
            JSONObject value = json == null || "null".equals(json.trim())
                    ? null : new JSONObject(json);
            if (value != null && value.has("error")) {
                completeWithError(requestId, value.getString("error"));
            } else if (value == null || value.length() == 0) {
                complete(requestId, null, null);
            } else {
                Result result = new Result(value.getString("placement"),
                        value.getString("placementOtherOrientation"),
                        value.getBoolean("whiteAtBottom"),
                        value.getBoolean("reliable"),
                        value.getBoolean("plausible"),
                        (float) value.getDouble("minConfidence"),
                        (float) value.getDouble("meanConfidence"));
                complete(requestId, result, null);
            }
        } catch (Exception e) {
            completeWithError(requestId, "Invalid chess recognition result: " + e.getMessage());
        }
    }

    private void completeWithError(String id, String error) {
        complete(id, null, error);
    }

    private void complete(String id, Result result, String error) {
        synchronized (pendingLock) {
            if (pending == null || !pending.id.equals(id)) {
                return;
            }
            pending.result = result;
            pending.error = error;
            pending.done.countDown();
            pending = null;
        }
    }

    public void close() {
        closed = true;
        synchronized (pendingLock) {
            if (pending != null) {
                pending.error = "Chess recognizer closed";
                pending.done.countDown();
                pending = null;
            }
        }
        pageLoaded.countDown();
        Runnable destroyWebView = () -> {
            WebView view = webView;
            webView = null;
            if (view == null) {
                return;
            }
            try {
                windowManager.removeView(view);
            } catch (IllegalArgumentException ignored) {
            }
            view.stopLoading();
            view.removeJavascriptInterface("ChessBotBridge");
            view.destroy();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebView.run();
        } else {
            mainHandler.post(destroyWebView);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!HOST.equals(uri.getHost())) {
                return emptyResponse(403, "Blocked");
            }
            String path = uri.getPath();
            try {
                if ("/frame.jpg".equals(path)) {
                    return response("image/jpeg", new FileInputStream(frameFile));
                }
                String assetPath;
                if (path.startsWith("/chess-recognition/")) {
                    assetPath = path.substring(1);
                } else if (path.startsWith("/assets/")) {
                    assetPath = "chess-recognition" + path;
                } else {
                    return emptyResponse(404, "Not found");
                }
                if (assetPath.contains("..")) {
                    return emptyResponse(400, "Invalid path");
                }
                return response(mimeType(assetPath), context.getAssets().open(assetPath));
            } catch (IOException e) {
                return emptyResponse(404, "Not found");
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if ((ORIGIN + "/chess-recognition/index.html").equals(url)) {
                pageLoaded.countDown();
                EventLog.add("Nhận diện ONNX offline đã sẵn sàng");
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost());
        }

        private WebResourceResponse response(String mimeType, InputStream input) {
            String encoding = mimeType.startsWith("text/") ? "UTF-8" : null;
            return new WebResourceResponse(mimeType, encoding, input);
        }

        private WebResourceResponse emptyResponse(int status, String reason) {
            return new WebResourceResponse("text/plain", "UTF-8", status, reason,
                    null, new ByteArrayInputStream(new byte[0]));
        }

        private String mimeType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript";
            if (path.endsWith(".wasm")) return "application/wasm";
            if (path.endsWith(".onnx")) return "application/octet-stream";
            return "application/octet-stream";
        }
    }
}
