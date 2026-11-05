package com.eno.stockbot.bean;

/**
 * watch 資料表的一列：某位使用者觀察清單裡的一檔股票。
 * 欄位 chat_id / symbol / name 由 JdbcUtil 自動對應到 chatId / symbol / name。
 */
public record Watch(long chatId, String symbol, String name) {
}
