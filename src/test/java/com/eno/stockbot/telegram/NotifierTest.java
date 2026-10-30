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
}
