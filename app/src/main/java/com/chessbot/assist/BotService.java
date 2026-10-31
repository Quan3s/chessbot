package com.chessbot.assist;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Foreground service that owns: the floating toggle button, the MediaProjection
 * capture session and the bot loop.
 */
public class BotService extends Service {

    public static final String ACTION_PREPARE = "com.chessbot.assist.action.PREPARE";
    public static final String ACTION_TOGGLE = "com.chessbot.assist.action.TOGGLE";
    public static final String ACTION_START = "com.chessbot.assist.action.START";
    public static final String ACTION_STOP = "com.chessbot.assist.action.STOP";
    public static final String ACTION_CALIBRATE = "com.chessbot.assist.action.CALIBRATE";
    public static final String ACTION_SHUTDOWN = "com.chessbot.assist.action.SHUTDOWN";

    private static final String CHANNEL = "chessbot";
    private static final int NOTIF_ID = 41;
    private static volatile BotService instance;

    private WindowManager wm;
    private Handler handler;
    private int screenW;
    private int screenH;
    private int screenDpi;
    private int fabSize;

    private FloatingButtonView fab;
    private WindowManager.LayoutParams fabParams;
    private MediaProjection projection;
    private ScreenCapture capture;
    private GameController controller;
    private ChessVisionRecognizer chessVisionRecognizer;
    private boolean prepared;
    private boolean calibrationShowing;
    private View calibrationView;
    private SidePickerOverlay sidePicker;
    private boolean sidePickerShowing;
    private long generation;

    public static BotService get() {
        return instance;
    }

    public static boolean isPrepared() {
        BotService s = instance;
        return s != null && s.prepared;
    }

