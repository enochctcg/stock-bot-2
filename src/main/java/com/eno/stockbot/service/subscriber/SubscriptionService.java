package com.eno.stockbot.service.subscriber;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.eno.stockbot.bean.subscriber.PushSchedule;
import com.eno.stockbot.bean.subscriber.Subscriber;
import com.eno.stockbot.bean.subscriber.SubscriberSummary;
import com.eno.stockbot.mapper.subscriber.SubscriberMapper;
import com.eno.stockbot.service.telegram.Notifier;

/**
 * 訂閱者：訂閱 / 暫停 / 恢復、推播時段與間隔。
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriberMapper subscriberMapper;

    public SubscriptionService(SubscriberMapper subscriberMapper) {
        this.subscriberMapper = subscriberMapper;
    }

    public Optional<Subscriber> find(long chatId) {
        return Optional.ofNullable(subscriberMapper.selectById(chatId));
    }

    /** 已設定推播時段與間隔。 */
    public boolean isConfigured(long chatId) {
        return find(chatId).map(Subscriber::configured).orElse(false);
    }

    /** 不存在的使用者視為未啟用。 */
    public boolean isEnabled(long chatId) {
        return find(chatId).map(Subscriber::isEnabled).orElse(false);
    }

    /** 確保使用者存在，並更新 username（傳 null 時保留原值），不動其他設定。 */
    public void touch(long chatId, String username) {
        int rows = subscriberMapper.upsert(chatId, username);
        // MySQL：新增回 1、更新回 2、沒有變化回 0（大部分的呼叫都是 0，不記錄）
        if (rows == 1) {
            log.info("[DB][訂閱] 新使用者訂閱 chat {} username={}", chatId, username);
        } else if (rows == 2) {
            log.info("[DB] 使用者 chat {} username 更新為 {}", chatId, username);
        }
    }

    /** /start：建立使用者並開啟推播。 */
    public void subscribe(long chatId, String username) {
        Optional<Subscriber> before = find(chatId);
        touch(chatId, username);
        updateEnabled(chatId, true);
        if (before.isPresent()) {
            log.info("[訂閱] chat {} username={} 再次 /start：{}", chatId, username,
                    before.get().isEnabled() ? "原本就在訂閱中" : "重新訂閱（恢復推播）");
        }
    }

    /** 暫停 / 恢復定時推播。 */
    public void setEnabled(long chatId, String username, boolean enabled) {
        boolean wasEnabled = isEnabled(chatId);
        touch(chatId, username);
        updateEnabled(chatId, enabled);
        log.info("[訂閱] chat {} username={} {}{}", chatId, username,
                enabled ? "恢復訂閱推播" : "取消訂閱推播（暫停）",
                wasEnabled == enabled ? "，原本就是這個狀態" : "");
    }

    /** 使用者封鎖 bot 時由 Notifier 呼叫。 */
    public void disable(long chatId) {
        updateEnabled(chatId, false);
    }

    public void setWindow(long chatId, String username, PushSchedule.Window window) {
        touch(chatId, username);
        int rows = subscriberMapper.update(Wrappers.<Subscriber>lambdaUpdate()
                .set(Subscriber::getPushStart, window.start())
                .set(Subscriber::getPushEnd, window.end())
                .eq(Subscriber::getChatId, chatId));
        log.info("[DB] chat {} 推播時段 → {}（{} 筆）", chatId, window, rows);
    }

    public void setInterval(long chatId, String username, int minutes) {
        touch(chatId, username);
        int rows = subscriberMapper.update(Wrappers.<Subscriber>lambdaUpdate()
                .set(Subscriber::getIntervalMinutes, minutes)
                .eq(Subscriber::getChatId, chatId));
        log.info("[DB] chat {} 推播間隔 → 每 {} 分鐘（{} 筆）", chatId, minutes, rows);
    }

    private void updateEnabled(long chatId, boolean enabled) {
        int rows = subscriberMapper.update(Wrappers.<Subscriber>lambdaUpdate()
                .set(Subscriber::isEnabled, enabled)
                .eq(Subscriber::getChatId, chatId));
        log.info("[DB] chat {} 定時推播 → {}（{} 筆）", chatId, enabled ? "開啟" : "暫停", rows);
    }

    public Optional<SubscriberSummary> findSummary(long chatId) {
        return Optional.ofNullable(subscriberMapper.selectSummary(chatId));
    }

    /** 所有使用者，依訂閱時間排序。 */
    public List<SubscriberSummary> findAllSummaries() {
        return subscriberMapper.selectAllSummaries();
    }

    /** 啟用中且已設定時段與間隔的使用者，給排程判斷這一分鐘要推給誰。 */
    public List<Subscriber> findPushReady() {
        return subscriberMapper.selectList(Wrappers.<Subscriber>lambdaQuery()
                .eq(Subscriber::isEnabled, true)
                .isNotNull(Subscriber::getPushStart)
                .isNotNull(Subscriber::getPushEnd)
                .isNotNull(Subscriber::getIntervalMinutes));
    }

    /** 啟用中（沒有暫停推播、沒有封鎖 bot）的使用者，給每日 Excel 決定要送給誰。 */
    public Set<Long> findEnabledChatIds() {
        return new HashSet<>(subscriberMapper.selectObjs(Wrappers.<Subscriber>lambdaQuery()
                .select(Subscriber::getChatId)
                .eq(Subscriber::isEnabled, true)));
    }

    /** 待通知「請重新設定推播」的使用者（V2 migration 時標記的既有使用者）。 */
    public List<Subscriber> findPendingSetupNotice() {
        return subscriberMapper.selectList(Wrappers.<Subscriber>lambdaQuery()
                .eq(Subscriber::isSetupNoticePending, true));
    }

    public void clearSetupNotice(long chatId) {
        subscriberMapper.update(Wrappers.<Subscriber>lambdaUpdate()
                .set(Subscriber::isSetupNoticePending, false)
                .eq(Subscriber::getChatId, chatId));
        log.info("[DB] chat {} 已清除「待通知重新設定」標記", chatId);
    }
}
