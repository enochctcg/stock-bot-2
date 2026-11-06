package com.eno.stockbot.common.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HttpUtilTest {

    @Test
    void multipartContainsFieldsAndFile() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chat_id", "123");
        fields.put("caption", "📑 報價");

        byte[] body = HttpUtil.multipart("B", fields,
                new HttpUtil.FilePart("document", "股價紀錄.xlsx", "DATA".getBytes(StandardCharsets.UTF_8)));

        assertEquals("""
                --B\r
                Content-Disposition: form-data; name="chat_id"\r
                \r
                123\r
                --B\r
                Content-Disposition: form-data; name="caption"\r
                \r
                📑 報價\r
                --B\r
                Content-Disposition: form-data; name="document"; filename="股價紀錄.xlsx"\r
                Content-Type: application/octet-stream\r
                \r
                DATA\r
                --B--\r
                """, new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void masksBotTokenInUrl() {
        assertEquals("https://api.telegram.org/bot***/sendMessage",
                HttpUtil.mask("https://api.telegram.org/bot123:ABC-def/sendMessage"));
        assertEquals("https://api.fugle.tw/marketdata/v1.0/stock/intraday/quote/2330",
                HttpUtil.mask("https://api.fugle.tw/marketdata/v1.0/stock/intraday/quote/2330"));
    }
}
