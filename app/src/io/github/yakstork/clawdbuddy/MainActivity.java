package io.github.yakstork.clawdbuddy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Экран настроек. Сам Clawd живёт в {@link ClawdService}. */
public final class MainActivity extends Activity {
    private static final int BG = 0xFF1F1E1B;
    private static final int CARD = 0xFF2A2925;
    private static final int TEXT = 0xFFECE9E1;
    private static final int MUTED = 0xFF9C978B;
    private static final int ACCENT = 0xFFD97757;
    private static final int LINE = 0xFF3D3A34;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ClawdView preview;
    private TextView serviceState;
    private Button serviceButton;
    private TextView diag;
    private LinearLayout sizeRow;
    private LinearLayout colorRows;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateService();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(32));

        col.addView(previewBlock());
        col.addView(title("Clawd для Claude", 24, TEXT, true));
        col.addView(text("Сидит над полем ввода официального приложения Claude. Само приложение не меняется: "
                + "та же подпись, вход через Google и обновления из Play Маркета.", MUTED), margins(0, 4, 0, 14));

        col.addView(serviceCard());
        col.addView(lookCard());
        col.addView(behaviourCard());
        col.addView(diagCard());

        Button open = button("Открыть Claude", true);
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openClaude(); }
        });
        col.addView(open, margins(0, 6, 0, 0));
        col.addView(text("Неофициальное приложение, не связано с Anthropic.", MUTED), margins(0, 16, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ---- блоки ----

    private View previewBlock() {
        FrameLayout frame = new FrameLayout(this);
        // «поле ввода», на котором он стоит
        View composer = new View(this);
        composer.setBackground(round(CARD, dp(22), LINE));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        clp.gravity = Gravity.BOTTOM;
        frame.addView(composer, clp);

        preview = new ClawdView(this);
        preview.setSizeDp(96);
        preview.setColor(Prefs.color(prefs));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM | Gravity.START;
        plp.leftMargin = dp(24);
        plp.bottomMargin = dp(48) - Math.round(preview.cellPx() * 0.5f);
        frame.addView(preview, plp);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { preview.jump(); }
        });

        LinearLayout demo = new LinearLayout(this);
        demo.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.TOP | Gravity.END;
        Button typing = chip("Печатает");
        typing.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                preview.setMood(preview.mood() == ClawdView.Mood.TYPING ? ClawdView.Mood.IDLE : ClawdView.Mood.TYPING);
            }
        });
        Button happy = chip("Ответил!");
        happy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { preview.celebrate(); }
        });
        demo.addView(typing);
        demo.addView(happy, margins(0, 6, 0, 0));
        frame.addView(demo, dlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        lp.bottomMargin = dp(16);
        frame.setLayoutParams(lp);
        return frame;
    }

    private View serviceCard() {
        LinearLayout c = card("Служба");
        serviceState = text("", TEXT);
        c.addView(serviceState);
        serviceButton = button("Включить в спецвозможностях", true);
        serviceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("Найди «Clawd» в списке установленных служб и включи");
            }
        });
        c.addView(serviceButton, margins(0, 10, 0, 0));
        c.addView(text("Переключатель серый и пишет «Ограниченная настройка»? Android так защищает приложения, "
                + "установленные не из Play Маркета. Открой настройки приложения → ⋮ вверху справа → "
                + "«Разрешить ограниченные настройки», потом включи службу снова.", MUTED), margins(0, 10, 0, 0));
        Button details = button("Настройки приложения", false);
        details.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        c.addView(details, margins(0, 8, 0, 0));
        return c;
    }

    private View lookCard() {
        LinearLayout c = card("Внешний вид");
        c.addView(toggle("Показывать Clawd", Prefs.ENABLED, Prefs.enabled(prefs)));

        c.addView(label("Размер"));
        sizeRow = new LinearLayout(this);
        String[] names = {"S", "M", "L"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = chip(names[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt(Prefs.SIZE, idx).apply();
                    paintSize();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            lp.rightMargin = i < names.length - 1 ? dp(8) : 0;
            sizeRow.addView(b, lp);
        }
        c.addView(sizeRow);
        paintSize();

        c.addView(label("Цвет"));
        colorRows = new LinearLayout(this);
        colorRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < Prefs.COLORS.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                colorRows.addView(row, margins(0, i == 0 ? 0 : 10, 0, 0));
            }
            final int color = Prefs.COLORS[i];
            View sw = new View(this);
            sw.setTag(color);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt(Prefs.COLOR, color).apply();
                    preview.setColor(color);
                    paintColors();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(14);
            row.addView(sw, lp);
        }
        c.addView(colorRows);
        paintColors();

        final TextView heightLabel = label("");
        c.addView(heightLabel);
        SeekBar seek = new SeekBar(this);
        seek.setMax(40);
        seek.setProgress(Prefs.yOffsetDp(prefs) + 20);
        heightLabel.setText(heightText(Prefs.yOffsetDp(prefs)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                heightLabel.setText(heightText(p - 20));
                if (user) prefs.edit().putInt(Prefs.Y_OFFSET_DP, p - 20).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        c.addView(seek);
        c.addView(text("Вдоль поля ввода его можно двигать пальцем прямо в Claude — место запомнится.", MUTED),
                margins(0, 8, 0, 0));
        return c;
    }

    private View behaviourCard() {
        LinearLayout c = card("Поведение");
        c.addView(toggle("Печатает на ноутбуке, пока Claude отвечает", Prefs.TYPING, Prefs.typing(prefs)));
        c.addView(toggle("Подпрыгивает, когда ответ готов", Prefs.CELEBRATE, Prefs.celebrate(prefs)));
        c.addView(toggle("Вибрация при нажатии на него", Prefs.HAPTICS, Prefs.haptics(prefs)));
        return c;
    }

    private View diagCard() {
        LinearLayout c = card("Проверка");
        diag = text("", MUTED);
        diag.setTypeface(Typeface.MONOSPACE);
        diag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.addView(diag);
        Button copy = button("Скопировать отчёт", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String r = ClawdService.lastReport;
                if (r == null) {
                    toast("Сначала открой чат в Claude, чтобы Clawd там появился");
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Clawd", r));
                toast("Скопировано — пришли, если что-то работает не так");
            }
        });
        c.addView(copy, margins(0, 10, 0, 0));
        c.addView(text("В отчёте только кнопки и рамки вокруг поля ввода, без сообщений чата.", MUTED),
                margins(0, 8, 0, 0));
        return c;
    }

    // ---- состояние ----

    private void updateService() {
        boolean on = isServiceEnabled();
        serviceState.setText(on ? "● Включена" : "○ Выключена — Clawd не появится");
        serviceState.setTextColor(on ? 0xFF8FD2B1 : TEXT);
        serviceButton.setVisibility(on ? View.GONE : View.VISIBLE);
        diag.setText(on ? ClawdService.status : "служба выключена");
    }

    private boolean isServiceEnabled() {
        if (ClawdService.instance != null) return true;
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(list);
        String me = getPackageName() + "/" + ClawdService.class.getName();
        String meShort = getPackageName() + "/.ClawdService";
        while (split.hasNext()) {
            String s = split.next();
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }

    private void openClaude() {
        Intent i = getPackageManager().getLaunchIntentForPackage(ClawdService.CLAUDE);
        if (i != null) {
            startActivity(i);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ClawdService.CLAUDE)));
        }
    }

    private void paintSize() {
        int sel = Prefs.size(prefs);
        for (int i = 0; i < sizeRow.getChildCount(); i++) {
            Button b = (Button) sizeRow.getChildAt(i);
            b.setBackground(round(i == sel ? ACCENT : BG, dp(12), i == sel ? ACCENT : LINE));
            b.setTextColor(i == sel ? Color.WHITE : TEXT);
        }
    }

    private void paintColors() {
        int sel = Prefs.color(prefs);
        for (int r = 0; r < colorRows.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) colorRows.getChildAt(r);
            for (int i = 0; i < row.getChildCount(); i++) {
                View v = row.getChildAt(i);
                int color = (Integer) v.getTag();
                GradientDrawable d = round(color, dp(22), color == sel ? TEXT : LINE);
                d.setStroke(dp(color == sel ? 3 : 1), color == sel ? TEXT : LINE);
                v.setBackground(d);
            }
        }
    }

    private static String heightText(int dp) {
        return "Высота посадки: " + (dp > 0 ? "+" : "") + dp + " dp";
    }

    // ---- мелкие виджеты ----

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(CARD, dp(20), LINE));
        c.addView(title(title, 17, TEXT, true), margins(0, 0, 0, 8));
        c.setLayoutParams(margins(0, 0, 0, 12));
        return c;
    }

    private View toggle(String label, final String key, boolean value) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setChecked(value);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean(key, on).apply();
            }
        });
        return s;
    }

    private TextView title(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView text(String s, int color) {
        TextView t = title(s, 14, color, false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView label(String s) {
        TextView t = title(s, 13, MUTED, false);
        t.setPadding(0, dp(14), 0, dp(8));
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setBackground(round(primary ? ACCENT : 0xFF34322D, dp(14), primary ? ACCENT : LINE));
        b.setMinHeight(dp(48));
        b.setStateListAnimator(null);
        return b;
    }

    private Button chip(String s) {
        Button b = button(s, false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
