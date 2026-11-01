package com.eno.stockbot.subscription;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.eno.stockbot.common.JdbcUtil;

@Repository
public class SubscriberRepository {

    private static final String COLUMNS = "chat_id, username, enabled, push_start, push_end, interval_minutes";

    private final JdbcUtil db;

    public SubscriberRepository(JdbcUtil db) {
        this.db = db;
    }

    /** 新增或更新使用者；既有使用者只更新 username（傳 null 時保留原值），不動其他設定。 */
    public void upsert(long chatId, String username) {
        db.update("""
                INSERT INTO subscriber (chat_id, username) VALUES (?, ?) AS n
                ON DUPLICATE KEY UPDATE username = COALESCE(n.username, subscriber.username)
                """, chatId, username);
    }

    public Optional<Subscriber> find(long chatId) {
        return db.queryOne("SELECT " + COLUMNS + " FROM subscriber WHERE chat_id = ?", Subscriber.class, chatId);
    }

    public void setEnabled(long chatId, boolean enabled) {
        db.update("UPDATE subscriber SET enabled = ? WHERE chat_id = ?", enabled, chatId);
    }

    /** 不存在的使用者視為未啟用。 */
    public boolean isEnabled(long chatId) {
        return db.queryValue("SELECT enabled FROM subscriber WHERE chat_id = ?", Boolean.class, chatId)
                .orElse(false);
    }

    public void setWindow(long chatId, PushSchedule.Window window) {
        db.update("UPDATE subscriber SET push_start = ?, push_end = ? WHERE chat_id = ?",
                window.start(), window.end(), chatId);
    }

    public void setInterval(long chatId, int minutes) {
        db.update("UPDATE subscriber SET interval_minutes = ? WHERE chat_id = ?", minutes, chatId);
    }

    /** 啟用中且已設定時段與間隔的使用者，給排程判斷這一分鐘要推給誰。 */
    public List<Subscriber> findPushReady() {
        return db.queryList("""
                SELECT %s
                  FROM subscriber
                 WHERE enabled = TRUE
                   AND push_start IS NOT NULL
                   AND push_end IS NOT NULL
                   AND interval_minutes IS NOT NULL
                """.formatted(COLUMNS), Subscriber.class);
    }

    /** 待通知「請重新設定推播」的使用者（V2 migration 時標記的既有使用者）。 */
    public List<Subscriber> findPendingSetupNotice() {
        return db.queryList("SELECT " + COLUMNS + " FROM subscriber WHERE setup_notice_pending = TRUE",
                Subscriber.class);
    }

    public void clearSetupNotice(long chatId) {
        db.update("UPDATE subscriber SET setup_notice_pending = FALSE WHERE chat_id = ?", chatId);
    }
}
