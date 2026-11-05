package com.eno.stockbot.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.eno.stockbot.bean.Subscriber;
import com.eno.stockbot.bo.PushSchedule;
import com.eno.stockbot.bo.SubscriberSummary;
import com.eno.stockbot.repository.SubscriberRepository;

/**
 * 訂閱者：訂閱 / 暫停 / 恢復、推播時段與間隔。
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriberRepository subscribers;

    public SubscriptionService(SubscriberRepository subscribers) {
        this.subscribers = subscribers;
    }

    public Optional<Subscriber> find(long chatId) {
        return subscribers.find(chatId);
    }

    /** 已設定推播時段與間隔。 */
    public boolean isConfigured(long chatId) {
        return find(chatId).map(Subscriber::configured).orElse(false);
    }

    /** 確保使用者存在，並更新 username。 */
    public void touch(long chatId, String username) {
        subscribers.upsert(chatId, username);
    }

    /** /start：建立使用者並開啟推播。 */
    public void subscribe(long chatId, String username) {
        Optional<Subscriber> before = find(chatId);
        subscribers.upsert(chatId, username);
        subscribers.setEnabled(chatId, true);
        if (before.isPresent()) {
            log.info("[訂閱] chat {} username={} 再次 /start：{}", chatId, username,
                    before.get().enabled() ? "原本就在訂閱中" : "重新訂閱（恢復推播）");
        }
    }

    /** 暫停 / 恢復定時推播。 */
    public void setEnabled(long chatId, String username, boolean enabled) {
        boolean wasEnabled = subscribers.isEnabled(chatId);
        subscribers.upsert(chatId, username);
        subscribers.setEnabled(chatId, enabled);
        log.info("[訂閱] chat {} username={} {}{}", chatId, username,
                enabled ? "恢復訂閱推播" : "取消訂閱推播（暫停）",
                wasEnabled == enabled ? "，原本就是這個狀態" : "");
    }

    public void setWindow(long chatId, String username, PushSchedule.Window window) {
        subscribers.upsert(chatId, username);
        subscribers.setWindow(chatId, window);
    }

    public void setInterval(long chatId, String username, int minutes) {
        subscribers.upsert(chatId, username);
        subscribers.setInterval(chatId, minutes);
    }

    public Optional<SubscriberSummary> findSummary(long chatId) {
        return subscribers.findSummary(chatId);
    }

    /** 所有使用者，依訂閱時間排序。 */
    public List<SubscriberSummary> findAllSummaries() {
        return subscribers.findAllSummaries();
    }

    /** 待通知「請重新設定推播」的使用者。 */
    public List<Subscriber> findPendingSetupNotice() {
        return subscribers.findPendingSetupNotice();
    }

    public void clearSetupNotice(long chatId) {
        subscribers.clearSetupNotice(chatId);
    }
}
