package com.eno.stockbot.history;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * quote_snapshot 資料表的一列：某檔股票在某個時間點的報價。
 *
 * @param price         成交價；尚無成交時為 null
 * @param changeAmount  漲跌（元）
 * @param changePercent 漲跌幅（%）
 */
public record QuoteSnapshot(
        LocalDate tradeDate,
        LocalTime snapshotTime,
        String symbol,
        String name,
        Double price,
        Double changeAmount,
        Double changePercent) {
}
