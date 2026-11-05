package com.eno.stockbot.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.eno.stockbot.bean.QuoteSnapshot;
import com.eno.stockbot.common.JdbcUtil;
import com.eno.stockbot.dto.Quote;
import com.eno.stockbot.so.QuoteSnapshotSo;

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

    /** 依條件查詢紀錄，依時間、代號排序。 */
    public List<QuoteSnapshot> find(QuoteSnapshotSo so) {
        StringBuilder sql = new StringBuilder("""
                SELECT trade_date, snapshot_time, symbol, name, price, change_amount, change_percent
                  FROM quote_snapshot
                 WHERE trade_date = ?""");
        List<Object> args = new ArrayList<>();
        args.add(so.tradeDate());
        if (!so.symbols().isEmpty()) {
            sql.append(" AND symbol IN (").append(String.join(", ", Collections.nCopies(so.symbols().size(), "?")))
                    .append(")");
            args.addAll(so.symbols());
        }
        sql.append(" ORDER BY snapshot_time, symbol");
        return db.queryList(sql.toString(), QuoteSnapshot.class, args.toArray());
    }

    /** 刪除 cutoff 之前（不含）的紀錄，回傳刪除筆數。 */
    public int deleteBefore(LocalDate cutoff) {
        int rows = db.update("DELETE FROM quote_snapshot WHERE trade_date < ?", cutoff);
        log.info("[DB] 刪除 {} 之前的歷史報價 {} 筆", cutoff, rows);
        return rows;
    }
}
