package com.eno.stockbot.service.subscriber;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.eno.stockbot.bean.fugle.Quote;
import com.eno.stockbot.bean.subscriber.QuoteSnapshot;
import com.eno.stockbot.bean.subscriber.QuoteSnapshotSo;
import com.eno.stockbot.mapper.subscriber.QuoteSnapshotMapper;

/**
 * 歷史報價（quote_snapshot）的存取。
 */
@Service
public class QuoteHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QuoteHistoryService.class);

    private final QuoteSnapshotMapper snapshotMapper;

    public QuoteHistoryService(QuoteSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    /** 同一時間點重複記錄（例如服務重啟後補跑）會覆蓋成最新的報價。 */
    public void save(LocalDate tradeDate, LocalTime time, Collection<Quote> quotes) {
        if (quotes.isEmpty()) {
            return;
        }
        List<QuoteSnapshot> rows = quotes.stream().map(q -> QuoteSnapshot.of(tradeDate, time, q)).toList();
        snapshotMapper.upsertBatch(rows);
        log.info("[DB] 記錄 {} {} 報價 {} 檔", tradeDate, time, rows.size());
    }

    /** 依條件查詢紀錄，依時間、代號排序。 */
    public List<QuoteSnapshot> find(QuoteSnapshotSo so) {
        return snapshotMapper.selectList(Wrappers.<QuoteSnapshot>lambdaQuery()
                .eq(QuoteSnapshot::getTradeDate, so.tradeDate())
                .in(!so.symbols().isEmpty(), QuoteSnapshot::getSymbol, so.symbols())
                .orderByAsc(QuoteSnapshot::getSnapshotTime, QuoteSnapshot::getSymbol));
    }

    /** 刪除 cutoff 之前（不含）的紀錄，回傳刪除筆數。 */
    public int deleteBefore(LocalDate cutoff) {
        int rows = snapshotMapper.delete(Wrappers.<QuoteSnapshot>lambdaQuery()
                .lt(QuoteSnapshot::getTradeDate, cutoff));
        log.info("[DB] 刪除 {} 之前的歷史報價 {} 筆", cutoff, rows);
        return rows;
    }
}
