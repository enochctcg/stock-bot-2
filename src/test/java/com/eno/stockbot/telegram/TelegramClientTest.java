package com.eno.stockbot.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TelegramClientTest {

    @Test
    void multipartContainsFieldsAndFile() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chat_id", "123");
        fields.put("caption", "📑 報價");

        byte[] body = TelegramClient.multipart("B", fields, "document", "股價紀錄.xlsx",
                "DATA".getBytes(StandardCharsets.UTF_8));

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
}
