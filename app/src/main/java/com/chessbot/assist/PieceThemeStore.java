package com.chessbot.assist;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stores locally trained piece templates for selectable board-piece themes. */
public final class PieceThemeStore {

    private static final String FILE = "piece_themes";
    private static final String THEME_IDS = "theme_ids";
    private static final String SELECTED_THEME = "selected_theme";

    public static final class Theme {
        public final String id;
        public final String name;

        Theme(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private PieceThemeStore() {
    }

    public static List<Theme> getThemes(Context context) {
        SharedPreferences prefs = preferences(context);
        List<Theme> themes = new ArrayList<>();
        for (String id : splitIds(prefs.getString(THEME_IDS, ""))) {
            String name = prefs.getString("name_" + id, null);
            if (name != null && !name.trim().isEmpty()) {
                themes.add(new Theme(id, name));
            }
        }
        return themes;
    }

    public static String getSelectedId(Context context) {
        return preferences(context).getString(SELECTED_THEME, "");
    }

    public static void setSelectedId(Context context, String id) {
        preferences(context).edit().putString(SELECTED_THEME, id == null ? "" : id).apply();
    }

    public static byte[] getTemplates(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String encoded = preferences(context).getString("data_" + id, null);
        return encoded == null ? null : android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
    }

    public static Theme save(Context context, String name, byte[] templates) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 40 || templates == null) {
            throw new IllegalArgumentException("Invalid piece theme");
        }
        SharedPreferences prefs = preferences(context);
        List<String> ids = splitIds(prefs.getString(THEME_IDS, ""));
        String id = null;
        for (String candidate : ids) {
            String savedName = prefs.getString("name_" + candidate, "");
            if (savedName.equalsIgnoreCase(cleanName)) {
                id = candidate;
                break;
            }
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
            ids.add(id);
        }
        String encoded = android.util.Base64.encodeToString(templates, android.util.Base64.NO_WRAP);
        prefs.edit()
                .putString(THEME_IDS, String.join(",", ids))
                .putString("name_" + id, cleanName)
                .putString("data_" + id, encoded)
                .putString(SELECTED_THEME, id)
                .apply();
        return new Theme(id, cleanName);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static List<String> splitIds(String stored) {
        List<String> ids = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return ids;
        }
        for (String id : stored.split(",")) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
