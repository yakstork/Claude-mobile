package io.github.yakstork.clawdbuddy;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки, общие для экрана настроек и службы. */
final class Prefs {
    static final String NAME = "clawd";

    static final String ENABLED = "enabled";
    static final String SIZE = "size";
    static final String COLOR = "color";
    static final String X_FRACTION = "x_fraction";
    static final String Y_OFFSET_DP = "y_offset_dp";
    static final String TYPING = "typing";
    static final String CELEBRATE = "celebrate";
    static final String HAPTICS = "haptics";
    static final String STYLE = "style";
    static final String SLEEP = "sleep";
    static final String WALK = "walk";
    static final String STOP_REACT = "stop_react";
    static final String WATCH_TYPING = "watch_typing";
    static final String TOSS = "toss";
    static final String HAT = "hat";
    static final String COUNT = "count";
    static final String COUNT_DAY = "count_day";
    static final String COUNT_TODAY = "count_today";
    static final String COUNT_TOTAL = "count_total";

    /** Ширина Clawd в dp для размеров S / M / L (на claude.ai он примерно как S). */
    static final int[] SIZES_DP = {34, 42, 54};

    static final int[] COLORS = {
        0xFFD97757, // оранжевый Claude
        0xFFE8A04A, // янтарь
        0xFF8FD2B1, // мята
        0xFF6CA0F0, // голубой
        0xFFA98BF0, // лаванда
        0xFFF08FB5, // розовый
        0xFFEDEDED, // белый
        0xFF3A3A3A, // графит
    };

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(SharedPreferences p) { return p.getBoolean(ENABLED, true); }
    static int size(SharedPreferences p) { return p.getInt(SIZE, 1); }
    static int color(SharedPreferences p) { return p.getInt(COLOR, COLORS[0]); }
    static float xFraction(SharedPreferences p) { return p.getFloat(X_FRACTION, 0.94f); }
    static int yOffsetDp(SharedPreferences p) { return p.getInt(Y_OFFSET_DP, 0); }
    static boolean typing(SharedPreferences p) { return p.getBoolean(TYPING, true); }
    static boolean celebrate(SharedPreferences p) { return p.getBoolean(CELEBRATE, true); }
    static boolean haptics(SharedPreferences p) { return p.getBoolean(HAPTICS, true); }
    static boolean sleep(SharedPreferences p) { return p.getBoolean(SLEEP, true); }
    static boolean walk(SharedPreferences p) { return p.getBoolean(WALK, true); }
    static boolean stopReact(SharedPreferences p) { return p.getBoolean(STOP_REACT, true); }
    static boolean watchTyping(SharedPreferences p) { return p.getBoolean(WATCH_TYPING, true); }
    static boolean toss(SharedPreferences p) { return p.getBoolean(TOSS, true); }
    static boolean count(SharedPreferences p) { return p.getBoolean(COUNT, true); }
    static int countDay(SharedPreferences p) { return p.getInt(COUNT_DAY, 0); }
    static int countToday(SharedPreferences p) { return p.getInt(COUNT_TODAY, 0); }
    static int countTotal(SharedPreferences p) { return p.getInt(COUNT_TOTAL, 0); }

    /** Сегодняшняя дата как yyyymmdd по местному времени. */
    static int today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    static ClawdView.Hat hat(SharedPreferences p) {
        try {
            return ClawdView.Hat.valueOf(p.getString(HAT, "none").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ClawdView.Hat.NONE;
        }
    }

    static ClawdView.Style style(SharedPreferences p) {
        try {
            return ClawdView.Style.valueOf(p.getString(STYLE, ClawdView.Style.WEB.name()));
        } catch (IllegalArgumentException e) {
            return ClawdView.Style.WEB;
        }
    }
}
