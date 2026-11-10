package io.github.yakstork.clawdbuddy;

/** Тесты Logic без библиотек: при провале печатает сообщение и выходит с кодом 1. */
public final class LogicTest {
    private static int failed;

    private static void check(boolean ok, String name) {
        if (!ok) {
            failed++;
            System.err.println("ПРОВАЛ: " + name);
        }
    }

    private static final String C = Logic.Front.CLAUDE;
    private static final String HOME = "com.launcher";

    private static Logic.Front claudeFront() {
        Logic.Front f = new Logic.Front();
        f.onWindowEvent(C, HOME, false, false);
        return f;
    }

    public static void main(String[] args) {
        busy();
        frame();
        framePad();
        front();
        journal();
        if (failed > 0) {
            System.err.println("Провалено проверок: " + failed);
            System.exit(1);
        }
        System.out.println("LogicTest: всё зелёное");
    }

    private static void checkFrame(String name, int[] want, int[] edit, int[]... ancestors) {
        int[] got = Logic.frame(edit, ancestors, 1272, 2603, 3.5f);
        check(java.util.Arrays.equals(got, want), "frame: " + name + " — " + java.util.Arrays.toString(got));
    }

    /** Раскладки из отчётов с OnePlus CPH2771 (1272×2603, плотность 3.5). */
    private static void frame() {
        checkFrame("чат Code, две строки", new int[] {56, 1711, 1216, 2425},
                new int[] {102, 1739, 1170, 2243},
                new int[] {56, 1711, 1216, 2271}, new int[] {56, 1711, 1216, 2425},
                new int[] {0, 0, 1272, 2509}, new int[] {0, 0, 1272, 2509});
        checkFrame("главная, одна строка", new int[] {56, 2271, 1216, 2453},
                new int[] {256, 2278, 848, 2446},
                new int[] {256, 2278, 873, 2446}, new int[] {256, 2278, 873, 2446},
                new int[] {231, 2278, 873, 2446}, new int[] {56, 2278, 1216, 2446},
                new int[] {56, 2271, 1216, 2453}, new int[] {0, 2215, 1272, 2509});
        // поле (область нажатия 48dp) выступает вверх за контейнер [56,1274] — рамка дальше
        checkFrame("главная с клавиатурой, две строки", new int[] {56, 1246, 1216, 1596},
                new int[] {102, 1260, 1170, 1428},
                new int[] {102, 1260, 1195, 1428}, new int[] {102, 1260, 1195, 1428},
                new int[] {56, 1260, 1216, 1428}, new int[] {56, 1274, 1216, 1568},
                new int[] {56, 1246, 1216, 1596}, new int[] {0, 1190, 1272, 1652});
        checkFrame("поиск проектов, рамки нет", new int[] {42, 393, 1230, 561},
                new int[] {42, 393, 1230, 561},
                new int[] {0, 0, 1272, 2800}, new int[] {0, 0, 1272, 2800});
        checkFrame("предков меньше шести", new int[] {56, 1711, 1216, 2271},
                new int[] {102, 1739, 1170, 2243},
                new int[] {56, 1711, 1216, 2271}, null, null);
    }

    /** Числа — из отчётов с OnePlus CPH2771 (плотность 3.5), сверены со скриншотами. */
    private static void framePad() {
        // чат Code, две строки: узел рамки до 2425, кнопки до 2446, видимая рамка на 28 шире
        check(Logic.framePad(2425, 2446, 3.5f) == 28, "framePad: чат, скрытый отступ 28");
        // то же с вложениями и клавиатурой
        check(Logic.framePad(1568, 1589, 3.5f) == 28, "framePad: вложения, скрытый отступ 28");
        // главная, одна строка: узел до 2453 уже охватывает кнопки (2446) — отступа нет
        check(Logic.framePad(2453, 2446, 3.5f) == 0, "framePad: главная, отступа нет");
        // кнопок рядом нет
        check(Logic.framePad(2453, -1, 3.5f) == 0, "framePad: без кнопок");
        // что-то высокое рядом — не рамка, отступ не раздуваем
        check(Logic.framePad(1000, 1500, 3.5f) == 0, "framePad: слишком большой выступ игнорируется");
    }

    private static void busy() {
        String[] yes = {"Stop response", "Остановить ответ", "Стоп", "57 с · думает…", "1 мин 3 с · пишет", "Thinking…"};
        for (String s : yes) check(Logic.isBusy(s, null, null), "isBusy текст «" + s + "»");
        check(Logic.isBusy(null, "Queue a message…", null), "isBusy подсказка Queue");
        check(Logic.isBusy(null, "Поставьте сообщение в очередь…", null), "isBusy подсказка очередь");
        check(Logic.isBusy(null, null, "Стоп"), "isBusy описание Стоп");
        String[] no = {"Opus 5.5", "Средний", "Что обдумаем?", "Stop the war"};
        for (String s : no) check(!Logic.isBusy(s, null, null), "не isBusy «" + s + "»");
        check(!Logic.isBusy(null, null, null), "не isBusy null");
    }

