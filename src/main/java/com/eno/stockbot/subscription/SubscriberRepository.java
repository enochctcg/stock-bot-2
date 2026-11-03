package com.eno.stockbot.subscription;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.eno.stockbot.common.JdbcUtil;

@Repository
public class SubscriberRepository {

    private static final String COLUMNS = "chat_id, username, enabled, push_start, push_end, interval_minutes";

    /** 訂閱資訊：給 log 顯示誰在什麼時候訂閱、目前狀態。 */
    public record Summary(long chatId, String username, boolean enabled, LocalDateTime createdAt, int watchCount) {
    }

    private static final String SUMMARY_SQL = """
            SELECT s.chat_id, s.username, s.enabled, s.created_at,
                   (SELECT COUNT(*) FROM watch w WHERE w.chat_id = s.chat_id) AS watch_count
              FROM subscriber s
            """;

    private static final Logger log = LoggerFactory.getLogger(SubscriberRepository.class);

    private final JdbcUtil db;

    public SubscriberRepository(JdbcUtil db) {
        this.db = db;
    }

    /** 新增或更新使用者；既有使用者只更新 username（傳 null 時保留原值），不動其他設定。 */
    public void upsert(long chatId, String username) {
        int rows = db.update("""
                INSERT INTO subscriber (chat_id, username) VALUES (?, ?) AS n
                ON DUPLICATE KEY UPDATE username = COALESCE(n.username, subscriber.username)
                """, chatId, username);
        // MySQL：新增回 1、更新回 2、沒有變化回 0（大部分的呼叫都是 0，不記錄）
        if (rows == 1) {
            log.info("[DB][訂閱] 新使用者訂閱 chat {} username={}", chatId, username);
        } else if (rows == 2) {
            log.info("[DB] 使用者 chat {} username 更新為 {}", chatId, username);
        }
    }

    public Optional<Subscriber> find(long chatId) {
        return db.queryOne("SELECT " + COLUMNS + " FROM subscriber WHERE chat_id = ?", Subscriber.class, chatId);
    }

    public Optional<Summary> findSummary(long chatId) {
        return db.queryOne(SUMMARY_SQL + " WHERE s.chat_id = ?", Summary.class, chatId);
    }

    /** 所有使用者，依訂閱時間排序。 */
    public List<Summary> findAllSummaries() {
        return db.queryList(SUMMARY_SQL + " ORDER BY s.created_at, s.chat_id", Summary.class);
    }

    public void setEnabled(long chatId, boolean enabled) {
        int rows = db.update("UPDATE subscriber SET enabled = ? WHERE chat_id = ?", enabled, chatId);
        log.info("[DB] chat {} 定時推播 → {}（{} 筆）", chatId, enabled ? "開啟" : "暫停", rows);
    }

    /** 不存在的使用者視為未啟用。 */
    public boolean isEnabled(long chatId) {
        return db.queryValue("SELECT enabled FROM subscriber WHERE chat_id = ?", Boolean.class, chatId)
                .orElse(false);
    }

    public void setWindow(long chatId, PushSchedule.Window window) {
        int rows = db.update("UPDATE subscriber SET push_start = ?, push_end = ? WHERE chat_id = ?",
                window.start(), window.end(), chatId);
        log.info("[DB] chat {} 推播時段 → {}（{} 筆）", chatId, window, rows);
    }

    public void setInterval(long chatId, int minutes) {
        int rows = db.update("UPDATE subscriber SET interval_minutes = ? WHERE chat_id = ?", minutes, chatId);
        log.info("[DB] chat {} 推播間隔 → 每 {} 分鐘（{} 筆）", chatId, minutes, rows);
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

    /** 啟用中（沒有暫停推播、沒有封鎖 bot）的使用者，給每日 Excel 決定要送給誰。 */
    public Set<Long> findEnabledChatIds() {
        return new HashSet<>(db.queryColumn("SELECT chat_id FROM subscriber WHERE enabled = TRUE", Long.class));
    }

    /** 待通知「請重新設定推播」的使用者（V2 migration 時標記的既有使用者）。 */
    public List<Subscriber> findPendingSetupNotice() {
        return db.queryList("SELECT " + COLUMNS + " FROM subscriber WHERE setup_notice_pending = TRUE",
                Subscriber.class);
    }

    public void clearSetupNotice(long chatId) {
        db.update("UPDATE subscriber SET setup_notice_pending = FALSE WHERE chat_id = ?", chatId);
        log.info("[DB] chat {} 已清除「待通知重新設定」標記", chatId);
    }
}
