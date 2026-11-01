package com.eno.stockbot.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eno.stockbot.bot.CommandHandler;
import com.eno.stockbot.market.FugleClient;
import com.eno.stockbot.market.Quote;
import com.eno.stockbot.market.QuoteService;
import com.eno.stockbot.subscription.PushSchedule;
import com.eno.stockbot.subscription.Subscriber;
import com.eno.stockbot.subscription.SubscriberRepository;
import com.eno.stockbot.subscription.WatchRepository;
import com.eno.stockbot.telegram.Notifier;

/**
 * 交易日盤中每分鐘執行一次，只推給「這一分鐘輪到」的使用者。
 * 每位使用者的推播時段與間隔存在 subscriber 資料表，未設定的使用者不會收到推播。
 */
@Component
public class IntradayReportJob {

    private static final Logger log = LoggerFactory.getLogger(IntradayReportJob.class);

    private final SubscriberRepository subscribers;
    private final WatchRepository watches;
    private final QuoteService quotes;
    private final Notifier notifier;
    private final Clock clock;

    public IntradayReportJob(SubscriberRepository subscribers, WatchRepository watches, QuoteService quotes,
                             Notifier notifier, Clock clock) {
        this.subscribers = subscribers;
        this.watches = watches;
        this.quotes = quotes;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Scheduled(cron = "${bot.schedule.cron}", zone = "Asia/Taipei")
    public void tick() {
        // 先取得這一分鐘，避免查富果花了幾秒後才計算時間
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        List<Long> due = subscribers.findPushReady().stream()
                .filter(s -> s.schedule().map(p -> p.isDue(now)).orElse(false))
                .map(Subscriber::chatId)
                .toList();
        if (due.isEmpty()) {
            return;
        }

        Map<Long, List<String>> allWatches = watches.findGroupedByChat();
        Map<Long, List<String>> byChat = new LinkedHashMap<>();
        for (Long chatId : due) {
            List<String> list = allWatches.get(chatId);
            if (list != null && !list.isEmpty()) {
                byChat.put(chatId, list);
            }
        }
        if (byChat.isEmpty()) {
            return;
        }
        push(now, byChat);
    }

    private void push(LocalTime now, Map<Long, List<String>> byChat) {
        // 這一分鐘要推的所有人，股票取聯集，每檔只查一次
        Set<String> symbols = new LinkedHashSet<>();
        byChat.values().forEach(symbols::addAll);

        Map<String, Quote> result;
        try {
            result = quotes.getQuotes(symbols);
        } catch (FugleClient.FugleException e) {
            log.error("{} 推播中止: {}", PushSchedule.format(now), e.getMessage());
            return;
        }

        // 休市日（國定假日、颱風假）富果回的是前一個交易日的資料，沒有任何一檔是今天就不推
        String today = LocalDate.now(clock).toString();
        if (result.values().stream().noneMatch(q -> today.equals(q.date()))) {
            log.info("今天 {} 沒有交易資料，判斷為休市，略過推播", today);
            return;
        }

        boolean closed = !result.isEmpty() && result.values().stream().allMatch(Quote::closed);
        String header = "⏱ " + PushSchedule.format(now) + (closed ? " 收盤報價" : " 盤中報價");

        Object buttons = CommandHandler.quoteButtons();
        byChat.forEach((chatId, list) -> notifier.send(chatId, ReportFormatter.format(header, list, result), buttons));
        log.info("{} 已推播 {} 位使用者、{} 檔股票", PushSchedule.format(now), byChat.size(), symbols.size());
    }
}
