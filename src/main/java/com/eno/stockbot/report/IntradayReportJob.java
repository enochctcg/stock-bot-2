package com.eno.stockbot.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import com.eno.stockbot.bot.CommandHandler;
import com.eno.stockbot.market.FugleClient;
import com.eno.stockbot.market.Quote;
import com.eno.stockbot.market.QuoteService;
import com.eno.stockbot.subscription.WatchRepository;
import com.eno.stockbot.telegram.Notifier;

/**
 * 盤中每 10 分鐘推播所有訂閱者的觀察清單。排程時間見 application.yml 的 bot.schedule。
 */
@Component
public class IntradayReportJob {

    private static final Logger log = LoggerFactory.getLogger(IntradayReportJob.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final WatchRepository watches;
    private final QuoteService quotes;
    private final Notifier notifier;
    private final Clock clock;

    public IntradayReportJob(WatchRepository watches, QuoteService quotes, Notifier notifier, Clock clock) {
        this.watches = watches;
        this.quotes = quotes;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Schedules({
            @Scheduled(cron = "${bot.schedule.cron-intraday}", zone = "Asia/Taipei"),
            @Scheduled(cron = "${bot.schedule.cron-close}", zone = "Asia/Taipei")
    })
    public void push() {
        Map<Long, List<String>> byChat = watches.findActiveGroupedByChat();
        if (byChat.isEmpty()) {
            return;
        }

        Set<String> symbols = new LinkedHashSet<>();
        byChat.values().forEach(symbols::addAll);

        Map<String, Quote> result;
        try {
            result = quotes.getQuotes(symbols);
        } catch (FugleClient.FugleException e) {
            log.error("本輪推播中止: {}", e.getMessage());
            return;
        }

        // 休市日（國定假日、颱風假）富果回的是前一個交易日的資料，沒有任何一檔是今天就不推
        String today = LocalDate.now(clock).toString();
        if (result.values().stream().noneMatch(q -> today.equals(q.date()))) {
            log.info("今天 {} 沒有交易資料，判斷為休市，略過推播", today);
            return;
        }

        boolean closed = result.values().stream().allMatch(Quote::closed);
        String header = "⏱ " + LocalTime.now(clock).format(HHMM) + (closed ? " 收盤報價" : " 盤中報價");

        Object buttons = CommandHandler.quoteButtons();
        byChat.forEach((chatId, list) -> notifier.send(chatId, ReportFormatter.format(header, list, result), buttons));
        log.info("已推播 {} 位使用者、{} 檔股票", byChat.size(), symbols.size());
    }
}
