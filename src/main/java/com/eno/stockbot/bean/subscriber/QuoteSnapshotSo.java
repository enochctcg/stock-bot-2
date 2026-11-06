package com.eno.stockbot.bean.subscriber;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 查詢歷史報價的條件。
 *
 * @param tradeDate 交易日（必填）
 * @param symbols   只查這些股票；null 或空的代表全部
 */
public record QuoteSnapshotSo(LocalDate tradeDate, Collection<String> symbols) {

    public QuoteSnapshotSo {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate 必填");
        }
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public static QuoteSnapshotSo ofDate(LocalDate tradeDate) {
        return new QuoteSnapshotSo(tradeDate, null);
    }
}
