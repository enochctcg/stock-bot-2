package com.eno.stockbot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.eno.stockbot.client.Keyboards.Button;

class KeyboardsTest {

    @Test
    void inlineKeyboardUsesTelegramFieldNames() {
        Map<String, Object> markup = Keyboards.inline(List.of(
                List.of(new Button("❌ 2330 台積電", "l:rm:2330")),
                List.of(new Button("➕ 新增", "a"), new Button("📊 即時報價", "q:new"))));

        assertEquals(Map.of("inline_keyboard", List.of(
                List.of(Map.of("text", "❌ 2330 台積電", "callback_data", "l:rm:2330")),
                List.of(Map.of("text", "➕ 新增", "callback_data", "a"),
                        Map.of("text", "📊 即時報價", "callback_data", "q:new")))), markup);
    }

    @Test
    void mainMenuIsPersistentTwoByTwo() {
        Map<String, Object> menu = Keyboards.mainMenu();
        assertEquals(Boolean.TRUE, menu.get("is_persistent"));
        assertEquals(Boolean.TRUE, menu.get("resize_keyboard"));
        assertEquals(List.of(
                List.of(Map.of("text", Keyboards.MENU_NOW), Map.of("text", Keyboards.MENU_LIST)),
                List.of(Map.of("text", Keyboards.MENU_ADD), Map.of("text", Keyboards.MENU_SETTINGS))),
                menu.get("keyboard"));
    }

    @Test
    void recognisesMenuText() {
        assertTrue(Keyboards.isMenuText(Keyboards.MENU_NOW));
        assertFalse(Keyboards.isMenuText("2330"));
    }

    @Test
    void rejectsCallbackDataOver64Bytes() {
        boolean thrown = false;
        try {
            new Button("x", "l:rm:" + "9".repeat(60));
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }
}
