package com.eno.stockbot.bo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.eno.stockbot.bean.Subscriber;

class PushScheduleTest {

    private static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    private static String error(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    @Test
    void dueEveryIntervalFromStartIncludingEnd() {
        PushSchedule p = new PushSchedule(t("09:00"), t("13:30"), 10);
        assertTrue(p.isDue(t("09:00")));
        assertTrue(p.isDue(t("09:10")));
        assertFalse(p.isDue(t("09:05")));
        assertTrue(p.isDue(t("13:30")));
        assertFalse(p.isDue(t("08:59")));
        assertFalse(p.isDue(t("13:31")));
        assertEquals(28, p.timesPerDay());
    }

    @Test
    void intervalCountsFromWindowStartNotFromTheHour() {
        PushSchedule p = new PushSchedule(t("09:05"), t("10:00"), 20);
        assertTrue(p.isDue(t("09:05")));
        assertTrue(p.isDue(t("09:25")));
        assertTrue(p.isDue(t("09:45")));
        assertFalse(p.isDue(t("10:00")));
        assertEquals(List.of(t("09:05"), t("09:25"), t("09:45")), p.firstTimes(10));
    }

    @Test
    void oneMinuteIntervalIsDueEveryMinuteAndIgnoresSeconds() {
        PushSchedule p = new PushSchedule(t("12:30"), t("13:30"), 1);
        assertTrue(p.isDue(LocalTime.of(12, 47, 3)));
        assertEquals(61, p.timesPerDay());
    }

    @Test
    void parsesCommonWindowFormats() {
        PushSchedule.Window expected = new PushSchedule.Window(t("09:00"), t("13:30"));
        for (String s : List.of("09:00-13:30", "9:00-13:30", "0900-1330", " 09:00 ~ 13:30 ", "09：00到13：30", "09:00 13:30")) {
            assertEquals(expected, PushSchedule.Window.parse(s));
        }
        assertEquals("0900-1330", expected.compact());
        assertEquals("09:00–13:30", expected.toString());
    }

    @Test
    void rejectsInvalidWindows() {
        assertEquals("格式不正確，請輸入像 09:00-13:30 這樣的時段", error(() -> PushSchedule.Window.parse("早上")));
        assertEquals("時段必須在 09:00 ~ 13:30 之間", error(() -> PushSchedule.Window.parse("08:30-10:00")));
        assertEquals("時段必須在 09:00 ~ 13:30 之間", error(() -> PushSchedule.Window.parse("12:00-14:00")));
        assertEquals("結束時間必須晚於開始時間", error(() -> PushSchedule.Window.parse("11:00-10:00")));
        assertEquals("時間不正確：25:00", error(() -> PushSchedule.Window.parse("25:00-26:00")));
    }

    @Test
    void parsesIntervalWithUnits() {
        assertEquals(5, PushSchedule.parseInterval("5"));
        assertEquals(15, PushSchedule.parseInterval(" 15 分鐘"));
        assertEquals(1, PushSchedule.parseInterval("1分"));
        assertEquals(30, PushSchedule.parseInterval("30 min"));
    }

    @Test
    void rejectsInvalidIntervals() {
        assertEquals("推播間隔必須是 1 ~ 270 分鐘", error(() -> PushSchedule.parseInterval("0")));
        assertEquals("推播間隔必須是 1 ~ 270 分鐘", error(() -> PushSchedule.parseInterval("300")));
        assertEquals("請輸入整數分鐘數，例如 5", error(() -> PushSchedule.parseInterval("abc")));
    }

    @Test
    void describesSchedule() {
        assertEquals("09:00–13:30，每 10 分鐘（每天 28 次：09:00、09:10、09:20…）",
                new PushSchedule(t("09:00"), t("13:30"), 10).describe());
        assertEquals("每天 2 次：09:00、10:00", new PushSchedule(t("09:00"), t("10:00"), 60).timesSummary());
    }

    @Test
    void subscriberTreatsMissingFieldsAsNotConfigured() {
        assertFalse(new Subscriber(1, "u", true, null, null, null).configured());
        assertFalse(new Subscriber(1, "u", true, t("09:00"), t("13:30"), null).configured());
        assertTrue(new Subscriber(1, "u", true, t("09:00"), t("13:30"), 10).schedule().isPresent());
        // DB 被改成不合法的值時不推播，而不是丟例外
        assertTrue(new Subscriber(1, "u", true, t("08:00"), t("13:30"), 10).schedule().isEmpty());
    }
}
