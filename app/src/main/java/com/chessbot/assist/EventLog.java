package com.chessbot.assist;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small in-process log shared by the service, the controller and the UI. */
public final class EventLog {

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static Listener listener;

    private EventLog() {
    }

    public static synchronized void add(String message) {
        String line = TIME.format(new Date()) + "  " + message;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
        Log.i("ChessBot", message);
        Listener l = listener;
        if (l != null) {
            l.onLog(line);
        }
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    public static synchronized void setListener(Listener l) {
        listener = l;
    }
}
