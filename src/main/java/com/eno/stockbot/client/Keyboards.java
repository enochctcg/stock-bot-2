package com.eno.stockbot.client;

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

    // ---- Inline 按鈕的 callback_data（上限 64 bytes）----
    public static final String CB_QUOTE_REFRESH = "q:refresh";   // 原地更新報價
    public static final String CB_QUOTE_NEW = "q:new";           // 另外送一則報價
    public static final String CB_LIST_NEW = "l:new";            // 另外送一則觀察清單
    public static final String CB_LIST_RM = "l:rm:";             // l:rm:2330 移除後原地更新清單
    public static final String CB_ADD = "a";                     // 送出新增股票提示
    public static final String CB_PAUSE = "s:pause";
    public static final String CB_RESUME = "s:resume";
    public static final String CB_SETTINGS = "s:view";           // 回到推播設定
    public static final String CB_SET_WINDOW = "s:window";       // 進入「選擇時段」
    public static final String CB_SET_INTERVAL = "s:interval";   // 進入「選擇間隔」
    public static final String CB_WINDOW = "w:";                 // w:0900-1330 選擇預設時段
    public static final String CB_WINDOW_CUSTOM = "w:custom";    // 自訂時段（ForceReply）
    public static final String CB_INTERVAL = "i:";               // i:10 選擇間隔
    public static final String CB_INTERVAL_CUSTOM = "i:custom";  // 自訂間隔（ForceReply）

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

    /** 報價訊息下方的按鈕；即時報價與定時推播共用。 */
    public static Map<String, Object> quoteButtons() {
        return inline(List.of(List.of(
                new Button("🔄 更新", CB_QUOTE_REFRESH),
                new Button("📋 管理清單", CB_LIST_NEW))));
    }

    public static Map<String, Object> forceReply(String placeholder) {
        return Map.of("force_reply", true, "input_field_placeholder", placeholder);
    }
}
