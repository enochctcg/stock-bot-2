package com.eno.stockbot.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.eno.stockbot.bo.PushSchedule;
import com.eno.stockbot.client.FugleClient;
import com.eno.stockbot.dto.Quote;
import com.eno.stockbot.repository.QuoteHistoryRepository;
import com.eno.stockbot.repository.WatchRepository;

/**
 * 記錄所有被訂閱股票（所有人觀察清單的聯集）當下的報價。
 * 和使用者自己的推播設定無關：只要有人在觀察清單裡就會記錄。
 */
@Service
public class QuoteRecordService {

    private static final Logger log = LoggerFactory.getLogger(QuoteRecordService.class);

    private final WatchRepository watches;
    private final QuoteService quotes;
    private final QuoteHistoryRepository history;

    public QuoteRecordService(WatchRepository watches, QuoteService quotes, QuoteHistoryRepository history) {
        this.watches = watches;
        this.quotes = quotes;
        this.history = history;
    }

    /**
     * @param now   這一分鐘（已捨去秒數），存成紀錄的時間點
     * @param label 盤中 / 收盤，只用於 log
     */
    public void record(LocalDate today, LocalTime now, String label) {
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