    public static boolean isRunning() {
        BotService s = instance;
        return s != null && s.controller != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Prefs.migrate(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        fabSize = dp(58);
        updateScreenMetrics();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PREPARE.equals(action)) {
            prepare(intent);
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_START.equals(action)) {
            startBot();
        } else if (ACTION_STOP.equals(action)) {
            stopBot(true);
        } else if (ACTION_CALIBRATE.equals(action)) {
            showCalibration();
        } else if (ACTION_SHUTDOWN.equals(action)) {
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopBot(false);
        closeSidePicker(false);
        releaseProjection();
        hideFab();
        if (calibrationView != null) {
            try {
                wm.removeView(calibrationView);
            } catch (Throwable ignored) {
            }
            calibrationView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int oldW = screenW;
        int oldH = screenH;
        updateScreenMetrics();
        if (sidePickerShowing) {
            closeSidePicker(true);
        }
        if (screenW != oldW || screenH != oldH) {
            if (controller != null) {
                stopBot(true);
                toast("Đã xoay màn hình — hãy hiệu chỉnh lại bàn cờ");
            }
            if (calibrationShowing) {
                closeCalibration();
            }
            if (prepared && projection != null) {
                if (capture != null) {
                    capture.release();
                    capture = null;
                }
                try {
                    capture = new ScreenCapture(projection, screenW, screenH, screenDpi, 0.5f);
                    capture.start();
                } catch (Throwable t) {
                    prepared = false;
                    EventLog.add("Không tạo lại được capture sau khi xoay: " + t);
                }
            }
            if (fab != null && fabParams != null) {
                fabParams.x = clamp(fabParams.x, 0, Math.max(0, screenW - fabSize));
                fabParams.y = clamp(fabParams.y, 0, Math.max(0, screenH - fabSize));
                try {
                    wm.updateViewLayout(fab, fabParams);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ prepare

    private void prepare(Intent intent) {
        int code = intent.getIntExtra("code", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        if (data == null || code == Activity.RESULT_CANCELED) {
            toast("Không nhận được quyền quay màn hình");
            stopSelf();
            return;
        }
        startForegroundCompat("Đang chuẩn bị…");
        releaseProjection();
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                throw new IllegalStateException("MediaProjection = null");
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    handler.post(() -> {
                        if (projection == null) {
                            return;
                        }
                        EventLog.add("Quyền quay màn hình đã bị thu hồi");
                        prepared = false;
                        stopBot(false);
                        if (capture != null) {
                            capture.release();
                            capture = null;
                        }
                        projection = null;
                        updateNotification("Quyền quay màn hình đã bị thu hồi");
                        toast("Quyền quay màn hình đã bị thu hồi. Mở ChessBot để cấp lại.");
                    });
                }
            }, handler);

            updateScreenMetrics();
            capture = new ScreenCapture(projection, screenW, screenH, screenDpi, 0.5f);
            capture.start();
            prepared = true;
            showFab();
            updateNotification("Sẵn sàng — nhấn ▶ để chọn màu quân và chạy");
            EventLog.add("Đã bật nút nổi (" + screenW + "×" + screenH + ")");
            toast("Đã bật nút nổi. Mở app cờ vua, giữ nút để hiệu chỉnh.");
        } catch (Throwable t) {
            prepared = false;
            releaseProjection();
            EventLog.add("Không tạo được MediaProjection: " + t);
            toast("Không tạo được quyền quay màn hình: " + t.getMessage());
            stopSelf();
        }
    }

    private void releaseProjection() {
        prepared = false;
        MediaProjection p = projection;
        projection = null;
        if (capture != null) {
            capture.release();
            capture = null;
        }
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ floating button

    private void showFab() {
        if (fab != null) {
            return;
        }
        fab = new FloatingButtonView(this);
        fab.setRunning(controller != null);
        int defX = screenW - fabSize - dp(10);
        int defY = screenH / 2;
        fabParams = new WindowManager.LayoutParams(
                fabSize, fabSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        fabParams.gravity = Gravity.TOP | Gravity.START;
        fabParams.x = clamp(Prefs.getOverlayX(this, defX), 0, Math.max(0, screenW - fabSize));
        fabParams.y = clamp(Prefs.getOverlayY(this, defY), 0, Math.max(0, screenH - fabSize));
        fab.setOnTouchListener(new FabTouch());
        try {
            wm.addView(fab, fabParams);
        } catch (Throwable t) {
            EventLog.add("Không thêm được nút nổi: " + t);
            toast("Không thêm được nút nổi (thiếu quyền?)");
            fab = null;
            fabParams = null;
        }
    }

    private void hideFab() {
        if (fab != null) {
            try {
                wm.removeView(fab);
            } catch (Throwable ignored) {
            }
            fab = null;
            fabParams = null;
        }
    }

    private final class FabTouch implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;
        private boolean longFired;
        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                longFired = true;
                showCalibration();
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = fabParams.x;
                    startY = fabParams.y;
                    moved = false;
                    longFired = false;
                    handler.postDelayed(longPress, 550);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (fabParams == null) {
                        return true;
                    }
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                        moved = true;
                        handler.removeCallbacks(longPress);
                    }
                    if (moved) {
                        fabParams.x = clamp(startX + (int) dx, 0, Math.max(0, screenW - fabSize));
                        fabParams.y = clamp(startY + (int) dy, 0, Math.max(0, screenH - fabSize));
                        try {
                            wm.updateViewLayout(fab, fabParams);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (fabParams == null) {
                        return true;
                    }
                    if (moved) {
                        Prefs.setOverlayPos(BotService.this, fabParams.x, fabParams.y);
                    } else if (!longFired) {
                        toggle();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ bot control

    private void toggle() {
        if (controller != null) {
            stopBot(true);
        } else if (sidePickerShowing) {
            closeSidePicker();
        } else {
            showSidePicker();
        }
    }

    /**
     * Ask which colour the human plays right before starting. This keeps the
     * choice visible at the moment it matters instead of hiding it in settings.
     */
    private void showSidePicker() {
        if (!prepared || capture == null) {
            toast("Hãy mở ChessBot và bật nút nổi trước");
            return;
        }
        if (!BotAccessibilityService.isConnected()) {
            toast("Hãy bật Trợ năng cho ChessBot");
            return;
        }
        if (!Prefs.hasCalibration(this)) {
            // startBot() will open the calibration overlay.
            startBot();
            return;
        }
        if (controller != null || sidePickerShowing || calibrationShowing) {
            return;
        }
        stopBot(false);
        hideFab();
        sidePickerShowing = true;
        SidePickerOverlay picker = new SidePickerOverlay(this, Prefs.isBotWhite(this),
                new SidePickerOverlay.Callback() {
                    @Override
                    public void onPick(boolean userWhite) {
                        // The bot plays the same colour the human is playing.
                        Prefs.setBotWhite(BotService.this, userWhite);
                        EventLog.add("Bạn cầm " + (userWhite ? "Trắng" : "Đen")
                                + " · bot sẽ đi quân " + (userWhite ? "Trắng" : "Đen"));
                        // Keep the FAB hidden for a moment: the overlay needs a
                        // frame or two to disappear from MediaProjection before
                        // the bot takes its first look at the board.
                        closeSidePicker(false);
                        handler.postDelayed(BotService.this::startBotAfterPicker, 300);
                    }

                    @Override
                    public void onCancel() {
                        closeSidePicker();
                    }
                });
        sidePicker = picker;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(picker, lp);
        } catch (Throwable t) {
            sidePickerShowing = false;
            sidePicker = null;
            showFab();
            toast("Không mở được menu chọn màu quân");
        }
    }

    private void closeSidePicker() {
        closeSidePicker(true);
    }

    private void closeSidePicker(boolean restoreFab) {
        if (sidePicker != null) {
            try {
                wm.removeView(sidePicker);
            } catch (Throwable ignored) {
            }
            sidePicker = null;
        }
        sidePickerShowing = false;
        if (restoreFab) {
            showFab();
        }
    }

    private void startBotAfterPicker() {
        if (!prepared || instance != this) {
            return;
        }
        startBot();
        // If startBot() bailed out (e.g. accessibility was revoked while the
        // menu was open), do not leave the user without a floating button.
        if (fab == null && prepared && !calibrationShowing && !sidePickerShowing) {
            showFab();
        }
    }

    private void startBot() {
        if (sidePickerShowing) {
            return;
        }
        if (!prepared || capture == null) {
            toast("Hãy mở ChessBot và bật nút nổi trước");
            return;
        }
        if (!BotAccessibilityService.isConnected()) {
            toast("Hãy bật Trợ năng cho ChessBot");
            return;
        }
        if (!Prefs.hasCalibration(this)) {
            toast("Chưa hiệu chỉnh bàn cờ");
            showCalibration();
            return;
        }
        if (controller != null) {
            return;
        }
        updateScreenMetrics();
        float[] cal = Prefs.getRect(this);
        if (cal != null) {
            moveFabOutsideBoard(cal[0], cal[1], cal[2], cal[3]);
        }
        ChessVisionRecognizer recognizer = getChessVisionRecognizer();
        final long gen = ++generation;
        final GameController c = new GameController(this, capture, recognizer, screenW, screenH,
                new GameController.Listener() {
                    @Override
                    public void onStatus(String text) {
                        handler.post(() -> updateNotification(text));
                    }

                    @Override
                    public void onFinished(String reason, boolean error) {
                        handler.post(() -> {
                            if (gen != generation) {
                                return;
                            }
                            controller = null;
                            releaseChessVisionRecognizer();
                            if (fab != null) {
                                fab.setRunning(false);
                            }
                            updateNotification(reason);
                            toast(reason);
                        });
                    }
                });
        controller = c;
        if (fab == null) {
            showFab();
        }
        if (fab != null) {
            fab.setRunning(true);
        }
        updateNotification("Đang chơi…");
        EventLog.add("Bắt đầu bot");
        c.start();
    }

    private ChessVisionRecognizer getChessVisionRecognizer() {
        if (chessVisionRecognizer != null) {
            return chessVisionRecognizer;
        }
        try {
            chessVisionRecognizer = new ChessVisionRecognizer(this, wm);
        } catch (RuntimeException e) {
            EventLog.add("Không khởi tạo được model cờ; dùng nhận diện dự phòng: " + e);
        }
        return chessVisionRecognizer;
    }

    private void releaseChessVisionRecognizer() {
        ChessVisionRecognizer recognizer = chessVisionRecognizer;
        chessVisionRecognizer = null;
        if (recognizer != null) {
            recognizer.close();
        }
    }

    private void stopBot(boolean announce) {
        GameController c = controller;
        controller = null;
        if (c != null) {
            c.stop();
        }
        releaseChessVisionRecognizer();
        if (fab != null) {
            fab.setRunning(false);
        }
        if (announce) {
            updateNotification("Đã dừng — nhấn ▶ để chọn màu quân và chạy");
            EventLog.add("Đã dừng bot");
        }
    }

    // ------------------------------------------------------------------ calibration

    private void showCalibration() {
        if (!prepared || capture == null) {
            toast("Hãy mở ChessBot và bật nút nổi trước");
            return;
        }
        if (calibrationShowing) {
            return;
        }
        if (sidePickerShowing) {
            closeSidePicker(false);
        }
        stopBot(true);
        hideFab();
        calibrationShowing = true;
        final float[] initial = Prefs.getRect(this);
        CalibrationOverlay overlay = new CalibrationOverlay(this, screenW, screenH, initial,
                Prefs.isBotWhite(this),
                new CalibrationOverlay.Callback() {
                    @Override
                    public void onSave(float l, float t, float r, float b,
                                       boolean userWhite, boolean startNow) {
                        Prefs.setRect(BotService.this, l, t, r, b);
                        Prefs.setBotWhite(BotService.this, userWhite);
                        moveFabOutsideBoard(l, t, r, b);
                        closeCalibration();
                        EventLog.add("Đã lưu vùng bàn cờ · bạn cầm "
                                + (userWhite ? "Trắng" : "Đen"));
                        // Do not toast here: a Toast is a system overlay and
                        // would be captured by MediaProjection while the
                        // verification frame is taken, which can corrupt the
                        // board read and hide the side picker.
                        verifyCalibrationAsync(() -> {
                            if (startNow) {
                                startBot();
                            }
                        });
                    }

                    @Override
                    public void onCancel() {
                        closeCalibration();
                    }
                });
        calibrationView = overlay;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(overlay, lp);
        } catch (Throwable t) {
            calibrationShowing = false;
            calibrationView = null;
            showFab();
            toast("Không mở được màn hình hiệu chỉnh");
        }
    }

    private void closeCalibration() {
        if (calibrationView != null) {
            try {
                wm.removeView(calibrationView);
            } catch (Throwable ignored) {
            }
            calibrationView = null;
        }
        calibrationShowing = false;
        showFab();
    }

    /**
     * The floating button is drawn in the screen capture too. If it overlaps
     * the board it corrupts recognition, so put it just outside the calibrated
     * area (above, below, left or right - whichever fits on screen).
     */
    private void moveFabOutsideBoard(float l, float t, float r, float b) {
        int left = Math.round(l * screenW);
        int top = Math.round(t * screenH);
        int right = Math.round(r * screenW);
        int bottom = Math.round(b * screenH);
        int margin = dp(6);
        int defX = screenW - fabSize - dp(10);
        int defY = screenH / 2;
        int x = Prefs.getOverlayX(this, defX);
        int y = Prefs.getOverlayY(this, defY);

        if (!overlapsBoard(x, y, left, top, right, bottom, margin)) {
            return;
        }
        int[][] candidates = new int[][]{
                {right - fabSize, top - fabSize - margin},   // above, right aligned
                {right - fabSize, bottom + margin},          // below
                {left - fabSize - margin, top},              // left
                {right + margin, top}                        // right
        };
        for (int[] c : candidates) {
            int cx = c[0];
            int cy = c[1];
            boolean fits = cx >= 0 && cy >= 0 && cx <= screenW - fabSize && cy <= screenH - fabSize;
            if (fits && !overlapsBoard(cx, cy, left, top, right, bottom, margin)) {
                Prefs.setOverlayPos(this, cx, cy);
                return;
            }
        }
        // Last resort: top-left corner (almost always outside a centred board).
        Prefs.setOverlayPos(this, 0, 0);
    }

    private boolean overlapsBoard(int x, int y, int left, int top, int right, int bottom, int margin) {
        return x < right + margin && x + fabSize > left - margin
                && y < bottom + margin && y + fabSize > top - margin;
    }

    private void verifyCalibrationAsync(Runnable onSuccess) {
        new Thread(() -> {
            boolean ok = false;
            try {
                sleep(350); // let the overlay disappear from the capture
                float[] r = Prefs.getRect(this);
                if (r == null) {
                    return;
                }
                BoardReader.StartRead bestRead = null;
                int[] previousExact = null;
                int previousThreshold = -1;
                for (int i = 0; i < 8; i++) {
                    if (capture == null) {
                        break;
                    }
                    ScreenCapture.Frame frame = capture.grab();
                    if (frame != null) {
                        BoardReader.StartRead read = BoardReader.readStartingPosition(
                                frame.pixels, frame.width, frame.height,
                                r[0], r[1], r[2], r[3]);
                        if (bestRead == null || read.mismatch < bestRead.mismatch) {
                            bestRead = read;
                        }
                        if (read.mismatch == 0) {
                            int[] current = read.result.screenOccupancy();
                            if (previousThreshold == read.devThreshold
                                    && java.util.Arrays.equals(previousExact, current)) {
                                bestRead = read;
                                break;
                            }
                            previousExact = current;
                            previousThreshold = read.devThreshold;
                        } else {
                            previousExact = null;
                            previousThreshold = -1;
                        }
                    }
                    sleep(150);
                }
                if (bestRead == null) {
                    toast("Không chụp được màn hình để kiểm tra");
                    return;
                }
                BoardReader.Result res = bestRead.result;
                String msg;
                msg = "Nhầm " + bestRead.falsePositives + " ô trống thành có quân, bỏ sót "
                        + bestRead.missedPieces + " quân; đúng "
                        + (64 - bestRead.mismatch) + "/64 ô";
                ok = bestRead.isValid();
                if (ok) {
                    Prefs.setBoardThresholds(this, bestRead.devThreshold,
                            bestRead.fillThreshold);
                } else {
                    msg += " — căn khung sát mép bàn cờ và mở ván mới";
                }
                EventLog.add("Kiểm tra hiệu chỉnh: " + msg);
                EventLog.add("Ngưỡng nhận diện: chênh lệch " + bestRead.devThreshold
                        + " · diện tích " + Math.round(bestRead.fillThreshold * 100) + "%");
                EventLog.add(res.debugRows());
                final String m = msg;
                final boolean passed = ok;
                // On success the side picker itself is the confirmation; a
                // Toast here would still be on screen when the user picks a
                // side and the bot takes its first capture. Only report
                // failures with a Toast.
                handler.post(() -> {
                    if (!passed) {
                        toast(m);
                    }
                });
            } finally {
                postIf(ok, onSuccess);
            }
        }, "verify-calibration").start();
    }

    private void postIf(boolean condition, Runnable action) {
        if (condition && action != null) {
            handler.post(action);
        }
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ChessBot",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Trạng thái bot cờ vua");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BotService.class);
        stop.setAction(ACTION_SHUTDOWN);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ChessBot Assist")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "Tắt", stopPi).build());
        return b.build();
    }

    // ------------------------------------------------------------------ misc

    private void shutdown() {
        stopBot(false);
        closeSidePicker(false);
        releaseProjection();
        hideFab();
        if (calibrationView != null) {
            try {
                wm.removeView(calibrationView);
            } catch (Throwable ignored) {
            }
            calibrationView = null;
            calibrationShowing = false;
        }
        stopForeground(true);
        stopSelf();
        EventLog.add("Đã tắt nút nổi");
    }

    private void updateScreenMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        screenDpi = dm.densityDpi;
    }

    private void toast(String message) {
        handler.post(() -> {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
