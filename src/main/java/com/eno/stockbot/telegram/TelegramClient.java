package com.eno.stockbot.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.eno.stockbot.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Telegram Bot HTTP API 的最小封裝（直接用 JDK HttpClient，不依賴第三方 bot 函式庫）。
 */
@Component
public class TelegramClient {

    /** getUpdates long polling 秒數。 */
    public static final int POLL_TIMEOUT_SECONDS = 30;

    private final HttpClient http;
    private final ObjectMapper om;
    private final String baseUrl;

        public TelegramClient(HttpClient http, ObjectMapper om, BotProperties props) {
        this.http = http;
        this.om = om;
        this.baseUrl = "https://api.telegram.org/bot" + props.telegram().token() + "/";
    }

    public JsonNode getUpdates(long offset) {
        Map<String, Object> body = Map.of(
                "offset", offset,
                "timeout", POLL_TIMEOUT_SECONDS,
                "allowed_updates", List.of("message", "callback_query"));
        return call("getUpdates", body, Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 15));
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    /** @param replyMarkup 鍵盤（見 {@link Keyboards}），可為 null */
    public void sendMessage(long chatId, String text, Object replyMarkup) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        call("sendMessage", body, Duration.ofSeconds(15));
    }

    /** 原地更新訊息內容與 inline 按鈕。 */
    public void editMessageText(long chatId, long messageId, String text, Object inlineMarkup) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        if (inlineMarkup != null) {
            body.put("reply_markup", inlineMarkup);
        }
        call("editMessageText", body, Duration.ofSeconds(15));
    }

    /** 結束按鈕上的讀取動畫；text 不為空時會在畫面上方跳出小提示。 */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {
            body.put("text", text.length() > 200 ? text.substring(0, 200) : text);
        }
        call("answerCallbackQuery", body, Duration.ofSeconds(10));
    }

    /** 設定輸入框旁的指令選單。 */
    public void setMyCommands(Map<String, String> commands) {
        List<Map<String, String>> list = commands.entrySet().stream()
                .map(e -> Map.of("command", e.getKey(), "description", e.getValue()))
                .toList();
        call("setMyCommands", Map.of("commands", list), Duration.ofSeconds(15));
    }

    /** 若曾設定過 webhook，getUpdates 會回 409，啟動時先清掉。 */
    public void deleteWebhook() {
        call("deleteWebhook", Map.of("drop_pending_updates", false), Duration.ofSeconds(15));
    }

    private JsonNode call(String method, Object body, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + method))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = om.readTree(resp.body());

            if (!json.path("ok").asBoolean(false)) {
                throw new TelegramApiException(
                        json.path("error_code").asInt(resp.statusCode()),
                        json.path("description").asText("unknown error"),
                        json.path("parameters").path("retry_after").asInt(0));
            }
            return json.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException(0, "interrupted", 0);
        } catch (IOException e) {
            throw new TelegramApiException(0, "network error: " + e.getMessage(), 0);
        }
    }

    public static class TelegramApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int errorCode;
        private final String description;
        private final int retryAfterSeconds;

        public TelegramApiException(int errorCode, String description, int retryAfterSeconds) {
            super("Telegram API error " + errorCode + ": " + description);
            this.errorCode = errorCode;
            this.description = description;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int errorCode() {
            return errorCode;
        }

        public String description() {
            return description;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }

        /** 編輯後內容與原本完全相同時，Telegram 會回 400 message is not modified，可以忽略。 */
        public boolean isMessageNotModified() {
            return errorCode == 400 && description != null && description.contains("message is not modified");
        }
    }
}
