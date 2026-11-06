package com.eno.stockbot.schedule;

import java.time.Clock;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eno.stockbot.service.subscriber.IntradayPushService;

/**
 * 排程觸發：交易日盤中每分鐘一次，推給這一分鐘輪到的使用者。
 */
@Component
public class IntradayReportJob {

    private final IntradayPushService pushService;
    private final Clock clock;

    public IntradayReportJob(IntradayPushService pushService, Clock clock) {
        this.pushService = pushService;
        this.clock = clock;
    }

    @Scheduled(cron = "${bot.schedule.cron}", zone = "Asia/Taipei")
    public void tick() {
        // 先取得這一分鐘，避免查富果花了幾秒後才計算時間
        pushService.pushDue(LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES));
    }
}
