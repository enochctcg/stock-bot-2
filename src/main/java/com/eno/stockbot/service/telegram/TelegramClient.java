package com.eno.stockbot.service.telegram;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.eno.stockbot.common.http.HttpException;
import com.eno.stockbot.common.http.HttpResult;
import com.eno.stockbot.common.http.HttpUtil;
import com.eno.stockbot.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Telegram Bot HTTP API 的最小封裝（透過共用的 {@link HttpUtil}，不依賴第三方 bot 函式庫）。
 */
@Component
public class TelegramClient {

    /** getUpdates long polling 秒數。 */
    public static final int POLL_TIMEOUT_SECONDS = 30;

    private final HttpUtil http;
    private final String baseUrl;

    public TelegramClient(HttpUtil http, BotProperties props) {
        this.http = http;
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
        sendMessage(chatId, text, replyMarkup, false);
    }

    /** @param html text 為 HTML 格式（例如報價表格的 &lt;pre&gt;）；一般訊息用純文字，避免使用者輸入的 &lt; 造成解析錯誤 */
    public void sendMessage(long chatId, String text, Object replyMarkup, boolean html) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (html) {
            body.put("parse_mode", "HTML");
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        call("sendMessage", body, Duration.ofSeconds(15));
    }

    /** 傳送檔案（例如每日 Excel），caption 為檔案下方的說明文字，可為 null。 */
    public void sendDocument(long chatId, String fileName, byte[] content, String caption) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chat_id", String.valueOf(chatId));
        if (caption != null && !caption.isBlank()) {
            fields.put("caption", caption);
        }
        try {
            handle(http.postMultipart(baseUrl + "sendDocument", fields,
                    new HttpUtil.FilePart("document", fileName, content), Duration.ofSeconds(60)));
        } catch (HttpException e) {
            throw new TelegramApiException(0, e.getMessage(), 0);
        }
    }

    /** 原地更新訊息內容與 inline 按鈕。 */
    public void editMessageText(long chatId, long messageId, String text, Object inlineMarkup, boolean html) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        if (html) {
            body.put("parse_mode", "HTML");
        }
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
            return handle(http.postJson(baseUrl + method, body, timeout));
        } catch (HttpException e) {
            throw new TelegramApiException(0, e.getMessage(), 0);   // errorCode 0 = 網路錯誤，Notifier 會重試
        }
    }

    /** Telegram 不論成功失敗都回 JSON：{"ok": true, "result": ...} 或 {"ok": false, "error_code": ...}。 */
    private JsonNode handle(HttpResult result) {
        JsonNode json;
        try {
            json = http.readTree(result.body());
        } catch (HttpException e) {
            throw new TelegramApiException(result.status(), e.getMessage(), 0);
        }
        if (!json.path("ok").asBoolean(false)) {
            throw new TelegramApiException(
                    json.path("error_code").asInt(result.status()),
                    json.path("description").asText("unknown error"),
                    json.path("parameters").path("retry_after").asInt(0));
        }
        return json.path("result");
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
