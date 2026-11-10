package io.github.yakstork.clawdbuddy;

import java.util.regex.Pattern;

/** Решения службы, не зависящие от Android — их можно проверить тестом без устройства. */
final class Logic {

    /** Кнопка остановки ответа: англ. «Stop response» / «Stop», русская локаль — «Остановить ответ» / «Стоп». */
    private static final Pattern STOP = Pattern.compile(
            "(?iu)^\\s*(stop( response| generating| responding)?|остановить( ответ| генерацию)?|стоп)\\s*$");

    /** Подсказка поля, пока Claude работает: «Поставьте сообщение в очередь…», «Queue a message…». */
    private static final Pattern QUEUE = Pattern.compile("(?iu)(сообщение в очередь|queue a message|queue message)");
    /** Строка состояния Code: «57 с · думает…», «1 мин 3 с · …», «Думает…», «Claude работает…», «Thinking…». */
    private static final Pattern WORKING = Pattern.compile(
            "(?iu)^\\s*((\\d+\\s*(мин|min|m)\\s*)?\\d+\\s*(с|сек|s)\\s*·"
            + "|(думает|всё ещё думает|почти додумал|claude работает|в работе|thinking|working).*…)");

    private Logic() {}

    /** Кнопка «Стоп», подсказка «…в очередь» (текст, подсказка или описание поля) или строка состояния с таймером. */
    static boolean isBusy(CharSequence text, CharSequence hint, CharSequence desc) {
        if ((desc != null && STOP.matcher(desc).matches()) || (text != null && STOP.matcher(text).matches())) {
            return true;
        }
        return (text != null && (QUEUE.matcher(text).find() || WORKING.matcher(text).find()))
                || (hint != null && QUEUE.matcher(hint).find())
                || (desc != null && QUEUE.matcher(desc).find());
    }

    /**
     * Скрытый отступ рамки композера. В чате Claude отдаёт службе внутренний узел рамки: видимая
     * рамка шире него сверху и снизу, и области нажатия кнопок выступают за узел вниз. Видимая
     * рамка охватывает кнопки с запасом 2dp, отступ сверху такой же, как снизу. Если узел и так
     * охватывает кнопки (главная, одна строка) — отступа нет.
     *
     * frameBottom — низ узла рамки, buttonBottom — низ самой нижней кнопки, которая в него
     * упирается (-1, если таких нет).
     */
    static int framePad(int frameBottom, int buttonBottom, float density) {
        int pad = buttonBottom + Math.round(2 * density) - frameBottom;
        // больше 16dp — это уже не отступ рамки, а что-то постороннее
        return pad > 0 && pad <= Math.round(16 * density) ? pad : 0;
    }

    /** Кто на переднем плане. */
    static final class Front {
        static final String CLAUDE = "com.anthropic.claude";
        static final long CLAIM_MS = 600;

        /** Приложение на переднем плане по последнему событию окна приложения; null — ещё не знаем. */
        String foreground;
        /** С какого момента Claude «претендует» на передний план; 0 — таймер не идёт. */
        long topSince;
        private String home;
        /** Передний план держится на фокусе Claude над рабочим столом: уйдёт фокус — вернётся столу. */
        private boolean claimedFromHome;

        /**
         * Событие смены окна. Claude и рабочий стол («Недавние» — это тоже он) принимаются всегда,
         * остальные — только полноэкранные приложения. Возвращает true, если теперь на переднем
         * плане не Claude (службе пора прятаться).
         *
         * claudeFocused — окно Claude сейчас сверху и в фокусе. Тогда событие рабочего стола
         * запоздало (лаунчер шлёт его через 0,3–0,5 с после входа в Claude — конец анимации
         * открытия) и передний план не меняет; но держится он дальше только на фокусе.
         */
        boolean onWindowEvent(String pkg, String home, boolean fullScreenApp, boolean claudeFocused) {
            this.home = home;
            boolean known = CLAUDE.equals(pkg) || pkg.equals(home);
            if (!known && !fullScreenApp) return false;
            if (claudeFocused && pkg.equals(home) && CLAUDE.equals(foreground)) {
                claimedFromHome = true;
                return false;
            }
            foreground = pkg;
            claimedFromHome = false;
            return !CLAUDE.equals(pkg);
        }

        /**
         * Claude на переднем плане? top — его окно сверху в списке окон, focused — у его окна фокус.
         * Если последним событием вышло другое приложение, а Claude события не прислал, он должен
         * непрерывно «претендовать» 0,6 с. Над рабочим столом претензия — ещё и фокус: в «Недавних»
         * окно Claude числится сверху, но фокус у лаунчера. По той же причине претензия над
         * рабочим столом отзывается, как только фокус ушёл: лаунчер мог прислать событие в начале
         * жеста, а «Недавние» открыть позже и уже молча.
         */
        boolean claudeInFront(boolean top, boolean focused, long now) {
            if (!top) {
                topSince = 0;
                return false;
            }
            if (claimedFromHome && !focused && CLAUDE.equals(foreground)) {
                foreground = home;
                claimedFromHome = false;
            }
            if (foreground == null || CLAUDE.equals(foreground)) {
                topSince = 0;
                return true;
            }
            boolean overHome = foreground.equals(home);
            boolean claims = overHome ? focused : true;
            if (!claims) {
                topSince = 0;
                return false;
            }
            if (topSince == 0) topSince = now;
            if (now - topSince >= CLAIM_MS) {
                foreground = CLAUDE;
                claimedFromHome = overHome;
                topSince = 0;
                return true;
            }
            return false;
        }

        /** Таймер идёт — службе стоит проверить ещё раз через ~300 мс. */
        boolean timing() {
            return topSince != 0;
        }
    }

    /** Кольцевой журнал событий на 40 строк; подряд идущие одинаковые строки схлопываются в «×N». */
    static final class Journal {
        private static final int SIZE = 40;
        private final long[] times = new long[SIZE];
        private final String[] lines = new String[SIZE];
        private final int[] counts = new int[SIZE];
        private int next;
        private int size;

        synchronized void add(long now, String line) {
            if (size > 0) {
                int last = (next + SIZE - 1) % SIZE;
                if (lines[last].equals(line)) {
                    counts[last]++;
                    return;
                }
            }
            times[next] = now;
            lines[next] = line;
            counts[next] = 1;
            next = (next + 1) % SIZE;
            if (size < SIZE) size++;
        }

        /** Строки «  12.345 текст ×N» от старых к новым. */
        synchronized String dump() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size; i++) {
                int k = (next + SIZE - size + i) % SIZE;
                long ms = times[k] % 1000;
                sb.append("  ").append(times[k] / 1000).append('.')
                        .append(ms < 10 ? "00" : ms < 100 ? "0" : "").append(ms)
                        .append(' ').append(lines[k]);
                if (counts[k] > 1) sb.append(" ×").append(counts[k]);
                sb.append('\n');
            }
            return sb.toString();
        }
    }
}
