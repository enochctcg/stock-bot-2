package com.eno.stockbot.trigger.startup;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.eno.stockbot.bo.SubscriberSummary;
import com.eno.stockbot.service.SubscriptionService;

/**
 * 服務啟動觸發：啟動完成後，在 log 列出所有訂閱者：誰、什麼時候訂閱、推播是否開啟、觀察幾檔。
 */
@Component
public class SubscriberStartupLog {

    private static final Logger log = LoggerFactory.getLogger(SubscriberStartupLog.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SubscriptionService subscriptions;

    public SubscriberStartupLog(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<SubscriberSummary> all = subscriptions.findAllSummaries();
        long enabled = all.stream().filter(SubscriberSummary::enabled).count();
        log.info("[訂閱] 目前共 {} 位訂閱者，推播開啟 {} 位、已暫停 {} 位", all.size(), enabled, all.size() - enabled);
        for (SubscriberSummary s : all) {
            log.info("[訂閱]   chat {} username={} 訂閱於 {}，推播{}，觀察 {} 檔", s.chatId(), s.username(),
                    s.createdAt().format(TIME), s.enabled() ? "開啟中" : "已暫停", s.watchCount());
        }
    }
}
