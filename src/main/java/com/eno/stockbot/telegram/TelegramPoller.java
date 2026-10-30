package com.eno.stockbot.telegram;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

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
        JsonNode callback = update.path("callback_query");
        if (!callback.isMissingNode()) {
            Incoming.Callback cb = new Incoming.Callback(
                    callback.path("id").asText(),
                    callback.path("message").path("chat").path("id").asLong(),
                    callback.path("message").path("message_id").asLong(),
                    callback.path("from").path("username").asText(null),
                    callback.path("data").asText(""));
            workers.submit(() -> handler.handleCallback(cb));
            return;
        }

        JsonNode message = update.path("message");
        String text = message.path("text").asText(null);
        if (text == null) {
            return;
        }
        JsonNode replyTo = message.path("reply_to_message");
        Incoming.Message msg = new Incoming.Message(
                message.path("chat").path("id").asLong(),
                message.path("from").path("username").asText(null),
                text,
                replyTo.isMissingNode() ? null : replyTo.path("text").asText(null),
                "private".equals(message.path("chat").path("type").asText()));
        workers.submit(() -> handler.handleMessage(msg));
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
