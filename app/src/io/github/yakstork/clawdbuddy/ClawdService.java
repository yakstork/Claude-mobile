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
 * Виден, только если Claude — приложение на переднем плане (по событиям окон приложений и
 * рабочего стола) и его окно сверху в списке окон. События скриншотера, тостов и своего окна не
 * считаются — по ним он мерцал. Уход на рабочий стол или в «Недавние» прячет его сразу.
 *
 * Окон два: картинка (не ловит касания, вся прозрачная часть пропускает нажатия в Claude) и
 * маленькое невидимое окно ровно по телу Clawd — для тапа и перетаскивания.
 *
 * Задержки: после смены окон ~0,8 с проверяем каждые 40 мс (клавиатура едет, окно открывается);
 * пока Claude открыт и ничего не меняется — раз в 400 мс. Появляется Clawd, когда окно Claude
 * открыто ≥ 150 мс и поле ввода стоит на месте; исчезает сразу.
 */
public final class ClawdService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String CLAUDE = "com.anthropic.claude";

    /** Кнопка остановки ответа: англ. «Stop response» / «Stop», русская локаль — «Остановить ответ» / «Стоп». */
    private static final Pattern STOP = Pattern.compile(
            "(?iu)^\\s*(stop( response| generating| responding)?|остановить( ответ| генерацию)?|стоп)\\s*$");

    /** Подсказка поля, пока Claude работает: «Поставьте сообщение в очередь…», «Queue a message…». */
    private static final Pattern QUEUE = Pattern.compile("(?iu)(сообщение в очередь|queue a message|queue message)");
    /** Строка состояния Code: «57 с · думает…», «1 мин 3 с · …», «Думает…», «Claude работает…», «Thinking…». */
    private static final Pattern WORKING = Pattern.compile(
            "(?iu)^\\s*((\\d+\\s*(мин|min|m)\\s*)?\\d+\\s*(с|сек|s)\\s*·"
            + "|(думает|всё ещё думает|почти додумал|claude работает|в работе|thinking|working).*…)");

    private static final long OPEN_SETTLE_MS = 150;
    private static final long BURST_MS = 800;
    private static final long BURST_STEP_MS = 40;
    private static final long IDLE_POLL_MS = 400;
    private static final long CALM_POLL_MS = 1500;
    private static final long CONTENT_THROTTLE_MS = 30;
    private static final long STOP_CHECK_MS = 200;
    /** Полный поиск поля ввода (дерево узлов) — не чаще, и только если содержимое менялось. */
    private static final long FULL_FIND_MS = 250;
    /** Спрятался и снова виден быстрее этого — без плавного появления, чтобы не мигал. */
    private static final long REAPPEAR_MS = 800;
    private static final long REPORT_MS = 3000;

    static volatile ClawdService instance;
    /** Последнее состояние — для экрана «Проверка». */
    static volatile String status = "служба запущена, Claude не открыт";
    /** Последний отчёт об области поля ввода — для кнопки «Скопировать отчёт». */
    static volatile String lastReport;
    /** Список окон на экране, обновляется раз в 3 с — часть отчёта. */
    static volatile String lastWindows = "";
    private long lastWindowsAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager wm;
    private ClawdView clawd;
    private WindowManager.LayoutParams lp;
    private View touch;
    private WindowManager.LayoutParams tlp;
    private Rect bodyRect = new Rect();
    /** Приложение на переднем плане по последнему событию окна приложения; null — ещё не знаем. */
    private String foreground;
    private String homePackage;
    /** Окна Claude сверху вниз — всплывающие подсказки и главное окно. */
    private final List<AccessibilityNodeInfo> claudeRoots = new ArrayList<AccessibilityNodeInfo>();
    /** С какого момента окно Claude сверху, хотя последним «на передний план» вышло другое приложение. */
    private long topNotForegroundSince;
    private Vibrator vibrator;
    private android.os.PowerManager power;
    /** Когда последний раз что-то менялось (события, сдвиг рамки) — для редкого опроса в покое. */
    private long lastActivity;

    private boolean claudeOnTop;
    private long claudeOnTopSince;
    private boolean shown;
    private long hiddenAt;
    private boolean contentEvents;
    private boolean contentDirty;
    private long burstUntil;
    private long pendingAt = Long.MAX_VALUE;

    /** Текстовое поле композера и насколько рамка композера выступает за него (вложения сверху и т. п.). */
    private AccessibilityNodeInfo editNode;
    private int aboveEdit, leftOfEdit, rightOfEdit;
    private long lastFullFind;
    private long lastReportAt;
    private final Rect edit = new Rect();
    private final Rect box = new Rect();
    private final Rect lastBox = new Rect();
    private int unstable;

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
        power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        registerReceiver(screenOn, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON));

        android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME), 0);
        homePackage = home != null && home.activityInfo != null ? home.activityInfo.packageName : null;

        clawd = new ClawdView(this);
        clawd.setVisibility(View.GONE);
        touch = new View(this);
        touch.setVisibility(View.GONE);
        touch.setOnTouchListener(new DragOrTap());

        lp = overlayParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.setTitle("Clawd");
        // пока Clawd не виден, окно касаний ничего не ловит
        tlp = overlayParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        tlp.setTitle("Clawd touch");
        applyLook();
        wm.addView(clawd, lp);
        wm.addView(touch, tlp);
        schedule(0);
    }

    private WindowManager.LayoutParams overlayParams(int extraFlags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | extraFlags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    /** Экран включился — проверить сразу, даже если окна не поменялись (нет экрана блокировки). */
    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            burstUntil = SystemClock.uptimeMillis() + BURST_MS;
            contentDirty = true;
            schedule(0);
        }
    };

    @Override
    public void onDestroy() {
        instance = null;
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
            // не успели зарегистрировать
        }
        handler.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (clawd != null && clawd.isAttachedToWindow()) wm.removeViewImmediate(clawd);
        if (touch != null && touch.isAttachedToWindow()) wm.removeViewImmediate(touch);
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
        clawd.setStyle(Prefs.style(prefs));
        clawd.setSizeDp(Prefs.SIZES_DP[Math.max(0, Math.min(2, Prefs.size(prefs)))]);
        clawd.setColor(Prefs.color(prefs));
        bodyRect = clawd.bodyRect();
        tlp.width = bodyRect.width();
        tlp.height = bodyRect.height();
        if (touch.isAttachedToWindow()) wm.updateViewLayout(touch, tlp);
        if (!Prefs.typing(prefs) && clawd.mood() == ClawdView.Mood.TYPING) {
            clawd.setMood(ClawdView.Mood.IDLE);
        }
    }

    // ---- события ----

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg != null && getPackageName().contentEquals(pkg)) return; // своё окно
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            trackForeground(e, pkg.toString());
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastActivity = SystemClock.uptimeMillis();
            burstUntil = lastActivity + BURST_MS;
            contentDirty = true;
            schedule(0);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (pkg != null && CLAUDE.contentEquals(pkg)) {
                lastActivity = SystemClock.uptimeMillis();
                contentDirty = true;
                schedule(CONTENT_THROTTLE_MS);
            }
        }
    }

    /**
     * Какое приложение на переднем плане. Считаются только окна приложений и рабочий стол
     * («Недавние» — это тоже он): скриншотер, шторка и тосты — системные окна, их пропускаем.
     */
    private void trackForeground(AccessibilityEvent e, String pkg) {
        String next = null;
        if (pkg.equals(CLAUDE) || pkg.equals(homePackage)) {
            next = pkg;
        } else {
            int id = e.getWindowId();
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getId() == id) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    // превью скриншота и прочие плавающие окна приложений — не уход из Claude
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && coversScreen(r)) next = pkg;
                    break;
                }
            }
        }
        if (next == null) return;
        foreground = next;
        if (!CLAUDE.equals(next)) hide("на переднем плане " + next);
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
        if (power != null && !power.isInteractive()) {
            // экран погас: ничего не проверяем, разбудят события окон при включении
            hide("экран выключен");
            setContentEvents(false);
            claudeOnTop = false;
            return;
        }
        if (!Prefs.enabled(prefs)) {
            hide("выключен в настройках");
            setContentEvents(false);
            return;
        }

        AccessibilityNodeInfo root = claudeRootOnTop();
        if (root != null && foreground != null && !CLAUDE.equals(foreground)) {
            // Чужое окно (например, системный диалог) закрылось, а Claude события не прислал:
            // если Claude сверху уже 0,6 с, значит он и на переднем плане. Кроме рабочего стола —
            // в «Недавних» окно Claude может числиться сверху.
            if (foreground.equals(homePackage)) {
                topNotForegroundSince = 0;
                root = null;
            } else {
                if (topNotForegroundSince == 0) topNotForegroundSince = now;
                if (now - topNotForegroundSince >= 600) {
                    foreground = CLAUDE;
                } else {
                    root = null;
                    schedule(300);
                }
            }
        } else {
            topNotForegroundSince = 0;
        }
        if (root == null) {
            if (claudeOnTop) {
                claudeOnTop = false;
                editNode = null;
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

        if (!findComposer(root, now)) {
            hide("Claude открыт, поля ввода на экране нет");
            next(now, false);
            return;
        }
        editNode.getBoundsInScreen(edit);
        box.set(edit.left - leftOfEdit, edit.top - aboveEdit, edit.right + rightOfEdit, edit.bottom);
        if (edit.isEmpty()) {
            hide("поле ввода скрыто");
            next(now, false);
            return;
        }

        if (now - lastStopCheck >= STOP_CHECK_MS) {
            lastStopCheck = now;
            updateGenerating();
        }

        int tol = 3;
        boolean stable = Math.abs(box.left - lastBox.left) <= tol && Math.abs(box.top - lastBox.top) <= tol
                && Math.abs(box.right - lastBox.right) <= tol;
        lastBox.set(box);
        if (!stable) lastActivity = now;
        unstable = stable ? 0 : unstable + 1;
        if (!shown) {
            // не ждём бесконечно, если что-то рядом всё время шевелится
            if (now - claudeOnTopSince >= OPEN_SETTLE_MS && (stable || unstable > 6)) show(now);
        } else if (!dragging) {
            place(true);
        }
        status = "Claude открыт · рамка " + box.toShortString() + (aboveEdit > 0 ? " (+" + aboveEdit + " над текстом)" : "")
                + (generating ? " · отвечает" : "") + (shown ? " · Clawd виден" : " · жду, пока всё встанет");
        next(now, !shown);
    }

    private void next(long now, boolean waitingToShow) {
        if (now < burstUntil || waitingToShow) schedule(BURST_STEP_MS);
        else if (generating) schedule(STOP_CHECK_MS);
        // ничего не менялось 3 с — проверяем раз в 1,5 с: события всё равно будят сразу
        else if (claudeOnTop) schedule(now - lastActivity < 3000 ? IDLE_POLL_MS : CALM_POLL_MS);
    }

    /**
     * Корень Claude, если его окно на экране и его не закрывает другое окно почти целиком
     * (приложение, рабочий стол, «Недавние», раскрытая шторка). Мелкие окна поверх — превью
     * скриншота, плавающие кнопки — Clawd не прячут: его окно и так выше всех.
     */
    private AccessibilityNodeInfo claudeRootOnTop() {
        List<AccessibilityWindowInfo> windows = new ArrayList<AccessibilityWindowInfo>(getWindows());
        Collections.sort(windows, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        long now = SystemClock.uptimeMillis();
        boolean dump = now - lastWindowsAt >= REPORT_MS;
        StringBuilder sb = dump ? new StringBuilder() : null;
        AccessibilityNodeInfo result = null;
        boolean decided = false;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (decided && sb == null) break;
            int type = w.getType();
            w.getBoundsInScreen(r);
            AccessibilityNodeInfo root = type == AccessibilityWindowInfo.TYPE_APPLICATION ? w.getRoot() : null;
            CharSequence pkg = root != null ? root.getPackageName() : null;
            if (sb != null) {
                sb.append("  ").append(windowType(type)).append(' ').append(pkg != null ? pkg : w.getTitle())
                        .append(w.isFocused() ? " [фокус]" : "").append(' ').append(r.toShortString()).append('\n');
            }
            if (decided) continue; // дальше — только для отчёта
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (pkg != null && CLAUDE.contentEquals(pkg)) {
                    // всплывающие окна Claude (подсказки при наборе, меню) лежат поверх главного —
                    // собираем все его окна подряд, поле ввода может быть в любом
                    if (result == null) {
                        result = root;
                        claudeRoots.clear();
                    }
                    claudeRoots.add(root);
                } else if (result != null || coversScreen(r)) {
                    decided = true;
                }
            } else if (type == AccessibilityWindowInfo.TYPE_SYSTEM && w.isFocused() && coversScreen(r)) {
                CharSequence title = w.getTitle();
                boolean screenshot = title != null && title.toString().toLowerCase().contains("screenshot");
                if (!screenshot) decided = true; // раскрытая шторка
            }
        }
        if (sb != null) {
            lastWindowsAt = now;
            lastWindows = sb.toString();
        }
        if (result == null) claudeRoots.clear();
        return result;
    }

    /** Окно закрывает больше 60% экрана. */
    private boolean coversScreen(Rect r) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (long) r.width() * r.height() > 0.6 * dm.widthPixels * dm.heightPixels;
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "прил";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "клав";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "сист";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "спецв";
            default: return "тип" + t;
        }
    }

    // ---- поле ввода ----

    /**
     * Текстовое поле — самое нижнее редактируемое поле окна. Список сообщений (прокручиваемый)
     * не обходим: там его нет, а сообщений может быть много. Между полными поисками узел только
     * обновляется; полный поиск — когда кеш устарел или содержимое Claude поменялось.
     */
    private boolean findComposer(AccessibilityNodeInfo root, long now) {
        boolean cached = editNode != null && editNode.refresh() && editNode.isVisibleToUser();
        if (cached && !(contentDirty && now - lastFullFind >= FULL_FIND_MS)) return true;
        contentDirty = false;
        lastFullFind = now;

        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        AccessibilityNodeInfo best = null;
        int bestBottom = -1;
        Rect r = new Rect();
        // все окна Claude: при наборе поверх главного бывает окно подсказок без поля ввода
        if (claudeRoots.isEmpty()) nodes.add(root);
        else nodes.addAll(claudeRoots);
        int roots = nodes.size();
        for (int i = 0; i < nodes.size() && i < 800; i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            if (n == null) continue;
            if (n.isEditable() && n.isVisibleToUser()) {
                n.getBoundsInScreen(r);
                if (r.bottom > bestBottom) {
                    best = n;
                    bestBottom = r.bottom;
                }
                continue;
            }
            if (n.isScrollable() && i >= roots) continue;
            for (int c = 0; c < n.getChildCount(); c++) nodes.add(n.getChild(c));
        }
        if (best == null) {
            editNode = null;
            return false;
        }
        editNode = best;
        measureFrame(best, nodes, now);
        return true;
    }

    /**
     * Где верхняя кромка рамки композера: самый внешний предок текстового поля, который ещё
     * похож на рамку — содержит поле, не во весь экран по ширине и не выше 45% экрана. В Claude
     * это узел, в который входят и прикреплённые фото, и ряд кнопок под полем. Полосы на всю
     * ширину над полем (там кнопка «Прокрутить вниз») сюда не попадают.
     */
    private void measureFrame(AccessibilityNodeInfo e, List<AccessibilityNodeInfo> nodes, long now) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect er = new Rect();
        Rect r = new Rect();
        e.getBoundsInScreen(er);
        int top = er.top, left = er.left, right = er.right;
        int gap = Math.round(16 * dm.density);

        AccessibilityNodeInfo p = e.getParent();
        for (int i = 0; i < 6 && p != null; i++) {
            p.getBoundsInScreen(r);
            if (r.height() > dm.heightPixels * 0.45f || r.width() >= dm.widthPixels * 0.98f
                    || r.top > er.top || r.bottom < er.bottom - 2) break;
            top = Math.min(top, r.top);
            left = Math.min(left, r.left);
            right = Math.max(right, r.right);
            p = p.getParent();
        }

        aboveEdit = er.top - top;
        leftOfEdit = er.left - left;
        rightOfEdit = right - er.right;

        // Признак «Claude отвечает» — заодно из того же обхода: кнопка «Стоп» или подсказка
        // «…в очередь» у поля ввода (обычный чат и Code), а в Code ещё и строка с таймером
        // («57 с · думает…») внизу ленты сообщений.
        stopNode = null;
        int near = Math.round(150 * dm.density);
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isVisibleToUser() || !isBusy(n)) continue;
            n.getBoundsInScreen(r);
            if (r.bottom >= top - gap && r.top <= er.bottom + near) {
                stopNode = n;
                break;
            }
        }
        StringBuilder tail = now - lastReportAt >= REPORT_MS ? new StringBuilder() : null;
        if (stopNode == null) stopNode = busyInFeed(nodes, top, tail);

        if (tail != null) {
            lastReportAt = now;
            lastReport = report(e, nodes, top, er.bottom + near) + "низ ленты:\n" + tail;
        }
    }

    // ---- «Claude печатает» ----

    private void updateGenerating() {
        boolean found = (stopNode != null && stopNode.refresh() && stopNode.isVisibleToUser() && isBusy(stopNode))
                || (editNode != null && isBusy(editNode));
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

    /** Кнопка «Стоп», подсказка «…в очередь» (текст, подсказка или описание поля) или строка состояния с таймером. */
    private static boolean isBusy(AccessibilityNodeInfo n) {
        if (isStop(n)) return true;
        CharSequence t = n.getText();
        CharSequence h = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
        CharSequence d = n.getContentDescription();
        return (t != null && (QUEUE.matcher(t).find() || WORKING.matcher(t).find()))
                || (h != null && QUEUE.matcher(h).find())
                || (d != null && QUEUE.matcher(d).find());
    }

    /**
     * Строка состояния Claude Code в ленте сообщений, прямо над полем ввода. Ленту целиком не
     * обходим — только элементы у её нижнего края (видимые, рядом с полем ввода).
     */
    private AccessibilityNodeInfo busyInFeed(List<AccessibilityNodeInfo> nodes, int composerTop, StringBuilder tail) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int reach = Math.round(260 * dm.density);
        Rect r = new Rect();
        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo list : nodes) {
            if (list == null || !list.isScrollable() || !list.isVisibleToUser()) continue;
            list.getBoundsInScreen(r);
            if (r.height() < dm.heightPixels / 4 || r.top > composerTop) continue;
            ArrayList<AccessibilityNodeInfo> queue = new ArrayList<AccessibilityNodeInfo>();
            for (int c = 0; c < list.getChildCount(); c++) queue.add(list.getChild(c));
            for (int i = 0; i < queue.size() && i < 150; i++) {
                AccessibilityNodeInfo n = queue.get(i);
                if (n == null) continue;
                n.getBoundsInScreen(r);
                if (r.bottom < composerTop - reach || r.top > composerTop) continue; // далеко от поля или под ним
                CharSequence t = n.getText();
                if (tail != null && t != null && t.length() <= 48) {
                    tail.append("  ").append(cut(t)).append(' ').append(r.toShortString()).append('\n');
                }
                if (found == null && n.isVisibleToUser() && isBusy(n)) {
                    found = n;
                    if (tail == null) return found;
                }
                for (int c = 0; c < n.getChildCount(); c++) queue.add(n.getChild(c));
            }
        }
        return found;
    }

    // ---- окно Clawd ----

    /** X окна так, чтобы само тело (без ноутбука и запаса) стояло на своём месте вдоль рамки. */
    private int targetX() {
        float f = Math.max(0f, Math.min(1f, Prefs.xFraction(prefs)));
        return box.left + Math.round(f * Math.max(0, box.width() - bodyRect.width())) - bodyRect.left;
    }

    private int targetY() {
        float density = getResources().getDisplayMetrics().density;
        // ноги чуть заходят на кромку рамки, чтобы он «стоял», а не висел
        int overlap = clawd.cellPx() / 2;
        return box.top - clawd.heightPx() + overlap - Math.round(Prefs.yOffsetDp(prefs) * density);
    }

    private void setTouchable(boolean on) {
        if (on) tlp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else tlp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(touch, tlp);
    }

    /** Двигает картинку и окно касаний вместе. */
    private void moveTo(int x, int y) {
        lp.x = x;
        lp.y = y;
        wm.updateViewLayout(clawd, lp);
        tlp.x = x + bodyRect.left;
        tlp.y = y + bodyRect.top;
        wm.updateViewLayout(touch, tlp);
    }

    private void show(long now) {
        moveTo(targetX(), targetY());
        clawd.animate().cancel();
        if (now - hiddenAt < REAPPEAR_MS) {
            clawd.setAlpha(1f);
        } else {
            clawd.setAlpha(0f);
            clawd.animate().alpha(1f).setDuration(120).start();
        }
        clawd.setVisibility(View.VISIBLE);
        touch.setVisibility(View.VISIBLE);
        setTouchable(true);
        shown = true;
        if (generating && Prefs.typing(prefs)) clawd.setMood(ClawdView.Mood.TYPING);
    }

    private void hide(String why) {
        if (why != null) status = why;
        if (!shown) return;
        shown = false;
        hiddenAt = SystemClock.uptimeMillis();
        if (mover != null) mover.cancel();
        clawd.animate().cancel();
        clawd.setVisibility(View.GONE);
        touch.setVisibility(View.GONE);
        setTouchable(false);
        clawd.reset();
        generating = false;
    }

    /** Быстро переезжает к полю ввода (клавиатура, многострочный текст, вложения). */
    private void place(boolean animate) {
        final int fromX = lp.x, fromY = lp.y;
        final int toX = targetX(), toY = targetY();
        if (Math.abs(fromX - toX) <= 1 && Math.abs(fromY - toY) <= 1) return;
        if (mover != null) mover.cancel();
        if (!animate) {
            moveTo(toX, toY);
            return;
        }
        mover = ValueAnimator.ofFloat(0f, 1f);
        mover.setDuration(Math.abs(toY - fromY) > clawd.heightPx() * 2 ? 110 : 70);
        mover.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                if (!shown) return;
                float t = (Float) a.getAnimatedValue();
                moveTo(Math.round(fromX + (toX - fromX) * t), Math.round(fromY + (toY - fromY) * t));
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
                        int minX = box.left - bodyRect.left;
                        int maxX = box.right - bodyRect.right;
                        moveTo(Math.max(minX, Math.min(maxX, Math.round(startX + dx))), lp.y);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int span = Math.max(1, box.width() - bodyRect.width());
                        float f = (lp.x + bodyRect.left - box.left) / (float) span;
                        prefs.edit().putFloat(Prefs.X_FRACTION, Math.max(0f, Math.min(1f, f))).apply();
                        dragging = false;
                    } else {
                        clawd.tapJump();
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
     * Отчёт для настройки: предки поля ввода и узлы в полосе вокруг него (класс, описание, короткий
     * текст, рамка). Сообщения чата сюда не попадают — список сообщений не обходится.
     */
    private String report(AccessibilityNodeInfo e, List<AccessibilityNodeInfo> nodes, int fromY, int toY) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder("Clawd 0.6 · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " · " + dm.widthPixels + "×" + dm.heightPixels
                + " @" + dm.density + "\n");
        sb.append("над текстом +").append(aboveEdit).append(", слева ").append(leftOfEdit)
                .append(", справа ").append(rightOfEdit).append(stopNode != null ? ", Стоп найден" : "").append('\n');
        sb.append("предки поля:\n");
        AccessibilityNodeInfo p = e;
        for (int i = 0; i < 7 && p != null; i++) {
            line(sb, p, 1);
            p = p.getParent();
        }
        sb.append("рядом с полем:\n");
        Rect r = new Rect();
        int margin = Math.round(60 * dm.density);
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || sb.length() > 7000) continue;
            n.getBoundsInScreen(r);
            if (r.bottom < fromY - margin || r.top > toY || r.height() > dm.heightPixels / 2) continue;
            line(sb, n, 1);
        }
        return sb.toString();
    }

    private void line(StringBuilder sb, AccessibilityNodeInfo n, int depth) {
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
        if (n.isScrollable()) sb.append(" [прокрутка]");
        if (n.isClickable()) sb.append(" [кнопка]");
        sb.append(' ').append(r.toShortString()).append('\n');
    }

    private static String cut(CharSequence s) {
        String v = s.toString().replace('\n', ' ');
        return v.length() > 24 ? v.substring(0, 24) + "…" : v;
    }
}
