package com.eno.stockbot.bo;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用者的推播排程：在 [start, end] 時段內，從 start 起每 intervalMinutes 分鐘推播一次。
 *
 * <pre>
 * 09:00–13:30、每 10 分鐘 → 09:00、09:10、09:20 … 13:30
 * 09:05–10:00、每 20 分鐘 → 09:05、09:25、09:45
 * </pre>
 *
 * 解析失敗會丟出 {@link IllegalArgumentException}，訊息可以直接回給使用者。
 */
public record PushSchedule(Window window, int intervalMinutes) {

    /** 可設定的範圍：台股一般交易時段。 */
    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    public static final LocalTime MARKET_CLOSE = LocalTime.of(13, 30);

    public static final int MIN_INTERVAL = 1;
    public static final int MAX_INTERVAL = (int) Duration.between(MARKET_OPEN, MARKET_CLOSE).toMinutes();   // 270

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 推播時段，頭尾都包含。 */
    public record Window(LocalTime start, LocalTime end) {

        // 09:00-13:30、9:00~13:30、0900-1330、09：00 到 13：30
        private static final Pattern FORMAT = Pattern.compile(
                "^(\\d{1,2})[:：]?(\\d{2})\\s*(?:-|~|～|－|到|至|\\s)\\s*(\\d{1,2})[:：]?(\\d{2})$");

        public Window {
            if (start == null || end == null) {
                throw new IllegalArgumentException("請輸入開始與結束時間");
            }
            if (start.isBefore(MARKET_OPEN) || end.isAfter(MARKET_CLOSE)) {
                throw new IllegalArgumentException("時段必須在 " + format(MARKET_OPEN) + " ~ " + format(MARKET_CLOSE) + " 之間");
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("結束時間必須晚於開始時間");
            }
        }

        public static Window parse(String text) {
            Matcher m = text == null ? null : FORMAT.matcher(text.strip());
            if (m == null || !m.matches()) {
                throw new IllegalArgumentException("格式不正確，請輸入像 09:00-13:30 這樣的時段");
            }
            return new Window(time(m.group(1), m.group(2)), time(m.group(3), m.group(4)));
        }

        /** 給 callback_data 用的精簡格式，例如 0900-1330。 */
        public String compact() {
            return start.format(DateTimeFormatter.ofPattern("HHmm")) + "-" + end.format(DateTimeFormatter.ofPattern("HHmm"));
        }

        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }

        @Override
        public String toString() {
            return format(start) + "–" + format(end);
        }

        private static LocalTime time(String h, String m) {
            int hour = Integer.parseInt(h);
            int minute = Integer.parseInt(m);
            if (hour > 23 || minute > 59) {
                throw new IllegalArgumentException("時間不正確：" + h + ":" + m);
            }
            return LocalTime.of(hour, minute);
        }
    }

    public PushSchedule {
        if (window == null) {
            throw new IllegalArgumentException("請先設定推播時段");
        }
        validateInterval(intervalMinutes);
    }

    public PushSchedule(LocalTime start, LocalTime end, int intervalMinutes) {
        this(new Window(start, end), intervalMinutes);
    }

    /** 解析使用者輸入的分鐘數，例如「5」、「5分」、「5 分鐘」。 */
    public static int parseInterval(String text) {
        String digits = text == null ? "" : text.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s*(分鐘|分钟|分|mins?|minutes?)$", "");
        int minutes;
        try {
            minutes = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("請輸入整數分鐘數，例如 5");
        }
        validateInterval(minutes);
        return minutes;
    }

    private static void validateInterval(int minutes) {
        if (minutes < MIN_INTERVAL || minutes > MAX_INTERVAL) {
            throw new IllegalArgumentException("推播間隔必須是 " + MIN_INTERVAL + " ~ " + MAX_INTERVAL + " 分鐘");
        }
    }

    /** 這一分鐘是否要推播；time 會先捨去秒數。 */
    public boolean isDue(LocalTime time) {
        LocalTime t = time.withSecond(0).withNano(0);
        if (t.isBefore(window.start()) || t.isAfter(window.end())) {
            return false;
        }
        return Duration.between(window.start(), t).toMinutes() % intervalMinutes == 0;
    }

    /** 每個交易日推播幾次。 */
    public int timesPerDay() {
        return (int) (window.minutes() / intervalMinutes) + 1;
    }

    /** 前幾個推播時間，用於說明，例如 [09:00, 09:10, 09:20]。 */
    public List<LocalTime> firstTimes(int count) {
        List<LocalTime> times = new ArrayList<>();
        for (LocalTime t = window.start(); times.size() < count && !t.isAfter(window.end());
             t = t.plusMinutes(intervalMinutes)) {
            times.add(t);
        }
        return times;
    }

    /** 例如：每天 28 次：09:00、09:10、09:20… */
    public String timesSummary() {
        List<String> times = firstTimes(3).stream().map(PushSchedule::format).toList();
        String more = timesPerDay() > times.size() ? "…" : "";
        return "每天 " + timesPerDay() + " 次：" + String.join("、", times) + more;
    }

    /** 例如：09:00–13:30，每 10 分鐘（每天 28 次：09:00、09:10、09:20…） */
    public String describe() {
        return window + "，每 " + intervalMinutes + " 分鐘（" + timesSummary() + "）";
    }

    public static String format(LocalTime t) {
        return t.format(HHMM);
    }
}
