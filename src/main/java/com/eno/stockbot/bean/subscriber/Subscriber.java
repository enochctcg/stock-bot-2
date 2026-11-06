package com.eno.stockbot.bean.subscriber;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * subscriber 資料表。push_start / push_end / interval_minutes 任一為 null 代表尚未設定推播。
 */
@Data
@TableName("subscriber")
public class Subscriber {

    /** Telegram chat id，由 Telegram 提供，不是自動產生。 */
    @TableId(type = IdType.INPUT)
    private Long chatId;
    private String username;
    private boolean enabled;
    private LocalTime pushStart;
    private LocalTime pushEnd;
    private Integer intervalMinutes;
    /** 啟動時是否要通知這位使用者重新設定推播（V2 migration 標記）。 */
    private boolean setupNoticePending;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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
