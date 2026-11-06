package com.eno.stockbot.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.eno.stockbot.service.fugle.FugleClient;
import com.eno.stockbot.service.telegram.TelegramClient;

/**
 * 共用的 HTTP 請求（JDK HttpClient 封裝），給 TelegramClient、FugleClient 使用：
 * <ul>
 *   <li>GET、POST JSON、POST multipart/form-data（上傳檔案）</li>
 *   <li>統一處理逾時、連線失敗、執行緒中斷 → {@link HttpException}</li>
 *   <li>DEBUG 等級記錄每次請求的狀態碼與耗時；網址中的 bot token 會遮蔽</li>
 * </ul>
 * 只要拿到 HTTP 回應（不論狀態碼）就回傳 {@link HttpResult}，重試與錯誤判斷由各 Client 自己處理。
 */
@Component
public class HttpUtil {

    private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);

    /** Telegram 的網址是 https://api.telegram.org/bot{token}/method，log 不能印出 token。 */
    private static final Pattern BOT_TOKEN = Pattern.compile("/bot[^/]+/");

    private final HttpClient http;
    private final ObjectMapper om;

    public HttpUtil(HttpClient http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    public HttpResult get(String url, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(builder::header);
        return send(builder.build());
    }

    /** body 會用 Jackson 轉成 JSON。 */
    public HttpResult postJson(String url, Object body, Duration timeout) {
        String json;
        try {
            json = om.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new HttpException("無法轉成 JSON: " + e.getMessage(), e);
        }
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    /** 上傳一個檔案，fields 為一般表單欄位。 */
    public HttpResult postMultipart(String url, Map<String, String> fields, FilePart file, Duration timeout) {
        String boundary = "----stock-bot-" + UUID.randomUUID();
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, fields, file)))
                .build());
    }

    /** multipart 裡的檔案欄位。 */
    public record FilePart(String field, String fileName, byte[] content) {
    }

    /** 解析 JSON 回應。 */
    public JsonNode readTree(String body) {
        try {
            return om.readTree(body);
        } catch (IOException e) {
            throw new HttpException("無法解析 JSON 回應: " + abbreviate(body), e);
        }
    }

    public static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private HttpResult send(HttpRequest request) {
        String target = request.method() + " " + mask(request.uri().toString());
        long start = System.nanoTime();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.debug("{} → HTTP {}，{} ms", target, resp.statusCode(), (System.nanoTime() - start) / 1_000_000);
            return new HttpResult(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpException("interrupted", e);
        } catch (IOException e) {
            log.debug("{} 失敗，{} ms: {}", target, (System.nanoTime() - start) / 1_000_000, e.toString());
            throw new HttpException("network error: " + e.getMessage(), e);
        }
    }

    static String mask(String url) {
        return BOT_TOKEN.matcher(url).replaceFirst("/bot***/");
    }

    /** 組 multipart/form-data 內容：一般欄位 + 一個檔案。 */
    static byte[] multipart(String boundary, Map<String, String> fields, FilePart file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fields.forEach((name, value) -> write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n"));
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + file.field() + "\"; filename=\""
                + file.fileName().replace("\"", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(file.content());
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