    private static void front() {
        // (а) Claude -> событие Claude
        Logic.Front f = claudeFront();
        check(f.claudeInFront(true, true, 0), "а: Claude виден");

        // (б) событие home -> не виден; «Недавние»: top, без фокуса сколько угодно
        f = claudeFront();
        check(f.onWindowEvent(HOME, HOME, false, false), "б: home — прятаться");
        check(!f.claudeInFront(true, false, 0), "б: сразу не виден");
        check(!f.claudeInFront(true, false, 5000), "б: Недавние 5 с не виден");
        check(!f.claudeInFront(true, false, 60000), "б: Недавние 60 с не виден");
        check(!f.timing(), "б: таймер не идёт");

        // (в) БАГ: событие Claude, затем запоздалое событие home, потом top и focused
        f = claudeFront();
        f.onWindowEvent(HOME, HOME, false, false);
        check(!f.claudeInFront(true, true, 1000), "в: 0 мс не виден");
        check(f.timing(), "в: таймер идёт");
        check(!f.claudeInFront(true, true, 1599), "в: 599 мс не виден");
        check(f.claudeInFront(true, true, 1600), "в: 600 мс виден");
        check(C.equals(f.foreground), "в: foreground == Claude");

        // (г) возврат без события Claude
        f = new Logic.Front();
        f.onWindowEvent(HOME, HOME, false, false);
        check(!f.claudeInFront(true, true, 100), "г: сразу не виден");
        check(f.claudeInFront(true, true, 700), "г: через 600 мс виден");
        check(C.equals(f.foreground), "г: foreground == Claude");

        // (ж) над рабочим столом потеря фокуса посреди отсчёта сбрасывает таймер
        f = new Logic.Front();
        f.onWindowEvent(HOME, HOME, false, false);
        check(!f.claudeInFront(true, true, 1), "ж: отсчёт пошёл");
        check(!f.claudeInFront(true, false, 300), "ж: фокус ушёл — не виден");
        check(!f.timing(), "ж: таймер сброшен");
        check(!f.claudeInFront(true, true, 400), "ж: отсчёт заново");
        check(!f.claudeInFront(true, true, 999), "ж: 599 мс не виден");
        check(f.claudeInFront(true, true, 1000), "ж: 600 мс виден");

        // (з) медленный свайп в «Недавние»: передний план получен по претензии, потом фокус
        // ушёл к лаунчеру без нового события — Clawd прячется и сам не возвращается
        check(!f.claudeInFront(true, false, 2000), "з: фокус ушёл — не виден");
        check(HOME.equals(f.foreground), "з: foreground снова рабочий стол");
        check(!f.claudeInFront(true, false, 9000), "з: в Недавних не виден");
        // а после настоящего события Claude потеря фокуса его не прячет (как раньше)
        f.onWindowEvent(C, HOME, false, false);
        check(f.claudeInFront(true, false, 9100), "з: после события Claude виден и без фокуса");

        // (и) запоздалое событие лаунчера при входе, Claude уже сверху и в фокусе: не прятаться
        f = claudeFront();
        check(!f.onWindowEvent(HOME, HOME, false, true), "и: запоздалое событие — не прятаться");
        check(C.equals(f.foreground), "и: foreground остался Claude");
        check(f.claudeInFront(true, true, 100), "и: виден без паузы");
        // но это мог быть и ранний сигнал жеста в «Недавние»: фокус ушёл — прячемся
        check(!f.claudeInFront(true, false, 200), "и: фокус ушёл — не виден");
        check(HOME.equals(f.foreground), "и: foreground рабочий стол");
        // событие лаунчера, когда Claude не в фокусе, прячет как раньше
        f = claudeFront();
        check(f.onWindowEvent(HOME, HOME, false, false), "и: Claude без фокуса — прятаться");

        // (д) чужое полноэкранное приложение и плавающее
        f = claudeFront();
        check(f.onWindowEvent("com.other", HOME, true, false), "д: чужое полноэкранное — прятаться");
        check(!f.claudeInFront(false, false, 100), "д: Claude не сверху — не виден");
        f = claudeFront();
        check(!f.onWindowEvent("com.screenshot", HOME, false, false), "д: плавающее игнорируется");
        check("com.anthropic.claude".equals(f.foreground), "д: foreground не изменился");
        check(f.claudeInFront(true, false, 100), "д: после плавающего Claude виден");
        f = claudeFront();
        f.onWindowEvent("com.other", HOME, true, false);
        check(!f.claudeInFront(true, false, 100), "д: закрыли чужое, 0 мс не виден");
        check(!f.claudeInFront(true, false, 699), "д: 599 мс не виден");
        check(f.claudeInFront(true, false, 700), "д: 600 мс виден без фокуса");

        // (е) таймер сбрасывается, если top пропал
        f = claudeFront();
        f.onWindowEvent("com.other", HOME, true, false);
        f.claudeInFront(true, false, 100);
        f.claudeInFront(true, false, 400);
        check(!f.claudeInFront(false, false, 500), "е: top пропал — не виден");
        check(!f.timing(), "е: таймер сброшен");
        check(!f.claudeInFront(true, false, 700), "е: заново, 0 мс");
        check(!f.claudeInFront(true, false, 1200), "е: прошло 500 мс от нового старта");
        check(f.claudeInFront(true, false, 1300), "е: 600 мс от нового старта");
    }

    private static void journal() {
        Logic.Journal j = new Logic.Journal();
        check(j.dump().isEmpty(), "журнал пуст");
        j.add(1005, "a");
        j.add(2000, "b");
        check(j.dump().equals("  1.005 a\n  2.000 b\n"), "журнал: порядок, получено «" + j.dump() + "»");

        j = new Logic.Journal();
        j.add(12345, "x");
        j.add(12400, "x");
        j.add(12500, "x");
        j.add(13000, "y");
        check(j.dump().equals("  12.345 x ×3\n  13.000 y\n"), "журнал: повторы, получено «" + j.dump() + "»");

        j = new Logic.Journal();
        for (int i = 0; i < 45; i++) j.add(i * 1000L, "s" + i);
        String[] rows = j.dump().split("\n");
        check(rows.length == 40, "журнал: 40 строк, получено " + rows.length);
        check(rows[0].equals("  5.000 s5"), "журнал: старые вытеснены, первая «" + rows[0] + "»");
        check(rows[39].equals("  44.000 s44"), "журнал: последняя «" + rows[39] + "»");
    }
}
