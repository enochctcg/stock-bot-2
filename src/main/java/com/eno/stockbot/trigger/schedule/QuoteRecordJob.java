package com.eno.stockbot.trigger.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eno.stockbot.service.QuoteRecordService;

/**
 * 排程觸發：交易日每 30 分鐘記錄一次報價，收盤後再記錄一次最終價。
 */
@Component
public class QuoteRecordJob {

    private final QuoteRecordService recordService;
    private final Clock clock;

    public QuoteRecordJob(QuoteRecordService recordService, Clock clock) {
        this.recordService = recordService;
        this.clock = clock;
    }

    @Scheduled(cron = "${bot.history.record-cron}", zone = "Asia/Taipei")
    public void record() {
        snapshot("盤中");
    }

    @Scheduled(cron = "${bot.history.close-cron}", zone = "Asia/Taipei")
    public void recordClose() {
        snapshot("收盤");
    }

    private void snapshot(String label) {
        // 先取得這一分鐘，避免查富果花了幾秒後才計算時間
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        recordService.record(LocalDate.now(clock), now, label);
    }
}
