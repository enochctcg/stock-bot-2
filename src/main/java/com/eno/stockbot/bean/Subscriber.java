package com.eno.stockbot.bean;

import java.time.LocalTime;
import java.util.Optional;

import com.eno.stockbot.bo.PushSchedule;

/**
 * subscriber 資料表的一列。push_start / push_end / interval_minutes 任一為 null 代表尚未設定推播。
 */
public record Subscriber(
        long chatId,
        String username,
        boolean enabled,
        LocalTime pushStart,
        LocalTime pushEnd,
        Integer intervalMinutes) {

    public boolean hasWindow() {
        return pushStart != null && pushEnd != null;
    }

    public boolean configured() {
        return hasWindow() && intervalMinutes != null;
    }

    public Optional<PushSchedule.Window> window() {
        return hasWindow() ? Optional.of(new PushSchedule.Window(pushStart, pushEnd)) : Optional.empty();
    }

    /** 已設定且資料合法時才有值（DB 被手動改成不合法的值時視為未設定）。 */
    public Optional<PushSchedule> schedule() {
        if (!configured()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PushSchedule(pushStart, pushEnd, intervalMinutes));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
