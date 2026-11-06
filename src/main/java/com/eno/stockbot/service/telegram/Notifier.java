package com.eno.stockbot.service.telegram;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eno.stockbot.service.subscriber.SubscriptionService;

/**
 * 發送訊息：處理 Telegram 的 429 限流、使用者封鎖 bot（403）、訊息長度上限。
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    /** Telegram 單則訊息上限 4096 字元，留一點餘裕。 */
    static final int MAX_LENGTH = 4000;
    /** 全域每秒最多約 30 則，兩則之間至少間隔 40ms。 */
    private static final long MIN_INTERVAL_MS = 40;

    private final TelegramClient telegram;
    private final SubscriptionService subscriptions;
    private long lastSentAt = 0;

    public Notifier(TelegramClient telegram, SubscriptionService subscriptions) {
        this.telegram = telegram;
        this.subscriptions = subscriptions;
    }

    public boolean send(long chatId, String text) {
        return send(chatId, text, null);
    }

    /**
     * @param replyMarkup 鍵盤；訊息被切成多段時只附在最後一段
     * @return 所有片段都送達才回傳 true
     */
    public boolean send(long chatId, String text, Object replyMarkup) {
        return send(chatId, text, replyMarkup, false);
    }

    /**
     * HTML 格式的訊息（報價表格）。切段會把 &lt;pre&gt; 拆壞，所以不切；
     * 觀察清單每人上限 20 檔，表格遠小於單則訊息上限。
     */
    public boolean sendHtml(long chatId, String text, Object replyMarkup) {
        return send(chatId, text, replyMarkup, true);
    }

    private boolean send(long chatId, String text, Object replyMarkup, boolean html) {
        List<String> parts = html ? List.of(text) : split(text);
        boolean allSent = true;
        for (int i = 0; i < parts.size(); i++) {
            Object markup = i == parts.size() - 1 ? replyMarkup : null;
            Result r = sendOne(chatId, parts.get(i), markup, html);
            if (r == Result.SENT) {
                log.info("已送出訊息 → chat {}{}：{}", chatId,
                        parts.size() > 1 ? "（第 " + (i + 1) + "/" + parts.size() + " 段）" : "", preview(parts.get(i)));
            } else {
                allSent = false;
                if (r == Result.BLOCKED) {
                    return false;   // 封鎖了，後面的片段不用再送
                }
            }
        }
        return allSent;
    }

    /** 傳送檔案；限流、封鎖的處理和文字訊息相同。 */
    public boolean sendDocument(long chatId, String fileName, byte[] content, String caption) {
        Result r = deliver(chatId, () -> telegram.sendDocument(chatId, fileName, content, caption));
        if (r == Result.SENT) {
            log.info("已送出檔案 → chat {}：{}（{} KB）", chatId, fileName, (content.length + 1023) / 1024);
        }
        return r == Result.SENT;
    }

    private enum Result { SENT, BLOCKED, FAILED }

    private Result sendOne(long chatId, String text, Object replyMarkup, boolean html) {
        return deliver(chatId, () -> telegram.sendMessage(chatId, text, replyMarkup, html));
    }

    private Result deliver(long chatId, Runnable call) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            pace();
            try {
                call.run();
                return Result.SENT;
            } catch (TelegramClient.TelegramApiException e) {
                if (e.errorCode() == 403) {
                    log.warn("[訂閱] chat {} 已封鎖 bot → 自動取消訂閱推播", chatId);
                    subscriptions.disable(chatId);
                    return Result.BLOCKED;
                }
                if (e.errorCode() == 429 && e.retryAfterSeconds() > 0) {
                    log.warn("送給 chat {} 被 Telegram 限流，{} 秒後重試（第 {} 次）", chatId, e.retryAfterSeconds(), attempt);
                    sleepMillis(e.retryAfterSeconds() * 1000L);
                    continue;
                }
                if (e.errorCode() == 0 && attempt < 3) {   // 網路錯誤
                    log.warn("送給 chat {} 網路錯誤，{} 秒後重試（第 {} 次）: {}", chatId, attempt, attempt, e.getMessage());
                    sleepMillis(1000L * attempt);
                    continue;
                }
                log.warn("發送給 chat {} 失敗: {}", chatId, e.getMessage());
                return Result.FAILED;
            }
        }
        log.warn("發送給 chat {} 失敗：重試 3 次仍未成功", chatId);
        return Result.FAILED;
    }

    /** log 只印第一行的前 60 個字，避免整份報價洗版。 */
    public static String preview(String text) {
        String first = text.lines().findFirst().orElse("");
        String more = text.contains("\n") ? " …（共 " + text.lines().count() + " 行）" : "";
        return (first.length() > 60 ? first.substring(0, 60) + "…" : first) + more;
    }

    private synchronized void pace() {
        long wait = lastSentAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            sleepMillis(wait);
        }
        lastSentAt = System.currentTimeMillis();
    }

    /** 依換行切段，避免超過單則長度上限。 */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (current.length() > 0 && current.length() + line.length() + 1 > MAX_LENGTH) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line.length() > MAX_LENGTH ? line.substring(0, MAX_LENGTH) : line);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
