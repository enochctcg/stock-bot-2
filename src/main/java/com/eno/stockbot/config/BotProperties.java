package com.eno.stockbot.config;

import java.time.Duration;
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
        Schedule schedule) {

    public record Telegram(String token) {
    }

    public record Fugle(String apiKey, String baseUrl, int requestsPerMinute, Duration cacheTtl) {
    }

    public record Schedule(String cronIntraday, String cronClose) {
    }

    /** 未設定白名單時任何人都能使用。 */
    public boolean isAllowed(long chatId) {
        return allowedChatIds == null || allowedChatIds.isEmpty() || allowedChatIds.contains(chatId);
    }
}
