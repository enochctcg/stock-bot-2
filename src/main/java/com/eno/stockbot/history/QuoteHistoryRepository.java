package com.eno.stockbot.history;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.eno.stockbot.common.JdbcUtil;
import com.eno.stockbot.market.Quote;

@Repository
public class QuoteHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(QuoteHistoryRepository.class);

    private final JdbcUtil db;

    public QuoteHistoryRepository(JdbcUtil db) {
        this.db = db;
    }

    /** 同一時間點重複記錄（例如服務重啟後補跑）會覆蓋成最新的報價。 */
    public void save(LocalDate tradeDate, LocalTime time, Collection<Quote> quotes) {
        List<Object[]> rows = quotes.stream()
                .map(q -> new Object[] {tradeDate, time, q.symbol(), q.name(), q.price(), q.change(), q.changePercent()})
                .toList();
        db.batchUpdate("""
                INSERT INTO quote_snapshot
                       (trade_date, snapshot_time, symbol, name, price, change_amount, change_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE name = n.name, price = n.price,
                       change_amount = n.change_amount, change_percent = n.change_percent
                """, rows);
        log.info("[DB] 記錄 {} {} 報價 {} 檔", tradeDate, time, rows.size());
    }

    /** 某一天所有股票的紀錄，依時間排序。 */
    public List<QuoteSnapshot> findByDate(LocalDate tradeDate) {
        return db.queryList("""
                SELECT trade_date, snapshot_time, symbol, name, price, change_amount, change_percent
                  FROM quote_snapshot
                 WHERE trade_date = ?
                 ORDER BY snapshot_time, symbol
                """, QuoteSnapshot.class, tradeDate);
    }

    /** 刪除 cutoff 之前（不含）的紀錄，回傳刪除筆數。 */
    public int deleteBefore(LocalDate cutoff) {
        int rows = db.update("DELETE FROM quote_snapshot WHERE trade_date < ?", cutoff);
        log.info("[DB] 刪除 {} 之前的歷史報價 {} 筆", cutoff, rows);
        return rows;
    }
}
