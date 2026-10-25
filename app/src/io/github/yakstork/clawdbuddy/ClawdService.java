package io.github.yakstork.clawdbuddy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Следит только за окнами официального Claude и держит Clawd над его полем ввода в отдельном
 * окне поверх. В само приложение ничего не внедряется, его запросы не трогаются.
 *
 * Логика задержек:
 *  - смена окон приходит событием сразу; после неё ~0,6 с проверяем часто (клавиатура едет);
 *  - пока Claude открыт и ничего не меняется — редкая проверка раз в 700 мс;
 *  - появляется Clawd только когда окно Claude открыто ≥ 250 мс и поле ввода стоит на месте,
 *    плавно; исчезает мгновенно.
 */
public final class ClawdService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String CLAUDE = "com.anthropic.claude";

    /** Кнопка остановки ответа: англ. «Stop response» / «Stop», русская локаль — «Остановить ответ» / «Стоп». */
    private static final Pattern STOP = Pattern.compile(
            "(?i)^\\s*(stop( response| generating| responding)?|остановить( ответ)?|стоп)\\s*$");

    private static final long OPEN_SETTLE_MS = 250;
    private static final long BURST_MS = 600;
    private static final long BURST_STEP_MS = 90;
    private static final long IDLE_POLL_MS = 700;
    private static final long CONTENT_THROTTLE_MS = 80;
    private static final long STOP_CHECK_MS = 220;

    static volatile ClawdService instance;
    /** Последнее состояние — для экрана диагностики. */
    static volatile String status = "служба запущена, Claude не открыт";
    /** Последний отчёт об области поля ввода (снимается, пока Clawd виден) — для кнопки «Скопировать отчёт». */
    static volatile String lastReport;
    private long lastReportAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager wm;
    private ClawdView clawd;
    private WindowManager.LayoutParams lp;
    private Vibrator vibrator;

    private boolean claudeOnTop;
    private long claudeOnTopSince;
    private boolean shown;
    private boolean contentEvents;
    private long burstUntil;
    private long pendingAt = Long.MAX_VALUE;

    private AccessibilityNodeInfo editNode;
    private AccessibilityNodeInfo boxNode;
    private final Rect box = new Rect();
    private final Rect lastBox = new Rect();

    private boolean generating;
    private int notGenerating;
    private long lastStopCheck;
    private AccessibilityNodeInfo stopNode;

    private ValueAnimator mover;
    private boolean dragging;

    // ---- жизненный цикл ----

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        clawd = new ClawdView(this);
        applyLook();
        clawd.setVisibility(View.GONE);
        clawd.setOnTouchListener(new DragOrTap());

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle("Clawd");
        wm.addView(clawd, lp);
        schedule(0);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (clawd != null && clawd.isAttachedToWindow()) wm.removeViewImmediate(clawd);
        status = "служба выключена";
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        applyLook();
        if (shown) place(false);
        schedule(0);
    }

    private void applyLook() {
        clawd.setSizeDp(Prefs.SIZES_DP[Math.max(0, Math.min(2, Prefs.size(prefs)))]);
        clawd.setColor(Prefs.color(prefs));
        if (!Prefs.typing(prefs) && clawd.mood() == ClawdView.Mood.TYPING) {
            clawd.setMood(ClawdView.Mood.IDLE);
        }
    }

    // ---- события ----

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = e.getPackageName();
            // Уход из Claude прячем сразу, не дожидаясь проверки.
            if (shown && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && pkg != null && !CLAUDE.contentEquals(pkg) && !isImeOrSystemUi(pkg)) {
                hide("открыто другое приложение");
            }
            burstUntil = SystemClock.uptimeMillis() + BURST_MS;
            schedule(0);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            CharSequence pkg = e.getPackageName();
            if (pkg != null && CLAUDE.contentEquals(pkg)) schedule(CONTENT_THROTTLE_MS);
        }
    }

    private static boolean isImeOrSystemUi(CharSequence pkg) {
        String p = pkg.toString();
        return p.equals("com.android.systemui") || p.contains("inputmethod") || p.contains("keyboard")
                || p.equals("com.google.android.inputmethod.latin");
    }

    /** События содержимого нужны только пока открыт Claude — иначе служба не грузит другие приложения. */
    private void setContentEvents(boolean on) {
        if (contentEvents == on) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int base = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.eventTypes = on ? base | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED : base;
        setServiceInfo(info);
        contentEvents = on;
    }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            pendingAt = Long.MAX_VALUE;
            try {
                tick();
            } catch (RuntimeException ex) {
                status = "ошибка: " + ex;
                hide(null);
            }
        }
    };

    private void schedule(long delay) {
        long at = SystemClock.uptimeMillis() + delay;
        if (at >= pendingAt) return;
        handler.removeCallbacks(check);
        pendingAt = at;
        handler.postAtTime(check, at);
    }

    // ---- основная проверка ----

    private void tick() {
        long now = SystemClock.uptimeMillis();
        if (!Prefs.enabled(prefs)) {
            hide("выключен в настройках");
            setContentEvents(false);
            return;
        }

        AccessibilityNodeInfo root = claudeRootOnTop();
        if (root == null) {
            if (claudeOnTop) {
                claudeOnTop = false;
                editNode = null;
                boxNode = null;
                stopNode = null;
            }
            hide("Claude не на экране");
            setContentEvents(false);
            return;
        }
        if (!claudeOnTop) {
            claudeOnTop = true;
            claudeOnTopSince = now;
            burstUntil = now + BURST_MS;
        }
        setContentEvents(true);

        if (!findComposer(root)) {
            hide("Claude открыт, поля ввода на экране нет");
            next(now, false);
            return;
        }
        boxNode.getBoundsInScreen(box);
        if (box.isEmpty()) {
            hide("поле ввода скрыто");
            next(now, false);
            return;
        }

        if (now - lastStopCheck >= STOP_CHECK_MS) {
            lastStopCheck = now;
            updateGenerating();
        }

        boolean stable = box.equals(lastBox);
        lastBox.set(box);
        if (!shown) {
            if (now - claudeOnTopSince >= OPEN_SETTLE_MS && stable) {
                show();
            }
        } else if (!dragging) {
            place(true);
        }
        if (shown && now - lastReportAt > 5000) {
            lastReportAt = now;
            lastReport = report(root);
        }
        status = "Claude открыт · поле ввода " + box.toShortString()
                + (generating ? " · отвечает" : "") + (shown ? " · Clawd виден" : " · жду, пока всё встанет");
        next(now, !shown);
    }

    private void next(long now, boolean waitingToShow) {
        if (now < burstUntil || waitingToShow) schedule(BURST_STEP_MS);
        else if (generating) schedule(STOP_CHECK_MS);
        else if (claudeOnTop) schedule(IDLE_POLL_MS);
    }

    /** Корень Claude, если его окно — верхнее окно приложения и его не закрывает шторка или «Недавние». */
    private AccessibilityNodeInfo claudeRootOnTop() {
        List<AccessibilityWindowInfo> windows = new ArrayList<AccessibilityWindowInfo>(getWindows());
        Collections.sort(windows, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                continue;
            }
            if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                w.getBoundsInScreen(r);
                // строка состояния и панель навигации маленькие; шторка и «Недавние» — во весь экран
                if (r.height() > dm.heightPixels / 3) return null;
                continue;
            }
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) return null;
                CharSequence pkg = root.getPackageName();
                return pkg != null && CLAUDE.contentEquals(pkg) ? root : null;
            }
        }
        return null;
    }

    /**
     * Находит поле ввода: нижнее редактируемое поле окна. Список сообщений (прокручиваемый) не
     * обходим — там поля ввода нет, а сообщений может быть много. Найденный узел кешируется и
     * дальше только обновляется.
     */
    private boolean findComposer(AccessibilityNodeInfo root) {
        if (editNode != null && editNode.refresh() && editNode.isVisibleToUser()
                && boxNode != null && boxNode.refresh()) {
            return true;
        }
        editNode = null;
        boxNode = null;
        stopNode = null;
        AccessibilityNodeInfo best = null;
        int bestBottom = -1;
        Rect r = new Rect();
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<AccessibilityNodeInfo>();
        queue.add(root);
        for (int i = 0; i < queue.size() && i < 600; i++) {
            AccessibilityNodeInfo n = queue.get(i);
            if (n == null) continue;
            if (n.isEditable() && n.isVisibleToUser()) {
                n.getBoundsInScreen(r);
                if (r.bottom > bestBottom) {
                    best = n;
                    bestBottom = r.bottom;
                }
                continue;
            }
            if (n.isScrollable() && n != root) continue;
            for (int c = 0; c < n.getChildCount(); c++) queue.add(n.getChild(c));
        }
        if (best == null) return false;
        editNode = best;
        boxNode = composerBox(best);
        return true;
    }

    /**
     * Рамка поля ввода — ближайший предок текстового поля, похожий на «коробку» композера:
     * не выше трети экрана. Clawd стоит на её верхней кромке.
     */
    private AccessibilityNodeInfo composerBox(AccessibilityNodeInfo edit) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxH = dm.heightPixels / 3;
        // рамка выступает над текстом на отступ, а не на целую панель
        int maxAbove = Math.round(40 * dm.density);
        Rect r = new Rect();
        Rect er = new Rect();
        edit.getBoundsInScreen(er);
        AccessibilityNodeInfo best = edit;
        AccessibilityNodeInfo p = edit.getParent();
        for (int i = 0; i < 4 && p != null; i++) {
            p.getBoundsInScreen(r);
            if (r.height() > maxH || r.top > er.top || er.top - r.top > maxAbove) break;
            best = p;
            p = p.getParent();
        }
        return best;
    }

    // ---- «Claude печатает» ----

    private void updateGenerating() {
        boolean found;
        if (stopNode != null && stopNode.refresh() && stopNode.isVisibleToUser() && isStop(stopNode)) {
            found = true;
        } else {
            stopNode = findStop(boxNode.getParent() != null ? boxNode.getParent() : boxNode);
            found = stopNode != null;
        }
        if (found) {
            notGenerating = 0;
            if (!generating) {
                generating = true;
                if (Prefs.typing(prefs) && shown) clawd.setMood(ClawdView.Mood.TYPING);
            }
        } else if (generating && ++notGenerating >= 2) {
            generating = false;
            if (Prefs.celebrate(prefs) && shown) clawd.celebrate();
            else clawd.setMood(ClawdView.Mood.IDLE);
        }
    }

    private static boolean isStop(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        CharSequence t = n.getText();
        return (d != null && STOP.matcher(d).matches()) || (t != null && STOP.matcher(t).matches());
    }

    private static AccessibilityNodeInfo findStop(AccessibilityNodeInfo from) {
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<AccessibilityNodeInfo>();
        queue.add(from);
        for (int i = 0; i < queue.size() && i < 120; i++) {
            AccessibilityNodeInfo n = queue.get(i);
            if (n == null) continue;
            if (n.isVisibleToUser() && isStop(n)) return n;
            for (int c = 0; c < n.getChildCount(); c++) queue.add(n.getChild(c));
        }
        return null;
    }

    // ---- окно Clawd ----

    private int targetX() {
        int w = clawd.widthPx();
        float f = Math.max(0f, Math.min(1f, Prefs.xFraction(prefs)));
        return box.left + Math.round(f * Math.max(0, box.width() - w));
    }

    private int targetY() {
        float density = getResources().getDisplayMetrics().density;
        // ноги чуть заходят на кромку рамки, чтобы он «стоял», а не висел
        int overlap = Math.round(clawd.cellPx() * 0.5f);
        return box.top - clawd.heightPx() + overlap - Math.round(Prefs.yOffsetDp(prefs) * density);
    }

    private void show() {
        lp.x = targetX();
        lp.y = targetY();
        wm.updateViewLayout(clawd, lp);
        clawd.setAlpha(0f);
        clawd.setVisibility(View.VISIBLE);
        clawd.animate().alpha(1f).setDuration(160).start();
        shown = true;
        if (generating && Prefs.typing(prefs)) clawd.setMood(ClawdView.Mood.TYPING);
    }

    private void hide(String why) {
        if (why != null) status = why;
        if (!shown) return;
        shown = false;
        if (mover != null) mover.cancel();
        clawd.animate().cancel();
        clawd.setVisibility(View.GONE);
        if (clawd.mood() != ClawdView.Mood.IDLE) clawd.setMood(ClawdView.Mood.IDLE);
        generating = false;
    }

    /** Плавно переезжает к поля ввода (клавиатура, многострочный текст). */
    private void place(boolean animate) {
        final int fromX = lp.x, fromY = lp.y;
        final int toX = targetX(), toY = targetY();
        if (fromX == toX && fromY == toY) return;
        if (mover != null) mover.cancel();
        if (!animate) {
            lp.x = toX;
            lp.y = toY;
            wm.updateViewLayout(clawd, lp);
            return;
        }
        mover = ValueAnimator.ofFloat(0f, 1f);
        mover.setDuration(Math.abs(toY - fromY) > clawd.heightPx() * 3 ? 180 : 120);
        mover.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                if (!shown) return;
                float t = (Float) a.getAnimatedValue();
                lp.x = Math.round(fromX + (toX - fromX) * t);
                lp.y = Math.round(fromY + (toY - fromY) * t);
                wm.updateViewLayout(clawd, lp);
            }
        });
        mover.start();
    }

    /** Тап — прыжок; перетаскивание — сдвиг вдоль поля ввода (позиция запоминается). */
    private final class DragOrTap implements View.OnTouchListener {
        private float downX;
        private int startX;
        private final int slop = ViewConfiguration.get(ClawdService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    startX = lp.x;
                    dragging = false;
                    if (mover != null) mover.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    if (!dragging && Math.abs(dx) > slop) dragging = true;
                    if (dragging) {
                        int w = clawd.widthPx();
                        lp.x = Math.max(box.left, Math.min(box.right - w, Math.round(startX + dx)));
                        wm.updateViewLayout(clawd, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int span = Math.max(1, box.width() - clawd.widthPx());
                        prefs.edit().putFloat(Prefs.X_FRACTION, (lp.x - box.left) / (float) span).apply();
                        dragging = false;
                    } else {
                        clawd.jump();
                        if (Prefs.haptics(prefs) && vibrator != null && vibrator.hasVibrator()) {
                            vibrator.vibrate(Build.VERSION.SDK_INT >= 29
                                    ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                                    : VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---- диагностика ----

    /**
     * Отчёт для настройки: узлы вокруг поля ввода (класс, описание, короткий текст, рамка).
     * Сообщения чата сюда не попадают — обходится только область поля ввода.
     */
    private String report(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder("Clawd " + Build.MANUFACTURER + " " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE + "\n" + status + "\n");
        AccessibilityNodeInfo from = boxNode.getParent() != null ? boxNode.getParent() : boxNode;
        dump(sb, from, 0);
        return sb.toString();
    }

    private void dump(StringBuilder sb, AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6 || sb.length() > 6000) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        for (int i = 0; i < depth; i++) sb.append("  ");
        CharSequence cls = n.getClassName();
        sb.append(cls == null ? "?" : cls.toString().replaceAll(".*\\.", ""));
        CharSequence d = n.getContentDescription();
        if (d != null) sb.append(" desc=\"").append(cut(d)).append('"');
        CharSequence t = n.getText();
        if (t != null) sb.append(" text=\"").append(cut(t)).append('"');
        if (n.isEditable()) sb.append(" [поле]");
        if (n == boxNode) sb.append(" [рамка]");
        sb.append(' ').append(r.toShortString()).append('\n');
        for (int c = 0; c < n.getChildCount(); c++) dump(sb, n.getChild(c), depth + 1);
    }

    private static String cut(CharSequence s) {
        String v = s.toString().replace('\n', ' ');
        return v.length() > 24 ? v.substring(0, 24) + "…" : v;
    }
}
