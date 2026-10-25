package io.github.yakstork.clawdbuddy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Пиксельный Clawd, нарисованный кодом (свой рисунок, без ресурсов приложения Claude).
 * Сетка 16×10 клеток плюс 4 клетки запаса сверху под прыжок. Кадры перерисовываются только
 * пока что-то двигается: моргание и взгляд — редкие отложенные задачи, а не постоянный цикл.
 */
final class ClawdView extends View {
    static final int COLS = 16;
    static final int SPRITE_ROWS = 10;
    static final int HEADROOM = 4;
    static final int ROWS = SPRITE_ROWS + HEADROOM;

    enum Mood { IDLE, TYPING, HAPPY }

    private static final long JUMP_MS = 420;
    private static final long TYPE_FRAME_MS = 140;
    private static final long HAPPY_MS = 1600;

    private final Paint paint = new Paint();
    private final Random random = new Random();
    private float cell;
    private int body, shade, eyes;

    private Mood mood = Mood.IDLE;
    private int look; // -1 влево, 0 прямо, 1 вправо
    private boolean blinking;
    private boolean typeFrame;
    private long jumpStart = -1;

    ClawdView(Context c) {
        super(c);
        paint.setAntiAlias(false);
        setColor(0xFFD97757);
        setSizeDp(52);
    }

    void setSizeDp(int widthDp) {
        cell = widthDp * getResources().getDisplayMetrics().density / COLS;
        requestLayout();
        invalidate();
    }

    int widthPx() { return Math.round(cell * COLS); }
    int heightPx() { return Math.round(cell * ROWS); }
    /** Сколько пикселей окна занимает запас под прыжок над головой. */
    float cellPx() { return cell; }

    void setColor(int color) {
        body = color;
        shade = blend(color, 0xFF000000, 0.18f);
        eyes = luminance(color) < 0.35f ? 0xFFF2F2F2 : 0xFF1A1A1A;
        invalidate();
    }

    Mood mood() { return mood; }

    void setMood(Mood m) {
        if (mood == m) return;
        mood = m;
        removeCallbacks(typeTick);
        removeCallbacks(backToIdle);
        if (m == Mood.TYPING) post(typeTick);
        if (m == Mood.HAPPY) postDelayed(backToIdle, HAPPY_MS);
        invalidate();
    }

    void jump() {
        jumpStart = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        postOnAnimation(frame);
    }

    void celebrate() {
        setMood(Mood.HAPPY);
        jump();
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
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blink);
        removeCallbacks(unblink);
        removeCallbacks(glance);
        removeCallbacks(unglance);
        removeCallbacks(typeTick);
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
            if (mood == Mood.TYPING) postDelayed(this, TYPE_FRAME_MS + random.nextInt(60));
        }
    };

    private final Runnable backToIdle = new Runnable() {
        @Override public void run() { setMood(Mood.IDLE); }
    };

    private final Runnable blink = new Runnable() {
        @Override public void run() {
            blinking = true;
            invalidate();
            postDelayed(unblink, 120);
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
                postDelayed(unglance, 700 + random.nextInt(600));
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
        postDelayed(blink, 2500 + random.nextInt(2500));
    }

    private void scheduleGlance() {
        removeCallbacks(glance);
        postDelayed(glance, 4000 + random.nextInt(5000));
    }

    // ---- рисование ----

    @Override
    protected void onDraw(Canvas canvas) {
        float lift = 0, squash = 1;
        if (jumpStart >= 0) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - jumpStart) / (float) JUMP_MS);
            lift = (float) Math.sin(Math.PI * t) * 3.2f * cell;
            if (t < 0.12f || t > 0.9f) squash = 0.9f; // присел перед прыжком и при приземлении
        }
        canvas.save();
        float bottom = ROWS * cell;
        canvas.scale(1f, squash, COLS * cell / 2f, bottom);
        canvas.translate(0, HEADROOM * cell - lift);

        boolean typing = mood == Mood.TYPING;
        boolean happy = mood == Mood.HAPPY;

        // ноги
        int[] legs = typing ? new int[] {3, 12} : new int[] {3, 5, 10, 12};
        for (int c : legs) rect(canvas, c, 8, c, 9, body);
        // тело
        rect(canvas, 2, 1, 13, 6, body);
        rect(canvas, 2, 7, 13, 7, shade);

        // руки
        if (happy) {
            rect(canvas, 0, 0, 1, 1, body);
            rect(canvas, 1, 2, 1, 3, body);
            rect(canvas, 14, 0, 15, 1, body);
            rect(canvas, 14, 2, 14, 3, body);
        } else if (typing) {
            int l = typeFrame ? 5 : 6, r = typeFrame ? 6 : 5;
            rect(canvas, 0, l, 1, l + 1, body);
            rect(canvas, 14, r, 15, r + 1, body);
        } else {
            rect(canvas, 0, 4, 1, 5, body);
            rect(canvas, 14, 4, 15, 5, body);
        }

        // глаза
        if (happy) {
            px(canvas, 4, 4, eyes); px(canvas, 5, 3, eyes); px(canvas, 6, 4, eyes);
            px(canvas, 9, 4, eyes); px(canvas, 10, 3, eyes); px(canvas, 11, 4, eyes);
        } else if (typing || blinking) {
            px(canvas, 5, 4, eyes);
            px(canvas, 10, 4, eyes);
        } else {
            rect(canvas, 5 + look, 3, 5 + look, 4, eyes);
            rect(canvas, 10 + look, 3, 10 + look, 4, eyes);
        }

        // ноутбук: крышка к нам задней стороной, под ней клавиатура
        if (typing) {
            rect(canvas, 4, 5, 11, 8, 0xFFC5C9CF);
            rect(canvas, 7, 6, 8, 7, 0xFFE9EBEE);
            rect(canvas, 3, 9, 12, 9, 0xFF8A8F96);
        }
        canvas.restore();
    }

    private void rect(Canvas c, int c0, int r0, int c1, int r1, int color) {
        paint.setColor(color);
        c.drawRect(c0 * cell, r0 * cell, (c1 + 1) * cell, (r1 + 1) * cell, paint);
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
