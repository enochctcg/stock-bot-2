package com.eno.stockbot.subscription;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 服務啟動完成後，在 log 列出所有訂閱者：誰、什麼時候訂閱、推播是否開啟、觀察幾檔。
 */
@Component
public class SubscriberStartupLog {

    private static final Logger log = LoggerFactory.getLogger(SubscriberStartupLog.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SubscriberRepository subscribers;

    public SubscriberStartupLog(SubscriberRepository subscribers) {
        this.subscribers = subscribers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<SubscriberRepository.Summary> all = subscribers.findAllSummaries();
        long enabled = all.stream().filter(SubscriberRepository.Summary::enabled).count();
        log.info("[訂閱] 目前共 {} 位訂閱者，推播開啟 {} 位、已暫停 {} 位", all.size(), enabled, all.size() - enabled);
        for (SubscriberRepository.Summary s : all) {
            log.info("[訂閱]   chat {} username={} 訂閱於 {}，推播{}，觀察 {} 檔", s.chatId(), s.username(),
                    s.createdAt().format(TIME), s.enabled() ? "開啟中" : "已暫停", s.watchCount());
        }
    }
}
