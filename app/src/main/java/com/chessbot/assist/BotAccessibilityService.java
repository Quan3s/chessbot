package com.chessbot.assist;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The only job of this service is to inject taps / drags into whatever app is
 * in front. It does not read window content.
 */
public class BotAccessibilityService extends AccessibilityService {

    private static final String TAG = "ChessBotAcc";
    private static final long TAP_DURATION_MS = 100;
    private static final long DRAG_DURATION_MS = 320;
    private static volatile BotAccessibilityService instance;

    public static BotAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        EventLog.add("Trợ năng: đã kết nối");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // not used
    }

    @Override
    public void onInterrupt() {
        // not used
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        EventLog.add("Trợ năng: đã ngắt");
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        EventLog.add("Trợ năng: đã ngắt kết nối");
        return super.onUnbind(intent);
    }

    /** A very short tap at (x, y) in screen pixels. */
    public boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, y);
        return dispatchSync(p, 0, TAP_DURATION_MS);
    }

    /** A drag from (x1,y1) to (x2,y2) in screen pixels. */
    public boolean drag(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatchSync(p, 0, DRAG_DURATION_MS);
    }

    private boolean dispatchSync(Path path, long startDelay, long duration) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[]{true};
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, startDelay, duration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean dispatched;
        try {
            dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    ok[0] = false;
                    EventLog.add("Android đã hủy cử chỉ chạm/kéo.");
                    latch.countDown();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture failed", t);
            EventLog.add("Trợ năng từ chối cử chỉ: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " — " + t.getMessage()));
            return false;
        }
        if (!dispatched) {
            EventLog.add("Android không nhận cử chỉ; hãy kiểm tra dịch vụ Trợ năng còn kết nối.");
            return false;
        }
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "gesture timeout");
                EventLog.add("Hết thời gian chờ Android xác nhận cử chỉ.");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EventLog.add("Đang chờ cử chỉ bị ngắt.");
            return false;
        }
        return ok[0];
    }
}
