package com.eno.stockbot.bo;

import java.time.LocalDateTime;

/**
 * 訂閱資訊（subscriber 加上觀察檔數）：給 log 顯示誰在什麼時候訂閱、目前狀態。
 */
public record SubscriberSummary(long chatId, String username, boolean enabled, LocalDateTime createdAt,
                                int watchCount) {
}
