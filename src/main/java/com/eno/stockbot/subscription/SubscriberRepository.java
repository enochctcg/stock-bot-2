package com.eno.stockbot.subscription;

import org.springframework.stereotype.Repository;

import com.eno.stockbot.common.JdbcUtil;

@Repository
public class SubscriberRepository {

    private final JdbcUtil db;

    public SubscriberRepository(JdbcUtil db) {
        this.db = db;
    }

    /** 新增或更新使用者；既有使用者只更新 username（傳 null 時保留原值），不動 enabled。 */
    public void upsert(long chatId, String username) {
        db.update("""
                INSERT INTO subscriber (chat_id, username) VALUES (?, ?) AS n
                ON DUPLICATE KEY UPDATE username = COALESCE(n.username, subscriber.username)
                """, chatId, username);
    }

    public void setEnabled(long chatId, boolean enabled) {
        db.update("UPDATE subscriber SET enabled = ? WHERE chat_id = ?", enabled, chatId);
    }

    /** 不存在的使用者視為未啟用。 */
    public boolean isEnabled(long chatId) {
        return db.queryValue("SELECT enabled FROM subscriber WHERE chat_id = ?", Boolean.class, chatId)
                .orElse(false);
    }
}
