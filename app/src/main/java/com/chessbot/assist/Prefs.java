package com.chessbot.assist;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted user settings. */
public final class Prefs {

    private static final String FILE = "chessbot_settings";

    public static final int ORIENT_AUTO = 0;
    public static final int ORIENT_WHITE_BOTTOM = 1;
    public static final int ORIENT_WHITE_TOP = 2;

    public static final int TAP_TAP = 0;
    public static final int TAP_DRAG = 1;

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * One-time settings migrations. Each step only rewrites the previous
     * *default*, so a value the user picked on purpose is preserved.
     */
    public static void migrate(Context c) {
        SharedPreferences p = get(c);
        SharedPreferences.Editor e = null;
        if (!p.getBoolean("migrated_1_1", false)) {
            e = p.edit().putBoolean("migrated_1_1", true);
            if (p.getInt("movetime", 3000) == 800) {
                e.putInt("movetime", 3000);
            }
        }
        // 1.3: full-power defaults. 3000 ms was the 1.1/1.2 default; make sure
        // an update actually gets the stronger 5000 ms setting.
        if (!p.getBoolean("migrated_1_3", false)) {
            if (e == null) {
                e = p.edit();
            }
            e.putBoolean("migrated_1_3", true);
            int mt = p.getInt("movetime", 5000);
            if (mt == 3000 || mt == 800) {
                e.putInt("movetime", 5000);
            }
            if (p.getInt("skill", 20) > 20) {
                e.putInt("skill", 20);
            }
        }
        if (e != null) {
            e.apply();
        }
    }

    public static boolean isBotWhite(Context c) {
        return get(c).getBoolean("bot_white", true);
    }

    public static void setBotWhite(Context c, boolean v) {
        get(c).edit().putBoolean("bot_white", v).apply();
    }

    public static int getOrientation(Context c) {
        return get(c).getInt("orientation", ORIENT_AUTO);
    }

    public static void setOrientation(Context c, int v) {
        get(c).edit().putInt("orientation", v).apply();
    }

    public static int getTapMode(Context c) {
        return get(c).getInt("tap_mode", TAP_TAP);
    }

    public static void setTapMode(Context c, int v) {
        get(c).edit().putInt("tap_mode", v).apply();
    }

    public static int getSkill(Context c) {
        return get(c).getInt("skill", 20);
    }

    public static void setSkill(Context c, int v) {
        get(c).edit().putInt("skill", v).apply();
    }

    public static int getMovetime(Context c) {
        return get(c).getInt("movetime", 5000);
    }

    public static void setMovetime(Context c, int v) {
        get(c).edit().putInt("movetime", v).apply();
    }

    public static String getEngineId(Context c) {
        return get(c).getString("engine_id", StockfishCatalog.BUNDLED_ENGINE_ID);
    }

    public static void setEngineId(Context c, String v) {
        get(c).edit().putString("engine_id", v).apply();
    }

    /** Zero means choose the device's available CPU cores automatically. */
    public static int getThreads(Context c) {
        return get(c).getInt("engine_threads", 0);
    }

    public static void setThreads(Context c, int v) {
        get(c).edit().putInt("engine_threads", Math.max(0, v)).apply();
    }

    /** Zero means choose hash memory automatically from the device's RAM. */
    public static int getHashMb(Context c) {
        return get(c).getInt("engine_hash_mb", 0);
    }

    public static void setHashMb(Context c, int v) {
        get(c).edit().putInt("engine_hash_mb", Math.max(0, v)).apply();
    }

    public static boolean hasCalibration(Context c) {
        return get(c).getBoolean("has_cal", false);
    }

    /** @return {left, top, right, bottom} as fractions of the screen, or null. */
    public static float[] getRect(Context c) {
        SharedPreferences p = get(c);
        if (!p.getBoolean("has_cal", false)) {
            return null;
        }
        return new float[]{
                p.getFloat("cal_l", 0.04f),
                p.getFloat("cal_t", 0.20f),
                p.getFloat("cal_r", 0.96f),
                p.getFloat("cal_b", 0.70f)
        };
    }

    public static void setRect(Context c, float l, float t, float r, float b) {
        get(c).edit()
                .putBoolean("has_cal", true)
                .putFloat("cal_l", l)
                .putFloat("cal_t", t)
                .putFloat("cal_r", r)
                .putFloat("cal_b", b)
                .apply();
    }

    public static int getOverlayX(Context c, int def) {
        return get(c).getInt("ov_x", def);
    }

    public static int getOverlayY(Context c, int def) {
        return get(c).getInt("ov_y", def);
    }

    public static void setOverlayPos(Context c, int x, int y) {
        get(c).edit().putInt("ov_x", x).putInt("ov_y", y).apply();
    }
}
