package com.eno.stockbot.telegram;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram 的三種鍵盤（reply_markup），以 Map / List 表示，交給 Jackson 序列化成 JSON。
 *
 * <ul>
 *   <li>Reply Keyboard：常駐在輸入框下方，點了等於送出按鈕上的文字</li>
 *   <li>Inline Keyboard：附在訊息下方，點了會收到 callback_query</li>
 *   <li>ForceReply：讓使用者的輸入框自動進入「回覆這則訊息」模式</li>
 * </ul>
 */
public final class Keyboards {

    /** 主選單按鈕文字；使用者點擊後 bot 收到的就是這段文字。 */
    public static final String MENU_NOW = "📊 即時報價";
    public static final String MENU_LIST = "📋 觀察清單";
    public static final String MENU_ADD = "➕ 新增股票";
    public static final String MENU_SETTINGS = "⚙️ 推播設定";

    /** Inline 按鈕。callbackData 最長 64 bytes。 */
    public record Button(String text, String callbackData) {
        public Button {
            if (callbackData.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64) {
                throw new IllegalArgumentException("callback_data 超過 64 bytes: " + callbackData);
            }
        }
    }

    private Keyboards() {
    }

    public static Map<String, Object> mainMenu() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyboard", List.of(
                List.of(Map.of("text", MENU_NOW), Map.of("text", MENU_LIST)),
                List.of(Map.of("text", MENU_ADD), Map.of("text", MENU_SETTINGS))));
        m.put("resize_keyboard", true);
        m.put("is_persistent", true);
        m.put("input_field_placeholder", "選擇功能，或輸入 /add 2330");
        return m;
    }

    public static boolean isMenuText(String text) {
        return MENU_NOW.equals(text) || MENU_LIST.equals(text)
                || MENU_ADD.equals(text) || MENU_SETTINGS.equals(text);
    }

    public static Map<String, Object> inline(List<List<Button>> rows) {
        List<List<Map<String, String>>> keyboard = rows.stream()
                .map(row -> row.stream()
                        .map(b -> Map.of("text", b.text(), "callback_data", b.callbackData()))
                        .toList())
                .toList();
        return Map.of("inline_keyboard", keyboard);
    }

    public static Map<String, Object> forceReply(String placeholder) {
        return Map.of("force_reply", true, "input_field_placeholder", placeholder);
    }
}
