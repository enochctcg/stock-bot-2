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
import org.slf4j.MDC;
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

        List<Subscriber> ready = subscribers.findPushReady();
        List<Long> due = ready.stream()
                .filter(s -> s.schedule().map(p -> p.isDue(now)).orElse(false))
                .map(Subscriber::chatId)
                .toList();
        if (due.isEmpty()) {
            // 每分鐘都會跑，沒人到期時只記 DEBUG，避免洗版
            log.debug("{} 排程檢查：{} 位已設定推播，這一分鐘沒有人到期", PushSchedule.format(now), ready.size());
            return;
        }
        log.info("{} 排程檢查：{} 位已設定推播，{} 位到期 {}", PushSchedule.format(now), ready.size(), due.size(), due);

        Map<Long, List<String>> allWatches = watches.findGroupedByChat();
        Map<Long, List<String>> byChat = new LinkedHashMap<>();
        for (Long chatId : due) {
            List<String> list = allWatches.get(chatId);
            if (list != null && !list.isEmpty()) {
                byChat.put(chatId, list);
            } else {
                log.info("{} 略過 chat {}：觀察清單是空的", PushSchedule.format(now), chatId);
            }
        }
        if (byChat.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        push(now, byChat);
        log.info("{} 本輪推播結束，耗時 {} ms", PushSchedule.format(now), (System.nanoTime() - start) / 1_000_000);
    }

    private void push(LocalTime now, Map<Long, List<String>> byChat) {
        // 這一分鐘要推的所有人，股票取聯集，每檔只查一次
        Set<String> symbols = new LinkedHashSet<>();
        byChat.values().forEach(symbols::addAll);

        Map<String, Quote> result;
        try {
            log.info("{} 查詢報價 {} 檔：{}", PushSchedule.format(now), symbols.size(), symbols);
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
        int ok = 0;
        for (Map.Entry<Long, List<String>> e : byChat.entrySet()) {
            long chatId = e.getKey();
            MDC.put("chatId", String.valueOf(chatId));   // 這位使用者推播過程中的 log 都帶上 chat id
            try {
                boolean sent = notifier.sendHtml(chatId, ReportFormatter.format(header, e.getValue(), result), buttons);
                if (sent) {
                    ok++;
                    log.info("{} 推播成功 → chat {}，{} 檔：{}", PushSchedule.format(now), chatId, e.getValue().size(), e.getValue());
                } else {
                    log.warn("{} 推播失敗 → chat {}", PushSchedule.format(now), chatId);
                }
            } finally {
                MDC.remove("chatId");
            }
        }
        log.info("{} 推播完成：{}/{} 位使用者成功、共 {} 檔股票", PushSchedule.format(now), ok, byChat.size(), symbols.size());
    }
}
