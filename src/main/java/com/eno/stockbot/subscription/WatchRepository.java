package com.eno.stockbot.subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.eno.stockbot.common.JdbcUtil;

@Repository
public class WatchRepository {

    /** 欄位 chat_id / symbol / name 由 JdbcUtil 自動對應到 record 的 chatId / symbol / name。 */
    public record Watch(long chatId, String symbol, String name) {
    }

    private static final Logger log = LoggerFactory.getLogger(WatchRepository.class);

    private final JdbcUtil db;

    public WatchRepository(JdbcUtil db) {
        this.db = db;
    }

    public void add(long chatId, String symbol, String name) {
        int rows = db.update("""
                INSERT INTO watch (chat_id, symbol, name) VALUES (?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE name = n.name
                """, chatId, symbol, name);
        // MySQL：新增回 1、已存在且名稱有變回 2、完全相同回 0
        log.info("[DB] 觀察清單{} chat {}：{} {}", rows == 1 ? "新增" : "已存在", chatId, symbol, name);
    }

    /** @return 是否真的有刪除資料 */
    public boolean remove(long chatId, String symbol) {
        int rows = db.update("DELETE FROM watch WHERE chat_id = ? AND symbol = ?", chatId, symbol);
        log.info("[DB] 觀察清單移除 chat {}：{}（{}）", chatId, symbol, rows > 0 ? "已刪除" : "不存在");
        return rows > 0;
    }

    public boolean exists(long chatId, String symbol) {
        return db.exists("SELECT 1 FROM watch WHERE chat_id = ? AND symbol = ? LIMIT 1", chatId, symbol);
    }

    public int count(long chatId) {
        return (int) db.count("SELECT COUNT(*) FROM watch WHERE chat_id = ?", chatId);
    }

    public List<Watch> findByChatId(long chatId) {
        return db.queryList("SELECT chat_id, symbol, name FROM watch WHERE chat_id = ? ORDER BY id",
                Watch.class, chatId);
    }

    /** 所有人觀察清單的聯集（每檔一次），給歷史報價紀錄用。 */
    public List<String> findAllSymbols() {
        return db.queryColumn("SELECT DISTINCT symbol FROM watch ORDER BY symbol", String.class);
    }

    /** 所有人的觀察清單，依 chat_id 分組並保留加入順序；要推給誰由排程依各自的推播設定過濾。 */
    public Map<Long, List<String>> findGroupedByChat() {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        db.forEach("""
                SELECT chat_id, symbol
                  FROM watch
                 ORDER BY chat_id, id
                """,
                rs -> {
                    result.computeIfAbsent(rs.getLong("chat_id"), k -> new ArrayList<>())
                            .add(rs.getString("symbol"));
                });
        return result;
    }
}
