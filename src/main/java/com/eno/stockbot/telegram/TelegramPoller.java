package com.eno.stockbot.telegram;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.eno.stockbot.bot.CommandHandler;
import com.eno.stockbot.bot.Incoming;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 用 getUpdates long polling 接收訊息：不需要公網 IP 或 HTTPS 網域。
 * 每則訊息交給 virtual thread 處理，/now 等待富果時不會卡住收訊。
 */
@Component
public class TelegramPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramClient telegram;
    private final CommandHandler handler;
    /** virtual thread 預設沒有名稱，log 的 [%thread] 會是空的；命名成 tg-update-1、tg-update-2 ... */
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tg-update-", 1).factory());

    private volatile boolean running;
    private Thread pollThread;
    private long offset = 0;

    public TelegramPoller(TelegramClient telegram, CommandHandler handler) {
        this.telegram = telegram;
        this.handler = handler;
    }

    @Override
    public void start() {
        try {
            telegram.deleteWebhook();
            telegram.setMyCommands(CommandHandler.MENU);
        } catch (TelegramClient.TelegramApiException e) {
            // 401 代表 token 錯誤，直接讓程式啟動失敗比較好發現
            if (e.errorCode() == 401) {
                throw new IllegalStateException("TELEGRAM_BOT_TOKEN 無效", e);
            }
            log.warn("初始化 bot 指令選單失敗: {}", e.getMessage());
        }

        running = true;
        pollThread = new Thread(this::loop, "telegram-poller");
        pollThread.start();
        log.info("Telegram long polling 已啟動");
    }

    private void loop() {
        int failures = 0;
        while (running) {
            try {
                JsonNode updates = telegram.getUpdates(offset);
                failures = 0;
                for (JsonNode update : updates) {
                    offset = update.path("update_id").asLong() + 1;
                    dispatch(update);
                }
            } catch (TelegramClient.TelegramApiException e) {
                if (!running) {
                    break;
                }
                failures++;
                long backoff = Math.min(60_000L, 1_000L * (1L << Math.min(failures, 6)));
                log.warn("getUpdates 失敗（第 {} 次），{} ms 後重試: {}", failures, backoff, e.getMessage());
                sleep(backoff);
            }
        }
    }

    private void dispatch(JsonNode update) {
        long updateId = update.path("update_id").asLong();
        JsonNode callback = update.path("callback_query");
        if (!callback.isMissingNode()) {
            Incoming.Callback cb = new Incoming.Callback(
                    callback.path("id").asText(),
                    callback.path("message").path("chat").path("id").asLong(),
                    callback.path("message").path("message_id").asLong(),
                    callback.path("from").path("username").asText(null),
                    callback.path("data").asText(""));
            submit(updateId, cb.chatId(), "按鈕 data=" + cb.data() + " msg=" + cb.messageId() + " user=" + cb.username(),
                    () -> handler.handleCallback(cb));
            return;
        }

        JsonNode message = update.path("message");
        String text = message.path("text").asText(null);
        if (text == null) {
            log.info("略過 update {}（不是文字訊息或按鈕）", updateId);
            return;
        }
        JsonNode replyTo = message.path("reply_to_message");
        Incoming.Message msg = new Incoming.Message(
                message.path("chat").path("id").asLong(),
                message.path("from").path("username").asText(null),
                text,
                replyTo.isMissingNode() ? null : replyTo.path("text").asText(null),
                "private".equals(message.path("chat").path("type").asText()));
        submit(updateId, msg.chatId(),
                "訊息 \"" + oneLine(text) + "\"" + (msg.replyToText() != null ? "（回覆提示）" : "") + " user=" + msg.username(),
                () -> handler.handleMessage(msg));
    }

    /**
     * 交給 virtual thread 處理，並在 MDC 放入 chatId / updateId：
     * 這筆 update 處理過程中的每一行 log（含 CommandHandler、Repository、Notifier）都會帶上這兩個值。
     */
    private void submit(long updateId, long chatId, String summary, Runnable task) {
        workers.submit(() -> {
            MDC.put("updateId", String.valueOf(updateId));
            MDC.put("chatId", String.valueOf(chatId));
            long start = System.nanoTime();
            log.info("收到 {}", summary);
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("處理 update 失敗", e);
            } finally {
                log.info("處理完成，耗時 {} ms", (System.nanoTime() - start) / 1_000_000);
                MDC.clear();
            }
        });
    }

    private static String oneLine(String text) {
        String s = text.replaceAll("\\s+", " ");
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    @Override
    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        workers.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
