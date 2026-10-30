package com.eno.stockbot.bot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.market.FugleClient;
import com.eno.stockbot.market.Quote;
import com.eno.stockbot.market.QuoteService;
import com.eno.stockbot.report.ReportFormatter;
import com.eno.stockbot.subscription.SubscriberRepository;
import com.eno.stockbot.subscription.WatchRepository;
import com.eno.stockbot.telegram.Keyboards;
import com.eno.stockbot.telegram.Keyboards.Button;
import com.eno.stockbot.telegram.Notifier;
import com.eno.stockbot.telegram.TelegramClient;

/**
 * 處理三種輸入，最後都會落到同一組「畫面」(view)：
 * <ol>
 *   <li>斜線指令：/add 2330、/list ...（保留給習慣打字的人）</li>
 *   <li>主選單按鈕（Reply Keyboard）：使用者點擊後送出的是按鈕文字</li>
 *   <li>Inline 按鈕：收到 callback_query，大多原地編輯原訊息</li>
 * </ol>
 * 另外「➕ 新增股票」會送出 ForceReply 提示，使用者回覆的內容當成 /add 處理。
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    /** 台股代號：4~6 碼數字或英數（ETF 如 00878、權證、特別股如 2881A）。 */
    private static final Pattern SYMBOL = Pattern.compile("^[0-9][0-9A-Z]{3,5}$");

    /** ForceReply 提示文字；使用者回覆這則訊息時，用它辨識是要新增股票。 */
    static final String ADD_PROMPT = "➕ 請輸入要新增的股票代號，可一次多檔（空格分隔）\n例如：2330 2317 0050";

    // ---- callback_data（上限 64 bytes）----
    static final String CB_QUOTE_REFRESH = "q:refresh";   // 原地更新報價
    static final String CB_QUOTE_NEW = "q:new";           // 另外送一則報價
    static final String CB_LIST_NEW = "l:new";            // 另外送一則觀察清單
    static final String CB_LIST_RM = "l:rm:";             // l:rm:2330 移除後原地更新清單
    static final String CB_ADD = "a";                     // 送出 ForceReply 提示
    static final String CB_PAUSE = "s:pause";
    static final String CB_RESUME = "s:resume";

    /** 出現在 Telegram 指令選單中的說明。 */
    public static final Map<String, String> MENU = menu();

    private static final String HELP = """
            📈 台股盤中報價機器人

            用下方選單按鈕操作就可以了：
            📊 即時報價 － 立即查詢目前價格
            📋 觀察清單 － 查看、移除股票
            ➕ 新增股票 － 輸入代號加入清單
            ⚙️ 推播設定 － 暫停或恢復定時推播

            也可以直接打指令：/add 2330 2317、/remove 2330、/list、/now

            交易日 09:10 ~ 13:20 每 10 分鐘推播一次，13:33 推播收盤價。""";

    /** 畫面 = 文字 + 鍵盤（可為 null）。 */
    record View(String text, Object markup) {
        static View text(String text) {
            return new View(text, null);
        }
    }

    private final BotProperties props;
    private final SubscriberRepository subscribers;
    private final WatchRepository watches;
    private final QuoteService quotes;
    private final Notifier notifier;
    private final TelegramClient telegram;
    private final Clock clock;

    public CommandHandler(BotProperties props, SubscriberRepository subscribers, WatchRepository watches,
                          QuoteService quotes, Notifier notifier, TelegramClient telegram, Clock clock) {
        this.props = props;
        this.subscribers = subscribers;
        this.watches = watches;
        this.quotes = quotes;
        this.notifier = notifier;
        this.telegram = telegram;
        this.clock = clock;
    }

    // =====================================================================
    // 文字訊息：斜線指令、主選單按鈕、ForceReply 回覆
    // =====================================================================

    public void handleMessage(Incoming.Message msg) {
        long chatId = msg.chatId();
        String text = msg.text().strip();
        Optional<Command> command = Command.parse(text);

        if (!props.isAllowed(chatId)) {
            if (command.isPresent() || msg.privateChat()) {
                notifier.send(chatId, "⛔ 你沒有使用權限。\n你的 chat id 是 " + chatId + "，請管理員加入 ALLOWED_CHAT_IDS。");
            }
            return;
        }

        try {
            View view;
            if (command.isPresent()) {
                view = onCommand(msg, command.get());
            } else if (Keyboards.isMenuText(text)) {
                // 要比 ForceReply 先判斷：提示還在時點選單按鈕，Telegram 可能會把它當成回覆送來
                view = onMenu(chatId, text);
            } else if (ADD_PROMPT.equals(msg.replyToText())) {
                view = add(chatId, msg.username(), Command.args(text));
            } else if (msg.privateChat()) {
                // 隨便打字時順便把主選單送回來（使用者可能把鍵盤收起來了）
                view = new View("請使用下方選單按鈕操作，或輸入 /help 查看說明。", Keyboards.mainMenu());
            } else {
                return;   // 群組裡的一般聊天不回應
            }
            notifier.send(chatId, view.text(), view.markup());
        } catch (FugleClient.FugleException e) {
            log.warn("處理 {} 時富果 API 失敗: {}", text, e.getMessage());
            notifier.send(chatId, "⚠️ 富果 API 暫時無法使用，請稍後再試。");
        } catch (RuntimeException e) {
            log.error("處理訊息 {} 失敗", text, e);
            notifier.send(chatId, "⚠️ 發生錯誤，請稍後再試。");
        }
    }

    private View onCommand(Incoming.Message msg, Command cmd) {
        long chatId = msg.chatId();
        return switch (cmd.name()) {
            case "start" -> {
                subscribers.upsert(chatId, msg.username());
                subscribers.setEnabled(chatId, true);
                yield new View("歡迎！你的 chat id 是 " + chatId + "\n\n" + HELP, Keyboards.mainMenu());
            }
            case "help" -> new View(HELP, Keyboards.mainMenu());
            case "add" -> cmd.args().isEmpty() ? addPrompt() : add(chatId, msg.username(), cmd.args());
            case "remove", "del" -> cmd.args().isEmpty() ? listView(chatId) : remove(chatId, cmd.args());
            case "list" -> listView(chatId);
            case "now" -> quoteView(chatId);
            case "settings" -> settingsView(chatId);
            case "pause" -> {
                setEnabled(chatId, msg.username(), false);
                yield settingsView(chatId);
            }
            case "resume" -> {
                setEnabled(chatId, msg.username(), true);
                yield settingsView(chatId);
            }
            default -> new View("看不懂這個指令，輸入 /help 查看說明。", Keyboards.mainMenu());
        };
    }

    private View onMenu(long chatId, String text) {
        return switch (text) {
            case Keyboards.MENU_NOW -> quoteView(chatId);
            case Keyboards.MENU_LIST -> listView(chatId);
            case Keyboards.MENU_ADD -> addPrompt();
            case Keyboards.MENU_SETTINGS -> settingsView(chatId);
            default -> throw new IllegalArgumentException(text);
        };
    }

    // =====================================================================
    // Inline 按鈕
    // =====================================================================

    public void handleCallback(Incoming.Callback cb) {
        long chatId = cb.chatId();
        if (!props.isAllowed(chatId)) {
            answer(cb, "⛔ 沒有使用權限");
            return;
        }

        String toast = null;
        try {
            String data = cb.data();
            if (CB_QUOTE_REFRESH.equals(data)) {
                edit(cb, quoteView(chatId));
                toast = "已更新";
            } else if (CB_QUOTE_NEW.equals(data)) {
                send(chatId, quoteView(chatId));
            } else if (CB_LIST_NEW.equals(data)) {
                send(chatId, listView(chatId));
            } else if (data.startsWith(CB_LIST_RM)) {
                String symbol = data.substring(CB_LIST_RM.length());
                toast = watches.remove(chatId, symbol) ? "🗑 已移除 " + symbol : symbol + " 已不在清單中";
                edit(cb, listView(chatId));
            } else if (CB_ADD.equals(data)) {
                send(chatId, addPrompt());
            } else if (CB_PAUSE.equals(data) || CB_RESUME.equals(data)) {
                boolean enabled = CB_RESUME.equals(data);
                setEnabled(chatId, cb.username(), enabled);
                toast = enabled ? "▶️ 已恢復定時推播" : "⏸ 已暫停定時推播";
                edit(cb, settingsView(chatId));
            } else {
                toast = "這個按鈕已失效";
            }
        } catch (FugleClient.FugleException e) {
            log.warn("處理按鈕 {} 時富果 API 失敗: {}", cb.data(), e.getMessage());
            toast = "⚠️ 富果 API 暫時無法使用，請稍後再試";
        } catch (RuntimeException e) {
            log.error("處理按鈕 {} 失敗", cb.data(), e);
            toast = "⚠️ 發生錯誤，請稍後再試";
        } finally {
            answer(cb, toast);
        }
    }

    /** 原地更新按鈕所在的訊息；內容沒變就略過，訊息太舊無法編輯時改送新訊息。 */
    private void edit(Incoming.Callback cb, View view) {
        try {
            telegram.editMessageText(cb.chatId(), cb.messageId(), view.text(), view.markup());
        } catch (TelegramClient.TelegramApiException e) {
            if (e.isMessageNotModified()) {
                return;
            }
            log.info("無法編輯訊息 {}，改送新訊息: {}", cb.messageId(), e.getMessage());
            send(cb.chatId(), view);
        }
    }

    private void send(long chatId, View view) {
        notifier.send(chatId, view.text(), view.markup());
    }

    private void answer(Incoming.Callback cb, String toast) {
        try {
            telegram.answerCallbackQuery(cb.id(), toast);
        } catch (TelegramClient.TelegramApiException e) {
            log.debug("answerCallbackQuery 失敗: {}", e.getMessage());   // 按鈕點太久以前會失敗，不影響功能
        }
    }

    // =====================================================================
    // 畫面
    // =====================================================================

    /** 報價訊息下方的按鈕；定時推播也會用到。 */
    public static Object quoteButtons() {
        return Keyboards.inline(List.of(List.of(
                new Button("🔄 更新", CB_QUOTE_REFRESH),
                new Button("📋 管理清單", CB_LIST_NEW))));
    }

    View quoteView(long chatId) {
        List<String> symbols = watches.findByChatId(chatId).stream().map(WatchRepository.Watch::symbol).toList();
        if (symbols.isEmpty()) {
            return emptyView();
        }
        Map<String, Quote> result = quotes.getQuotes(symbols);

        String header = "📊 " + LocalTime.now(clock).format(DateTimeFormatter.ofPattern("HH:mm")) + " 即時報價";
        // 休市日或盤前查詢時，富果回的是上一個交易日的資料，標示出來避免誤會
        String today = LocalDate.now(clock).toString();
        String dataDate = result.values().stream().map(Quote::date)
                .filter(d -> !d.isEmpty()).findFirst().orElse(today);
        if (!dataDate.equals(today)) {
            header += "（資料日期 " + dataDate + "）";
        }
        return new View(ReportFormatter.format(header, symbols, result), quoteButtons());
    }

    View listView(long chatId) {
        List<WatchRepository.Watch> list = watches.findByChatId(chatId);
        if (list.isEmpty()) {
            return emptyView();
        }
        StringBuilder sb = new StringBuilder("📋 觀察清單（" + list.size() + "/" + props.maxWatchPerUser() + "）\n點擊股票即可移除");
        if (!subscribers.isEnabled(chatId)) {
            sb.append("\n\n⏸ 定時推播已暫停");
        }

        List<List<Button>> rows = new ArrayList<>();
        for (WatchRepository.Watch w : list) {
            String label = "❌ " + w.symbol() + (w.name() == null ? "" : " " + w.name());
            rows.add(List.of(new Button(label, CB_LIST_RM + w.symbol())));
        }
        rows.add(List.of(new Button("➕ 新增", CB_ADD), new Button("📊 即時報價", CB_QUOTE_NEW)));
        return new View(sb.toString(), Keyboards.inline(rows));
    }

    View settingsView(long chatId) {
        boolean enabled = subscribers.isEnabled(chatId);
        String text = enabled
                ? "🔔 定時推播：開啟中\n交易日 09:10 ~ 13:20 每 10 分鐘推播，13:33 推播收盤價。"
                : "🔕 定時推播：已暫停\n觀察清單會保留，恢復後照常推播。";
        Button toggle = enabled ? new Button("⏸ 暫停推播", CB_PAUSE) : new Button("▶️ 恢復推播", CB_RESUME);
        return new View(text, Keyboards.inline(List.of(List.of(toggle))));
    }

    private View addPrompt() {
        return new View(ADD_PROMPT, Keyboards.forceReply("2330 2317"));
    }

    private View emptyView() {
        return new View("觀察清單是空的，先新增幾檔股票吧。",
                Keyboards.inline(List.of(List.of(new Button("➕ 新增股票", CB_ADD)))));
    }

    // =====================================================================
    // 動作
    // =====================================================================

    private View add(long chatId, String username, List<String> symbols) {
        if (symbols.isEmpty()) {
            return addPrompt();
        }
        subscribers.upsert(chatId, username);

        List<String> added = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int count = watches.count(chatId);

        for (String symbol : symbols) {
            if (!SYMBOL.matcher(symbol).matches()) {
                problems.add(symbol + "：代號格式不正確");
            } else if (watches.exists(chatId, symbol)) {
                problems.add(symbol + "：已經在清單中");
            } else if (count >= props.maxWatchPerUser()) {
                problems.add(symbol + "：已達上限 " + props.maxWatchPerUser() + " 檔");
            } else {
                Optional<Quote> q = quotes.getQuote(symbol);
                if (q.isEmpty()) {
                    problems.add(symbol + "：查無此股票");
                } else {
                    watches.add(chatId, symbol, q.get().name());
                    added.add(symbol + " " + q.get().name());
                    count++;
                }
            }
        }

        List<String> lines = new ArrayList<>();
        if (!added.isEmpty()) {
            lines.add("✅ 已加入：" + String.join("、", added));
        }
        problems.forEach(p -> lines.add("⚠️ " + p));

        Object buttons = Keyboards.inline(List.of(List.of(
                new Button("📋 查看清單", CB_LIST_NEW),
                new Button("➕ 繼續新增", CB_ADD))));
        return new View(String.join("\n", lines), buttons);
    }

    private View remove(long chatId, List<String> symbols) {
        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String symbol : symbols) {
            (watches.remove(chatId, symbol) ? removed : missing).add(symbol);
        }
        List<String> lines = new ArrayList<>();
        if (!removed.isEmpty()) {
            lines.add("🗑 已移除：" + String.join("、", removed));
        }
        if (!missing.isEmpty()) {
            lines.add("不在清單中：" + String.join("、", missing));
        }
        return new View(String.join("\n", lines),
                Keyboards.inline(List.of(List.of(new Button("📋 查看清單", CB_LIST_NEW)))));
    }

    private void setEnabled(long chatId, String username, boolean enabled) {
        subscribers.upsert(chatId, username);
        subscribers.setEnabled(chatId, enabled);
    }

    private static Map<String, String> menu() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("now", "立即查詢目前價格");
        m.put("list", "觀察清單（可移除）");
        m.put("add", "新增股票，例：/add 2330");
        m.put("settings", "推播設定");
        m.put("help", "使用說明（重新顯示選單按鈕）");
        return m;
    }
}
