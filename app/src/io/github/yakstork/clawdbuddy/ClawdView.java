package io.github.yakstork.clawdbuddy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Clawd в одном из трёх стилей:
 *  - WEB — как на claude.ai/code: кадры {@link WebFrames}, сидит в профиль и печатает за ноутбуком;
 *  - SQUARE — квадратный из версии 0.2;
 *  - CLASSIC — вытянутый из версии 0.1.
 * Клетка — целое число пикселей, края чёткие. Пока он стоит, кадры не перерисовываются: моргание
 * и взгляд — редкие отложенные задачи; во время анимаций — 12 кадров в секунду, как на сайте.
 */
final class ClawdView extends View {
    enum Mood { IDLE, TYPING, OUTRO, HAPPY, DANCE }
    enum Style { WEB, SQUARE, CLASSIC }

    private static final long TICK_MS = 83; // 12 кадров в секунду
    private static final int HAPPY_TICKS = 17;
    private static final int DANCE_TICKS = 20;
    private static final long JUMP_MS = 440;
    private static final int WEB_HEAD = 3;

    private static final int LAPTOP = 0xFFC5C9CF;
    private static final int LAPTOP_LOGO = 0xFFE9EBEE;
    private static final int LAPTOP_BASE = 0xFF8A8F96;
    private static final int WEB_LAPTOP = 0xFF8C8C8C;

    private final Paint paint = new Paint();
    private final Random random = new Random();
    private Style style = Style.WEB;
    private int sizeDp = 42;
    private int cell = 1;
    private int body, shade, eyes;

    private Mood mood = Mood.IDLE;
    private Mood pending;
    private int tick;
    private int look; // -1 влево, 0 прямо, 1 вправо
    private boolean blinking;
    private long jumpStart = -1;

    ClawdView(Context c) {
        super(c);
        paint.setAntiAlias(false);
        setColor(0xFFD97757);
        recalc();
    }

    // ---- геометрия стиля ----

    /** Ширина сетки, высота сетки (с запасом под прыжок), ширина «тела» для масштаба, тело в сетке. */
    private int cols() { return style == Style.WEB ? WebFrames.W : style == Style.SQUARE ? 14 : 16; }
    private int rows() { return style == Style.WEB ? WebFrames.H + WEB_HEAD : 14; }
    private int bodyCols() { return style == Style.WEB ? 24 : style == Style.SQUARE ? 12 : 16; }
    private int bodyLeft() { return style == Style.WEB ? WebFrames.BODY_LEFT : style == Style.SQUARE ? 1 : 0; }
    private int bodyTop() { return style == Style.WEB ? WEB_HEAD + WebFrames.BODY_TOP : style == Style.SQUARE ? 6 : 5; }

    void setStyle(Style s) {
        if (style == s) return;
        style = s;
        reset();
        setColor(body);
        recalc();
    }

    Style style() { return style; }

    /** Ширина самого Clawd (с руками, без ноутбука и запаса) в dp. */
    void setSizeDp(int dp) {
        if (sizeDp == dp) return;
        sizeDp = dp;
        recalc();
    }

    private void recalc() {
        cell = Math.max(1, Math.round(sizeDp * getResources().getDisplayMetrics().density / bodyCols()));
        requestLayout();
        invalidate();
    }

    int widthPx() { return cell * cols(); }
    int heightPx() { return cell * rows(); }
    int cellPx() { return cell; }

    /** Где в окне стоит сам Clawd — для окна касаний и выравнивания по полю ввода. */
    Rect bodyRect() {
        return new Rect(bodyLeft() * cell, bodyTop() * cell, (bodyLeft() + bodyCols()) * cell, rows() * cell);
    }

    void setColor(int color) {
        body = color;
        shade = blend(color, 0xFF000000, style == Style.WEB ? 0.13f : 0.18f);
        eyes = luminance(color) < 0.35f ? 0xFFF2F2F2 : 0xFF000000;
        invalidate();
    }

    // ---- настроение ----

