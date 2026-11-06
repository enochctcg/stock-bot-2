package com.eno.stockbot.bean.subscriber;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.eno.stockbot.bean.fugle.Quote;

import lombok.Data;

/**
 * quote_snapshot 資料表：某檔股票在某個時間點的報價。
 */
@Data
@TableName("quote_snapshot")
public class QuoteSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 交易日 */
    private LocalDate tradeDate;
    /** 記錄時間（台北時間，到分鐘） */
    private LocalTime snapshotTime;
    private String symbol;
    private String name;
    /** 成交價；尚無成交時為 null */
    private Double price;
    /** 漲跌（元） */
    private Double changeAmount;
    /** 漲跌幅（%） */
    private Double changePercent;
    private LocalDateTime createdAt;

    public static QuoteSnapshot of(LocalDate tradeDate, LocalTime snapshotTime, String symbol, String name,
                                   Double price, Double changeAmount, Double changePercent) {
        QuoteSnapshot s = new QuoteSnapshot();
        s.setTradeDate(tradeDate);
        s.setSnapshotTime(snapshotTime);
        s.setSymbol(symbol);
        s.setName(name);
        s.setPrice(price);
        s.setChangeAmount(changeAmount);
        s.setChangePercent(changePercent);
        return s;
    }

    /** 把富果的報價轉成某個時間點的紀錄。 */
    public static QuoteSnapshot of(LocalDate tradeDate, LocalTime snapshotTime, Quote q) {
        return of(tradeDate, snapshotTime, q.symbol(), q.name(), q.price(), q.change(), q.changePercent());
    }
}
