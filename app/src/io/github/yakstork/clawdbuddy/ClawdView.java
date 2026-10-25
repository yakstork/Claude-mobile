package io.github.yakstork.clawdbuddy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Пиксельный Clawd в пропорциях веб-версии claude.ai: тело 8×6 клеток, ручки 2×2 по бокам,
 * глаза-квадратики 1×1, четыре ножки по 2 клетки. Рисунок свой, кодом. Сетка 14×8 (клетка запаса
 * слева и справа — для танца) плюс 6 клеток сверху под прыжок и поднятые руки. Клетка — целое число пикселей,
 * чтобы края были чёткими. Кадры перерисовываются только пока что-то двигается.
 */
final class ClawdView extends View {
    static final int BODY_COLS = 12;
    static final int COLS = BODY_COLS + 2;
    static final int SPRITE_ROWS = 8;
    static final int HEADROOM = 6;
    static final int ROWS = SPRITE_ROWS + HEADROOM;

    enum Mood { IDLE, TYPING, HAPPY, DANCE }

    private static final long JUMP_MS = 440;
    private static final long TYPE_FRAME_MS = 130;
    private static final long DANCE_FRAME_MS = 170;
    private static final int DANCE_FRAMES = 10;
    private static final long HAPPY_MS = 1400;

    private static final int LAPTOP = 0xFFC5C9CF;
    private static final int LAPTOP_LOGO = 0xFFE9EBEE;
    private static final int LAPTOP_BASE = 0xFF8A8F96;

    private final Paint paint = new Paint();
    private final Random random = new Random();
    private int cell = 1;
    private int body, eyes;

    private Mood mood = Mood.IDLE;
    private int look; // -1 влево, 0 прямо, 1 вправо
    private boolean blinking;
    private boolean typeFrame;
    private int danceFrame;
    private long jumpStart = -1;

    ClawdView(Context c) {
        super(c);
        paint.setAntiAlias(false);
        setColor(0xFFD97757);
        setSizeDp(42);
    }

    /** Ширина самого Clawd (без запаса по бокам) в dp. */
    void setSizeDp(int widthDp) {
        int c = Math.max(1, Math.round(widthDp * getResources().getDisplayMetrics().density / BODY_COLS));
        if (c == cell) return;
        cell = c;
        requestLayout();
        invalidate();
    }

    int widthPx() { return cell * COLS; }
    int heightPx() { return cell * ROWS; }
    int cellPx() { return cell; }

    void setColor(int color) {
        body = color;
        eyes = luminance(color) < 0.35f ? 0xFFF2F2F2 : 0xFF000000;
        invalidate();
    }

    Mood mood() { return mood; }

    void setMood(Mood m) {
        if (mood == m) return;
        mood = m;
        removeCallbacks(typeTick);
        removeCallbacks(danceTick);
        removeCallbacks(backToIdle);
        if (m == Mood.TYPING) post(typeTick);
        if (m == Mood.HAPPY) postDelayed(backToIdle, HAPPY_MS);
        if (m == Mood.DANCE) {
            danceFrame = 0;
            post(danceTick);
        }
        invalidate();
    }

    void jump() {
        jumpStart = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        postOnAnimation(frame);
    }

    /** Ответ готов: прыжок с поднятыми руками или короткий танец. */
    void celebrate() {
        if (random.nextInt(10) < 6) {
            setMood(Mood.HAPPY);
            jump();
        } else {
            setMood(Mood.DANCE);
        }
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
        if (mood == Mood.TYPING) post(typeTick);
        if (mood == Mood.DANCE) post(danceTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blink);
        removeCallbacks(unblink);
        removeCallbacks(glance);
        removeCallbacks(unglance);
        removeCallbacks(typeTick);
        removeCallbacks(danceTick);
        removeCallbacks(frame);
        removeCallbacks(backToIdle);
        super.onDetachedFromWindow();
    }

    // ---- анимации ----

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

    private final Runnable typeTick = new Runnable() {
        @Override public void run() {
            typeFrame = !typeFrame;
            invalidate();
            if (mood == Mood.TYPING) postDelayed(this, TYPE_FRAME_MS + random.nextInt(70));
        }
    };

    private final Runnable danceTick = new Runnable() {
        @Override public void run() {
            if (mood != Mood.DANCE) return;
            if (++danceFrame >= DANCE_FRAMES) {
                setMood(Mood.IDLE);
                return;
            }
            invalidate();
            postDelayed(this, DANCE_FRAME_MS);
        }
    };

    private final Runnable backToIdle = new Runnable() {
        @Override public void run() { setMood(Mood.IDLE); }
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

    // ---- рисование ----

    private int ox, oy;

    @Override
    protected void onDraw(Canvas canvas) {
        int lift = 0;
        boolean squat = false;
        if (jumpStart >= 0) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - jumpStart) / (float) JUMP_MS);
            lift = Math.round((float) Math.sin(Math.PI * t) * 3.5f);
            squat = t < 0.1f || t > 0.92f;
        }
        boolean dance = mood == Mood.DANCE;
        ox = 1 + (dance ? (danceFrame % 2 == 0 ? -1 : 1) : 0);
        oy = HEADROOM - lift;

        if (mood == Mood.TYPING) drawTyping(canvas);
        else drawStanding(canvas, squat, dance);
    }

    private void drawStanding(Canvas c, boolean squat, boolean dance) {
        boolean happy = mood == Mood.HAPPY;
        boolean armsUp = happy || (dance && danceFrame % 2 == 0);
        // при приседании тело опускается на клетку, ножки короче
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
            // в танце ножки переступают: то левая пара короче, то правая
            boolean short_ = squat || (dance && (danceFrame % 2 == 0) == (i < 2));
            rect(c, legs[i], short_ ? 7 : 6, legs[i], 7, body);
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

    /** Сидит за ноутбуком: крышка к нам задней стороной, глаза в экран, руки по очереди стучат. */
    private void drawTyping(Canvas c) {
        rect(c, 2, 0, 9, 5, body);
        rect(c, 2, 6, 2, 6, body);
        rect(c, 9, 6, 9, 6, body);
        if (!blinking) {
            px(c, 3, 2, eyes);
            px(c, 8, 2, eyes);
        }
        rect(c, 0, typeFrame ? 4 : 5, 1, typeFrame ? 4 : 5, body);
        rect(c, 10, typeFrame ? 5 : 4, 11, typeFrame ? 5 : 4, body);
        rect(c, 3, 3, 8, 6, LAPTOP);
        rect(c, 5, 4, 6, 4, LAPTOP_LOGO);
        rect(c, 1, 7, 10, 7, LAPTOP_BASE);
    }

    private void rect(Canvas c, int c0, int r0, int c1, int r1, int color) {
        paint.setColor(color);
        c.drawRect((c0 + ox) * cell, (r0 + oy) * cell, (c1 + 1 + ox) * cell, (r1 + 1 + oy) * cell, paint);
    }

    private void px(Canvas c, int col, int row, int color) {
        rect(c, col, row, col, row, color);
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }
}
