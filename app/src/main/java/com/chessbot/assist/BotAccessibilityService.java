package com.chessbot.assist;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
        instance = null;
        EventLog.add("Trợ năng: đã ngắt");
        super.onDestroy();
    }

    /** A very short tap at (x, y) in screen pixels. */
    public boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 1.5f, y + 1.5f);
        return dispatchSync(p, 0, 55);
    }

    /** A drag from (x1,y1) to (x2,y2) in screen pixels. */
    public boolean drag(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatchSync(p, 0, 260);
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
                    latch.countDown();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture failed", t);
            return false;
        }
        if (!dispatched) {
            return false;
        }
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "gesture timeout");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok[0];
    }
}
