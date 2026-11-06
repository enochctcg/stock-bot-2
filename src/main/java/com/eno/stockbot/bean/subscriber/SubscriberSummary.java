package com.eno.stockbot.bean.subscriber;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 訂閱資訊（subscriber 加上觀察檔數）：給 log 顯示誰在什麼時候訂閱、目前狀態。
 */
@Data
public class SubscriberSummary {

    private Long chatId;
    private String username;
    private boolean enabled;
    private LocalDateTime createdAt;
    private int watchCount;
}
