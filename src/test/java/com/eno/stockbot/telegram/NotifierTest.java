package com.eno.stockbot.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NotifierTest {

    @Test
    void shortTextIsSingleMessage() {
        assertEquals(List.of("a\nb"), Notifier.split("a\nb"));
    }

    @Test
    void longTextIsSplitOnLineBoundaries() {
        String line = "x".repeat(1000);
        String text = String.join("\n", line, line, line, line, line);   // 5004 字元

        List<String> parts = Notifier.split(text);

        assertEquals(2, parts.size());
        parts.forEach(p -> assertTrue(p.length() <= Notifier.MAX_LENGTH));
        assertEquals(text, String.join("\n", parts));
    }

    @Test
    void previewShowsFirstLineAndLineCount() {
        assertEquals("⏱ 10:20 盤中報價 …（共 3 行）", Notifier.preview("⏱ 10:20 盤中報價\n🔴 2330\n🟢 2317"));
        assertEquals("✅ 已加入：2330 台積電", Notifier.preview("✅ 已加入：2330 台積電"));
        assertEquals("x".repeat(60) + "…", Notifier.preview("x".repeat(80)));
    }
}
