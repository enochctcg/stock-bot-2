package com.eno.stockbot.history;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.subscription.SubscriberRepository;
import com.eno.stockbot.subscription.WatchRepository;
import com.eno.stockbot.telegram.Notifier;

/**
 * 每個交易日收盤後，把當天的歷史報價整理成 Excel 推給每位使用者（只含自己的觀察清單），
 * 接著刪除超過保留期限的舊資料。已暫停推播（或封鎖 bot）的使用者不送。
 */
@Component
public class DailyReportJob {

    private static final Logger log = LoggerFactory.getLogger(DailyReportJob.class);

    private final QuoteHistoryRepository history;
    private final SubscriberRepository subscribers;
    private final WatchRepository watches;
    private final Notifier notifier;
    private final Clock clock;
    private final Period retention;

    public DailyReportJob(QuoteHistoryRepository history, SubscriberRepository subscribers, WatchRepository watches,
                          Notifier notifier, Clock clock, BotProperties props) {
        this.history = history;
        this.subscribers = subscribers;
        this.watches = watches;
        this.notifier = notifier;
        this.clock = clock;
        this.retention = props.history().retention() == null ? Period.ofMonths(1) : props.history().retention();
    }

    @Scheduled(cron = "${bot.history.report-cron}", zone = "Asia/Taipei")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        try {
            sendReports(today);
        } finally {
            // 不論今天有沒有開盤都清一次
            history.deleteBefore(today.minus(retention));
        }
    }

    void sendReports(LocalDate today) {
        List<QuoteSnapshot> snapshots = history.findByDate(today);
        if (snapshots.isEmpty()) {
            log.info("{} 沒有歷史報價（休市或沒有被訂閱的股票），不產生每日 Excel", today);
            return;
        }
        Set<String> recorded = snapshots.stream().map(QuoteSnapshot::symbol).collect(Collectors.toSet());
        Set<Long> enabled = subscribers.findEnabledChatIds();

        long start = System.nanoTime();
        int ok = 0;
        int total = 0;
        for (Map.Entry<Long, List<String>> e : watches.findGroupedByChat().entrySet()) {
            long chatId = e.getKey();
            List<String> symbols = e.getValue();
            if (!enabled.contains(chatId) || symbols.stream().noneMatch(recorded::contains)) {
                continue;
            }
            total++;
            MDC.put("chatId", String.valueOf(chatId));
            try {
                byte[] file = DailyExcel.build(today, symbols, snapshots);
                String caption = "📑 " + today + " 觀察清單報價紀錄（" + symbols.size() + " 檔，每 30 分鐘一筆）";
                if (notifier.sendDocument(chatId, DailyExcel.fileName(today), file, caption)) {
                    ok++;
                } else {
                    log.warn("每日 Excel 推送失敗 → chat {}", chatId);
                }
            } catch (RuntimeException ex) {
                log.error("產生 chat {} 的每日 Excel 失敗", chatId, ex);
            } finally {
                MDC.remove("chatId");
            }
        }
        log.info("{} 每日 Excel 推送完成：{}/{} 位使用者成功，耗時 {} ms",
                today, ok, total, (System.nanoTime() - start) / 1_000_000);
    }
}
