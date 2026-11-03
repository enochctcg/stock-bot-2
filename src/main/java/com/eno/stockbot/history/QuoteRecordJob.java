package com.eno.stockbot.history;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eno.stockbot.market.FugleClient;
import com.eno.stockbot.market.Quote;
import com.eno.stockbot.market.QuoteService;
import com.eno.stockbot.subscription.PushSchedule;
import com.eno.stockbot.subscription.WatchRepository;

/**
 * 交易日每 30 分鐘記錄一次所有被訂閱股票（所有人觀察清單的聯集）的報價，收盤後再記錄一次最終價。
 * 和使用者自己的推播設定無關：只要有人在觀察清單裡就會記錄。
 */
@Component
public class QuoteRecordJob {

    private static final Logger log = LoggerFactory.getLogger(QuoteRecordJob.class);

    private final WatchRepository watches;
    private final QuoteService quotes;
    private final QuoteHistoryRepository history;
    private final Clock clock;

    public QuoteRecordJob(WatchRepository watches, QuoteService quotes, QuoteHistoryRepository history, Clock clock) {
        this.watches = watches;
        this.quotes = quotes;
        this.history = history;
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

    void snapshot(String label) {
        // 先取得這一分鐘，避免查富果花了幾秒後才計算時間
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        LocalDate today = LocalDate.now(clock);

        List<String> symbols = watches.findAllSymbols();
        if (symbols.isEmpty()) {
            log.info("{} {}紀錄：沒有任何被訂閱的股票", PushSchedule.format(now), label);
            return;
        }

        Map<String, Quote> result;
        try {
            result = quotes.getQuotes(symbols);
        } catch (FugleClient.FugleException e) {
            log.error("{} {}紀錄中止: {}", PushSchedule.format(now), label, e.getMessage());
            return;
        }

        // 休市日富果回的是前一個交易日的資料，不是今天的就不記錄
        String todayText = today.toString();
        List<Quote> todays = result.values().stream().filter(q -> todayText.equals(q.date())).toList();
        if (todays.isEmpty()) {
            log.info("今天 {} 沒有交易資料，判斷為休市，略過{}紀錄", todayText, label);
            return;
        }

        history.save(today, now, todays);
        log.info("{} {}紀錄完成：{}/{} 檔", PushSchedule.format(now), label, todays.size(), symbols.size());
    }
}
