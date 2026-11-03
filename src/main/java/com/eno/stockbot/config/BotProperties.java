package com.eno.stockbot.config;

import java.time.Duration;
import java.time.Period;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 中 bot.* 的設定。
 */
@ConfigurationProperties("bot")
public record BotProperties(
        Telegram telegram,
        Fugle fugle,
        int maxWatchPerUser,
        List<Long> allowedChatIds,
        Schedule schedule,
        History history) {

    public record Telegram(String token) {
    }

    public record Fugle(String apiKey, String baseUrl, int requestsPerMinute, Duration cacheTtl) {
    }

    public record Schedule(String cron) {
    }

    /**
     * 歷史報價紀錄與每日 Excel。
     *
     * @param recordCron 盤中記錄報價的時間點
     * @param closeCron  收盤後再記錄一次最終價
     * @param reportCron 產生並推送每日 Excel 的時間
     * @param retention  歷史報價保留多久
     */
    public record History(String recordCron, String closeCron, String reportCron, Period retention) {
    }

    /** 未設定白名單時任何人都能使用。 */
    public boolean isAllowed(long chatId) {
        return allowedChatIds == null || allowedChatIds.isEmpty() || allowedChatIds.contains(chatId);
    }
}