    Mood mood() { return mood == Mood.OUTRO && pending != null ? pending : mood; }

    void setMood(Mood m) {
        if (m == Mood.OUTRO) return;
        if (mood == m && pending == null) return;
        // в стиле claude.ai после печати он сначала убирает ноутбук
        if (style == Style.WEB && (mood == Mood.TYPING || mood == Mood.OUTRO) && m != Mood.TYPING) {
            pending = m;
            if (mood == Mood.TYPING) start(Mood.OUTRO);
            return;
        }
        pending = null;
        start(m);
    }

    /** Сразу в покой, без доигрывания (когда его прячут). */
    void reset() {
        pending = null;
        jumpStart = -1;
        removeCallbacks(frame);
        start(Mood.IDLE);
    }

    private void start(Mood m) {
        mood = m;
        tick = 0;
        removeCallbacks(ticker);
        if (m != Mood.IDLE) postDelayed(ticker, TICK_MS);
        invalidate();
    }

    void jump() {
        jumpStart = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        postOnAnimation(frame);
    }

    /** Ответ готов: прыжок с поднятыми руками или короткий танец. */
    void celebrate() {
        setMood(random.nextInt(10) < 6 ? Mood.HAPPY : Mood.DANCE);
        if (mood == Mood.HAPPY) jump();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick++;
            if (mood == Mood.OUTRO && tick >= WebFrames.OUTRO.length) {
                Mood next = pending != null ? pending : Mood.IDLE;
                pending = null;
                start(next);
                if (next == Mood.HAPPY) jump();
                return;
            }
            if ((mood == Mood.HAPPY && tick >= HAPPY_TICKS) || (mood == Mood.DANCE && tick >= DANCE_TICKS)) {
                start(Mood.IDLE);
                return;
            }
            invalidate();
            postDelayed(this, TICK_MS);
        }
    };

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            invalidate();
            if (jumpStart >= 0 && SystemClock.uptimeMillis() - jumpStart < JUMP_MS) {
                postOnAnimation(this);
            } else {
                jumpStart = -1;
                invalidate();
            }
        }
    };

    private final Runnable blink = new Runnable() {
        @Override public void run() {
            blinking = true;
            invalidate();
            postDelayed(unblink, 110);
        }
    };

    private final Runnable unblink = new Runnable() {
        @Override public void run() {
            blinking = false;
            invalidate();
            scheduleBlink();
        }
    };

    private final Runnable glance = new Runnable() {
        @Override public void run() {
            if (mood == Mood.IDLE) {
                look = random.nextBoolean() ? 1 : -1;
                invalidate();
                postDelayed(unglance, 700 + random.nextInt(700));
            } else {
                scheduleGlance();
            }
        }
    };

    private final Runnable unglance = new Runnable() {
        @Override public void run() {
            look = 0;
            invalidate();
            scheduleGlance();
        }
    };

    private void scheduleBlink() {
        removeCallbacks(blink);
        postDelayed(blink, 2500 + random.nextInt(3000));
    }

    private void scheduleGlance() {
        removeCallbacks(glance);
        postDelayed(glance, 4000 + random.nextInt(6000));
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(widthPx(), heightPx());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleBlink();
        scheduleGlance();
        if (mood != Mood.IDLE) postDelayed(ticker, TICK_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blink);
        removeCallbacks(unblink);
        removeCallbacks(glance);
        removeCallbacks(unglance);
        removeCallbacks(ticker);
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    // ---- рисование ----

    private int ox, oy;

    /** 0..1 — фаза прыжка, -1 — не прыгает. */
    private float jumpPhase() {
        if (jumpStart < 0) return -1;
        return Math.min(1f, (SystemClock.uptimeMillis() - jumpStart) / (float) JUMP_MS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (style == Style.WEB) drawWeb(canvas);
        else if (style == Style.SQUARE) drawSquare(canvas);
        else drawClassic(canvas);
    }

    // claude.ai

    private void drawWeb(Canvas c) {
        float t = jumpPhase();
        int lift = t < 0 ? 0 : Math.round((float) Math.sin(Math.PI * t) * 6f);
        ox = 0;
        oy = WEB_HEAD - lift;
        String[] f;
        boolean mirror = false;
        switch (mood) {
            case TYPING:
                f = tick < WebFrames.INTRO.length ? WebFrames.INTRO[tick]
                        : WebFrames.TYPING[(tick - WebFrames.INTRO.length) % WebFrames.TYPING.length];
                break;
            case OUTRO:
                f = WebFrames.OUTRO[Math.min(tick, WebFrames.OUTRO.length - 1)];
                break;
            case HAPPY:
                f = WebFrames.HAPPY;
                break;
            case DANCE:
                f = WebFrames.DANCE;
                mirror = (tick / 2) % 2 == 1;
                break;
            default:
                f = WebFrames.STAND;
        }
        drawMap(c, f, mirror);
        if (mood == Mood.IDLE && (blinking || look != 0)) {
            int r = WebFrames.EYE_TOP;
            rect(c, WebFrames.EYE_L, r, WebFrames.EYE_L + 1, r + 1, body);
            rect(c, WebFrames.EYE_R, r, WebFrames.EYE_R + 1, r + 1, body);
            if (blinking) {
                rect(c, WebFrames.EYE_L, r + 1, WebFrames.EYE_L + 1, r + 1, eyes);
                rect(c, WebFrames.EYE_R, r + 1, WebFrames.EYE_R + 1, r + 1, eyes);
            } else {
                rect(c, WebFrames.EYE_L + look, r, WebFrames.EYE_L + 1 + look, r + 1, eyes);
                rect(c, WebFrames.EYE_R + look, r, WebFrames.EYE_R + 1 + look, r + 1, eyes);
            }
        }
    }

    /** Рисует кадр строками: подряд идущие одинаковые пиксели — одним прямоугольником. */
    private void drawMap(Canvas c, String[] map, boolean mirror) {
        for (int y = 0; y < map.length; y++) {
            String row = map[y];
            int x = 0;
            while (x < row.length()) {
                char ch = row.charAt(x);
                int end = x + 1;
                while (end < row.length() && row.charAt(end) == ch) end++;
                int color = colorOf(ch);
                if (color != 0) {
                    if (mirror) rect(c, 43 - (end - 1), y, 43 - x, y, color);
                    else rect(c, x, y, end - 1, y, color);
                }
                x = end;
            }
        }
    }

    private int colorOf(char ch) {
        switch (ch) {
            case 'O': return body;
            case 'D': return shade;
            case 'E': return eyes;
            case 'G': return WEB_LAPTOP;
            default: return 0;
        }
    }

    // квадратный (0.2)

    private void drawSquare(Canvas c) {
        float t = jumpPhase();
        int lift = t < 0 ? 0 : Math.round((float) Math.sin(Math.PI * t) * 3.5f);
        boolean squat = t >= 0 && (t < 0.1f || t > 0.92f);
        boolean dance = mood == Mood.DANCE;
        int danceFrame = tick / 2;
        ox = 1 + (dance ? (danceFrame % 2 == 0 ? -1 : 1) : 0);
        oy = 6 - lift;
        if (mood == Mood.TYPING) {
            boolean f = (tick / 2) % 2 == 0;
            rect(c, 2, 0, 9, 5, body);
            rect(c, 2, 6, 2, 6, body);
            rect(c, 9, 6, 9, 6, body);
            if (!blinking) {
                px(c, 3, 2, eyes);
                px(c, 8, 2, eyes);
            }
            rect(c, 0, f ? 4 : 5, 1, f ? 4 : 5, body);
            rect(c, 10, f ? 5 : 4, 11, f ? 5 : 4, body);
            rect(c, 3, 3, 8, 6, LAPTOP);
            rect(c, 5, 4, 6, 4, LAPTOP_LOGO);
            rect(c, 1, 7, 10, 7, LAPTOP_BASE);
            return;
        }
        boolean happy = mood == Mood.HAPPY;
        boolean armsUp = happy || (dance && danceFrame % 2 == 0);
        int s = squat ? 1 : 0;
        rect(c, 2, s, 9, 5 + s, body);
        if (armsUp) {
            rect(c, 1, -2 + s, 1, 1 + s, body);
            rect(c, 10, -2 + s, 10, 1 + s, body);
        } else {
            rect(c, 0, 2 + s, 1, 3 + s, body);
            rect(c, 10, 2 + s, 11, 3 + s, body);
        }
        int[] legs = {2, 4, 7, 9};
        for (int i = 0; i < legs.length; i++) {
            boolean shortLeg = squat || (dance && (danceFrame % 2 == 0) == (i < 2));
            rect(c, legs[i], shortLeg ? 7 : 6, legs[i], 7, body);
        }
        if (happy) {
            px(c, 2, 2 + s, eyes); px(c, 3, 1 + s, eyes); px(c, 4, 2 + s, eyes);
            px(c, 7, 2 + s, eyes); px(c, 8, 1 + s, eyes); px(c, 9, 2 + s, eyes);
        } else if (!blinking) {
            int l = dance ? 0 : look;
            px(c, 3 + l, 1 + s, eyes);
            px(c, 8 + l, 1 + s, eyes);
        }
    }

    // первый (0.1)

    private void drawClassic(Canvas c) {
        float t = jumpPhase();
        int lift = t < 0 ? 0 : Math.round((float) Math.sin(Math.PI * t) * 3.2f);
        boolean squat = t >= 0 && (t < 0.12f || t > 0.9f);
        ox = 0;
        oy = 4 - lift + (squat ? 1 : 0);
        boolean typing = mood == Mood.TYPING;
        boolean happy = mood == Mood.HAPPY || mood == Mood.DANCE;
        boolean f = (tick / 2) % 2 == 0;

        int[] legs = typing ? new int[] {3, 12} : new int[] {3, 5, 10, 12};
        for (int col : legs) rect(c, col, 8, col, squat ? 8 : 9, body);
        rect(c, 2, 1, 13, 6, body);
        rect(c, 2, 7, 13, 7, shade);

        if (happy) {
            rect(c, 0, 0, 1, 1, body);
            rect(c, 1, 2, 1, 3, body);
            rect(c, 14, 0, 15, 1, body);
            rect(c, 14, 2, 14, 3, body);
        } else if (typing) {
            int l = f ? 5 : 6, r = f ? 6 : 5;
            rect(c, 0, l, 1, l + 1, body);
            rect(c, 14, r, 15, r + 1, body);
        } else {
            rect(c, 0, 4, 1, 5, body);
            rect(c, 14, 4, 15, 5, body);
        }

        if (happy) {
            px(c, 4, 4, eyes); px(c, 5, 3, eyes); px(c, 6, 4, eyes);
            px(c, 9, 4, eyes); px(c, 10, 3, eyes); px(c, 11, 4, eyes);
        } else if (typing || blinking) {
            px(c, 5, 4, eyes);
            px(c, 10, 4, eyes);
        } else {
            rect(c, 5 + look, 3, 5 + look, 4, eyes);
            rect(c, 10 + look, 3, 10 + look, 4, eyes);
        }

        if (typing) {
            rect(c, 4, 5, 11, 8, LAPTOP);
            rect(c, 7, 6, 8, 7, LAPTOP_LOGO);
            rect(c, 3, 9, 12, 9, LAPTOP_BASE);
        }
    }

    // ---- примитивы ----

    private void rect(Canvas c, int c0, int r0, int c1, int r1, int color) {
        paint.setColor(color);
        c.drawRect((c0 + ox) * cell, (r0 + oy) * cell, (c1 + 1 + ox) * cell, (r1 + 1 + oy) * cell, paint);
    }

    private void px(Canvas c, int col, int row, int color) {
        rect(c, col, row, col, row, color);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }
}
