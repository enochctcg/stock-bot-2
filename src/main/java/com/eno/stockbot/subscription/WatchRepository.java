package com.eno.stockbot.subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.eno.stockbot.common.JdbcUtil;

@Repository
public class WatchRepository {

    /** 欄位 chat_id / symbol / name 由 JdbcUtil 自動對應到 record 的 chatId / symbol / name。 */
    public record Watch(long chatId, String symbol, String name) {
    }

    private final JdbcUtil db;

    public WatchRepository(JdbcUtil db) {
        this.db = db;
    }

    public void add(long chatId, String symbol, String name) {
        db.update("""
                INSERT INTO watch (chat_id, symbol, name) VALUES (?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE name = n.name
                """, chatId, symbol, name);
    }

    /** @return 是否真的有刪除資料 */
    public boolean remove(long chatId, String symbol) {
        return db.update("DELETE FROM watch WHERE chat_id = ? AND symbol = ?", chatId, symbol) > 0;
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

    /** 所有啟用中使用者的觀察清單，依 chat_id 分組並保留加入順序。 */
    public Map<Long, List<String>> findActiveGroupedByChat() {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        db.forEach("""
                SELECT w.chat_id, w.symbol
                  FROM watch w
                  JOIN subscriber s ON s.chat_id = w.chat_id
                 WHERE s.enabled = TRUE
                 ORDER BY w.chat_id, w.id
                """,
                rs -> {
                    result.computeIfAbsent(rs.getLong("chat_id"), k -> new ArrayList<>())
                            .add(rs.getString("symbol"));
                });
        return result;
    }
}
