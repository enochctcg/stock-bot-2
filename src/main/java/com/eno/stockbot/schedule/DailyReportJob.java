package com.eno.stockbot.schedule;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eno.stockbot.service.subscriber.DailyReportService;

/**
 * 排程觸發：每個交易日收盤後推送當天 Excel，接著清除過期的歷史報價。
 */
@Component
public class DailyReportJob {

    private final DailyReportService reportService;
    private final Clock clock;

    public DailyReportJob(DailyReportService reportService, Clock clock) {
        this.reportService = reportService;
        this.clock = clock;
    }

    @Scheduled(cron = "${bot.history.report-cron}", zone = "Asia/Taipei")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        try {
            reportService.sendReports(today);
        } finally {
            // 不論今天有沒有開盤都清一次
            reportService.purgeExpired(today);
        }
    }
}
