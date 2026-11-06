package com.eno.stockbot.service.subscriber;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.eno.stockbot.bean.subscriber.QuoteSnapshot;
import com.eno.stockbot.service.telegram.Notifier;
import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.bean.subscriber.QuoteSnapshotSo;

/**
 * 每日 Excel：把當天的歷史報價整理成 Excel 推給每位使用者（只含自己的觀察清單），
 * 並刪除超過保留期限的舊資料。已暫停推播（或封鎖 bot）的使用者不送。
 */
@Service
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    private final QuoteHistoryService history;
    private final SubscriptionService subscriptions;
    private final WatchService watches;
    private final Notifier notifier;
    private final Period retention;

    public DailyReportService(QuoteHistoryService history, SubscriptionService subscriptions,
                              WatchService watches, Notifier notifier, BotProperties props) {
        this.history = history;
        this.subscriptions = subscriptions;
        this.watches = watches;
        this.notifier = notifier;
        this.retention = props.history().retention() == null ? Period.ofMonths(1) : props.history().retention();
    }

    public void sendReports(LocalDate today) {
        Set<Long> enabled = subscriptions.findEnabledChatIds();

        long start = System.nanoTime();
        int ok = 0;
        int total = 0;
        for (Map.Entry<Long, List<String>> e : watches.findGroupedByChat().entrySet()) {
            long chatId = e.getKey();
            List<String> symbols = e.getValue();
            if (!enabled.contains(chatId)) {
                continue;
            }
            MDC.put("chatId", String.valueOf(chatId));
            try {
                List<QuoteSnapshot> snapshots = history.find(new QuoteSnapshotSo(today, symbols));
                if (snapshots.isEmpty()) {
                    continue;   // 休市，或清單裡的股票都還沒記錄到
                }
                total++;
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
        if (total == 0) {
            log.info("{} 沒有可推送的歷史報價（休市或沒有被訂閱的股票），不產生每日 Excel", today);
            return;
        }
        log.info("{} 每日 Excel 推送完成：{}/{} 位使用者成功，耗時 {} ms",
                today, ok, total, (System.nanoTime() - start) / 1_000_000);
    }

    /** 刪除超過保留期限的歷史報價。 */
    public void purgeExpired(LocalDate today) {
        history.deleteBefore(today.minus(retention));
    }
}
