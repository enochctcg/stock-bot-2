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
import com.eno.stockbot.subscription.PushSchedule;
import com.eno.stockbot.subscription.Subscriber;
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
 * 需要使用者打字的步驟（新增股票、自訂推播時段 / 間隔）會送出 ForceReply 提示，
 * 使用者回覆的內容依被回覆的提示文字判斷用途。
 *
 * <p>推播設定分兩步：① 時段 → ② 間隔。兩者都設定完成才會開始定時推播。
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    /** 台股代號：4~6 碼數字或英數（ETF 如 00878、權證、特別股如 2881A）。 */
    private static final Pattern SYMBOL = Pattern.compile("^[0-9][0-9A-Z]{3,5}$");

    // ---- ForceReply 提示；使用者回覆時用它辨識要做什麼 ----
    static final String ADD_PROMPT = "➕ 請輸入要新增的股票代號，可一次多檔（空格分隔）\n例如：2330 2317 0050";
    static final String WINDOW_PROMPT = "🕘 請輸入推播時段，範圍 "
            + PushSchedule.format(PushSchedule.MARKET_OPEN) + " ~ " + PushSchedule.format(PushSchedule.MARKET_CLOSE)
            + "\n例如：09:00-13:30";
    static final String INTERVAL_PROMPT = "⏱ 請輸入推播間隔（分鐘），最少 " + PushSchedule.MIN_INTERVAL + " 分鐘\n例如：5";

    // ---- callback_data（上限 64 bytes）----
    static final String CB_QUOTE_REFRESH = "q:refresh";   // 原地更新報價
    static final String CB_QUOTE_NEW = "q:new";           // 另外送一則報價
    static final String CB_LIST_NEW = "l:new";            // 另外送一則觀察清單
    static final String CB_LIST_RM = "l:rm:";             // l:rm:2330 移除後原地更新清單
    static final String CB_ADD = "a";                     // 送出新增股票提示
    static final String CB_PAUSE = "s:pause";
    static final String CB_RESUME = "s:resume";
    static final String CB_SETTINGS = "s:view";           // 回到推播設定
    static final String CB_SET_WINDOW = "s:window";       // 進入「選擇時段」
    static final String CB_SET_INTERVAL = "s:interval";   // 進入「選擇間隔」
    static final String CB_WINDOW = "w:";                 // w:0900-1330 選擇預設時段
    static final String CB_WINDOW_CUSTOM = "w:custom";    // 自訂時段（ForceReply）
    static final String CB_INTERVAL = "i:";               // i:10 選擇間隔
    static final String CB_INTERVAL_CUSTOM = "i:custom";  // 自訂間隔（ForceReply）

    /** 時段預設選項。 */
    private static final List<Preset> WINDOW_PRESETS = List.of(
            new Preset("🌞 整個盤中", "09:00-13:30"),
            new Preset("🌅 開盤 1 小時", "09:00-10:00"),
            new Preset("🌇 收盤前 1 小時", "12:30-13:30"));

    /** 間隔預設選項（分鐘），每列 3 個。 */
    private static final List<Integer> INTERVAL_PRESETS = List.of(1, 5, 10, 15, 30, 60);

    private record Preset(String label, String window) {
    }

    /** 出現在 Telegram 指令選單中的說明。 */
    public static final Map<String, String> MENU = menu();

    private static final String HELP = """
            📈 台股盤中報價機器人

            用下方選單按鈕操作就可以了：
            📊 即時報價 － 立即查詢目前價格
            📋 觀察清單 － 查看、移除股票
            ➕ 新增股票 － 輸入代號加入清單
            ⚙️ 推播設定 － 設定推播時段、間隔，或暫停推播

            也可以直接打指令：/add 2330 2317、/remove 2330、/list、/now、/settings

            推播時段與間隔由你自己設定（交易日 09:00 ~ 13:30，最短每 1 分鐘）。""";

    private static final DateTimeFormatter SUBSCRIBED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 畫面 = 文字 + 鍵盤（可為 null）。 */
    record View(String text, Object markup, boolean html) {
        View(String text, Object markup) {
            this(text, markup, false);
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
        logSubscriber(chatId);
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
            } else if (isReplyTo(msg, ADD_PROMPT)) {
                view = add(chatId, msg.username(), Command.args(text));
            } else if (isReplyTo(msg, WINDOW_PROMPT)) {
                view = onWindowInput(chatId, msg.username(), text);
            } else if (isReplyTo(msg, INTERVAL_PROMPT)) {
                view = onIntervalInput(chatId, msg.username(), text);
            } else if (msg.privateChat()) {
                // 隨便打字時順便把主選單送回來（使用者可能把鍵盤收起來了）
                view = new View("請使用下方選單按鈕操作，或輸入 /help 查看說明。", Keyboards.mainMenu());
            } else {
                return;   // 群組裡的一般聊天不回應
            }
            if (view != null) {
                send(chatId, view);
            }
        } catch (FugleClient.FugleException e) {
            log.warn("處理 {} 時富果 API 失敗: {}", text, e.getMessage());
            notifier.send(chatId, "⚠️ 富果 API 暫時無法使用，請稍後再試。");
        } catch (RuntimeException e) {
            log.error("處理訊息 {} 失敗", text, e);
            notifier.send(chatId, "⚠️ 發生錯誤，請稍後再試。");
        }
    }

    /** 輸入錯誤時重送的提示會在前面加上錯誤訊息，所以用 endsWith 比對。 */
    private static boolean isReplyTo(Incoming.Message msg, String prompt) {
        return msg.replyToText() != null && msg.replyToText().endsWith(prompt);
    }

    private View onCommand(Incoming.Message msg, Command cmd) {
        long chatId = msg.chatId();
        return switch (cmd.name()) {
            case "start" -> start(chatId, msg.username());
            case "help" -> new View(HELP, Keyboards.mainMenu());
            case "add" -> cmd.args().isEmpty() ? addPrompt() : add(chatId, msg.username(), cmd.args());
            case "remove", "del" -> cmd.args().isEmpty() ? listView(chatId) : remove(chatId, cmd.args());
            case "list" -> listView(chatId);
            case "now" -> quoteView(chatId);
            case "settings" -> settingsView(chatId, null);
            case "pause" -> {
                setEnabled(chatId, msg.username(), false);
                yield settingsView(chatId, null);
            }
            case "resume" -> {
                setEnabled(chatId, msg.username(), true);
                yield settingsView(chatId, null);
            }
            default -> new View("看不懂這個指令，輸入 /help 查看說明。", Keyboards.mainMenu());
        };
    }

    /** 第一次使用：送出歡迎訊息與主選單，接著直接進入推播設定。 */
    private View start(long chatId, String username) {
        Optional<Subscriber> before = subscriber(chatId);
        subscribers.upsert(chatId, username);
        subscribers.setEnabled(chatId, true);
        if (before.isPresent()) {
            log.info("[訂閱] chat {} username={} 再次 /start：{}", chatId, username,
                    before.get().enabled() ? "原本就在訂閱中" : "重新訂閱（恢復推播）");
        }
        String welcome = "歡迎！你的 chat id 是 " + chatId + "\n\n" + HELP;
        if (isConfigured(chatId)) {
            return new View(welcome, Keyboards.mainMenu());
        }
        // 主選單（Reply Keyboard）和 inline 按鈕不能放在同一則訊息，分兩則送
        notifier.send(chatId, welcome, Keyboards.mainMenu());
        return windowView(chatId);
    }

    private View onMenu(long chatId, String text) {
        return switch (text) {
            case Keyboards.MENU_NOW -> quoteView(chatId);
            case Keyboards.MENU_LIST -> listView(chatId);
            case Keyboards.MENU_ADD -> addPrompt();
            case Keyboards.MENU_SETTINGS -> settingsView(chatId, null);
            default -> throw new IllegalArgumentException(text);
        };
    }

    private View onWindowInput(long chatId, String username, String text) {
        PushSchedule.Window window;
        try {
            window = PushSchedule.Window.parse(text);
        } catch (IllegalArgumentException e) {
            return new View("⚠️ " + e.getMessage() + "\n\n" + WINDOW_PROMPT, Keyboards.forceReply("09:00-13:30"));
        }
        subscribers.upsert(chatId, username);
        return afterWindowSet(chatId, window);
    }

    private View onIntervalInput(long chatId, String username, String text) {
        int minutes;
        try {
            minutes = PushSchedule.parseInterval(text);
        } catch (IllegalArgumentException e) {
            return new View("⚠️ " + e.getMessage() + "\n\n" + INTERVAL_PROMPT, Keyboards.forceReply("5"));
        }
        subscribers.upsert(chatId, username);
        return afterIntervalSet(chatId, minutes);
    }

    // =====================================================================
    // Inline 按鈕
    // =====================================================================

    public void handleCallback(Incoming.Callback cb) {
        long chatId = cb.chatId();
        logSubscriber(chatId);
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
                edit(cb, settingsView(chatId, null));
            } else if (CB_SETTINGS.equals(data)) {
                edit(cb, settingsView(chatId, null));
            } else if (CB_SET_WINDOW.equals(data)) {
                edit(cb, windowView(chatId));
            } else if (CB_SET_INTERVAL.equals(data)) {
                edit(cb, intervalView(chatId));
            } else if (CB_WINDOW_CUSTOM.equals(data)) {
                send(chatId, new View(WINDOW_PROMPT, Keyboards.forceReply("09:00-13:30")));
            } else if (data.startsWith(CB_WINDOW)) {
                PushSchedule.Window window = PushSchedule.Window.parse(data.substring(CB_WINDOW.length()));
                subscribers.upsert(chatId, cb.username());
                edit(cb, afterWindowSet(chatId, window));
                toast = "🕘 時段：" + window;
            } else if (CB_INTERVAL_CUSTOM.equals(data)) {
                send(chatId, new View(INTERVAL_PROMPT, Keyboards.forceReply("5")));
            } else if (data.startsWith(CB_INTERVAL)) {
                int minutes = PushSchedule.parseInterval(data.substring(CB_INTERVAL.length()));
                subscribers.upsert(chatId, cb.username());
                edit(cb, afterIntervalSet(chatId, minutes));
                toast = "⏱ 每 " + minutes + " 分鐘";
            } else {
                toast = "這個按鈕已失效";
            }
        } catch (FugleClient.FugleException e) {
            log.warn("處理按鈕 {} 時富果 API 失敗: {}", cb.data(), e.getMessage());
            toast = "⚠️ 富果 API 暫時無法使用，請稍後再試";
        } catch (IllegalArgumentException e) {
            log.warn("按鈕資料不合法 {}: {}", cb.data(), e.getMessage());
            toast = "這個按鈕已失效";
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
            telegram.editMessageText(cb.chatId(), cb.messageId(), view.text(), view.markup(), view.html());
            log.info("已原地更新訊息 {} → chat {}：{}", cb.messageId(), cb.chatId(), Notifier.preview(view.text()));
        } catch (TelegramClient.TelegramApiException e) {
            if (e.isMessageNotModified()) {
                log.info("訊息 {} 內容沒有變化，略過更新", cb.messageId());
                return;
            }
            log.info("無法編輯訊息 {}，改送新訊息: {}", cb.messageId(), e.getMessage());
            send(cb.chatId(), view);
        }
    }

    private void send(long chatId, View view) {
        if (view.html()) {
            notifier.sendHtml(chatId, view.text(), view.markup());
        } else {
            notifier.send(chatId, view.text(), view.markup());
        }
    }

    private void answer(Incoming.Callback cb, String toast) {
        try {
            telegram.answerCallbackQuery(cb.id(), toast);
            if (toast != null) {
                log.info("按鈕提示：{}", toast);
            }
        } catch (TelegramClient.TelegramApiException e) {
            log.debug("answerCallbackQuery 失敗: {}", e.getMessage());   // 按鈕點太久以前會失敗，不影響功能
        }
    }

    // =====================================================================
    // 推播設定
    // =====================================================================

    /** 設定時段後：還沒設定間隔就進入第 2 步，否則回到設定總覽。 */
    private View afterWindowSet(long chatId, PushSchedule.Window window) {
        boolean wasConfigured = isConfigured(chatId);
        subscribers.setWindow(chatId, window);
        if (subscriber(chatId).map(Subscriber::intervalMinutes).isEmpty()) {
            return intervalView(chatId);
        }
        return settingsView(chatId, wasConfigured ? "✅ 已更新推播時段" : "✅ 推播設定完成，開始定時推播！");
    }

    /** 設定間隔後：還沒設定時段就回到第 1 步，否則回到設定總覽。 */
    private View afterIntervalSet(long chatId, int minutes) {
        boolean wasConfigured = isConfigured(chatId);
        subscribers.setInterval(chatId, minutes);
        if (subscriber(chatId).filter(Subscriber::hasWindow).isEmpty()) {
            return windowView(chatId);
        }
        return settingsView(chatId, wasConfigured ? "✅ 已更新推播間隔" : "✅ 推播設定完成，開始定時推播！");
    }

    /** 第 1 步：選擇推播時段。也給啟動時的「請重新設定」通知使用。 */
    View windowView(long chatId) {
        Optional<Subscriber> s = subscriber(chatId);
        boolean configured = s.map(Subscriber::configured).orElse(false);

        StringBuilder text = new StringBuilder(configured ? "🕘 修改推播時段" : "⚙️ 推播設定（1/2）\n選擇推播時段");
        text.append("\n可設定範圍：交易日 ")
                .append(PushSchedule.format(PushSchedule.MARKET_OPEN)).append(" ~ ")
                .append(PushSchedule.format(PushSchedule.MARKET_CLOSE));
        s.flatMap(Subscriber::window).ifPresent(w -> text.append("\n目前：").append(w));

        List<List<Button>> rows = new ArrayList<>();
        for (Preset p : WINDOW_PRESETS) {
            PushSchedule.Window w = PushSchedule.Window.parse(p.window());
            rows.add(List.of(new Button(p.label() + " " + w, CB_WINDOW + w.compact())));
        }
        rows.add(List.of(new Button("✏️ 自訂時段", CB_WINDOW_CUSTOM)));
        if (configured) {
            rows.add(List.of(new Button("↩️ 返回", CB_SETTINGS)));
        }
        return new View(text.toString(), Keyboards.inline(rows));
    }

    /** 第 2 步：選擇推播間隔。 */
    View intervalView(long chatId) {
        Optional<Subscriber> s = subscriber(chatId);
        boolean configured = s.map(Subscriber::configured).orElse(false);

        StringBuilder text = new StringBuilder(configured ? "⏱ 修改推播間隔" : "⚙️ 推播設定（2/2）\n多久推播一次？");
        s.flatMap(Subscriber::window).ifPresent(w -> text.append("\n時段：").append(w));
        s.map(Subscriber::intervalMinutes).ifPresent(m -> text.append("\n目前：每 ").append(m).append(" 分鐘"));
        text.append("\n間隔越短，訊息越多（最短 ").append(PushSchedule.MIN_INTERVAL).append(" 分鐘）");

        List<List<Button>> rows = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        for (int minutes : INTERVAL_PRESETS) {
            row.add(new Button(minutes + " 分鐘", CB_INTERVAL + minutes));
            if (row.size() == 3) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(new Button("✏️ 自訂間隔", CB_INTERVAL_CUSTOM)));
        if (configured) {
            rows.add(List.of(new Button("↩️ 返回", CB_SETTINGS)));
        }
        return new View(text.toString(), Keyboards.inline(rows));
    }

    /** 推播設定總覽。banner 為最上方的提示（例如「設定完成」），可為 null。 */
    View settingsView(long chatId, String banner) {
        Optional<Subscriber> s = subscriber(chatId);
        Optional<PushSchedule> schedule = s.flatMap(Subscriber::schedule);
        if (schedule.isEmpty()) {
            return new View("⚙️ 尚未設定推播\n設定推播時段與間隔後，才會開始定時推播。",
                    Keyboards.inline(List.of(List.of(new Button("▶️ 開始設定", CB_SET_WINDOW)))));
        }

        boolean enabled = s.get().enabled();
        StringBuilder text = new StringBuilder();
        if (banner != null) {
            text.append(banner).append("\n\n");
        }
        text.append(enabled ? "🔔 定時推播：開啟中" : "🔕 定時推播：已暫停（觀察清單與設定都會保留）")
                .append("\n🕘 時段：").append(schedule.get().window())
                .append("\n⏱ 間隔：每 ").append(schedule.get().intervalMinutes()).append(" 分鐘")
                .append("\n📅 交易日").append(schedule.get().timesSummary());

        Button toggle = enabled ? new Button("⏸ 暫停推播", CB_PAUSE) : new Button("▶️ 恢復推播", CB_RESUME);
        return new View(text.toString(), Keyboards.inline(List.of(
                List.of(new Button("🕘 修改時段", CB_SET_WINDOW), new Button("⏱ 修改間隔", CB_SET_INTERVAL)),
                List.of(toggle))));
    }

    private Optional<Subscriber> subscriber(long chatId) {
        return subscribers.find(chatId);
    }

    private boolean isConfigured(long chatId) {
        return subscriber(chatId).map(Subscriber::configured).orElse(false);
    }

    // =====================================================================
    // 報價、觀察清單
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
        return new View(ReportFormatter.format(header, symbols, result), quoteButtons(), true);
    }

    View listView(long chatId) {
        List<WatchRepository.Watch> list = watches.findByChatId(chatId);
        if (list.isEmpty()) {
            return emptyView();
        }
        StringBuilder sb = new StringBuilder("📋 觀察清單（" + list.size() + "/" + props.maxWatchPerUser() + "）\n點擊股票即可移除");
        Optional<Subscriber> s = subscriber(chatId);
        if (!s.map(Subscriber::configured).orElse(false)) {
            sb.append("\n\n⚙️ 尚未設定推播時段，請到「推播設定」設定");
        } else if (!s.get().enabled()) {
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

        List<List<Button>> rows = new ArrayList<>();
        rows.add(List.of(new Button("📋 查看清單", CB_LIST_NEW), new Button("➕ 繼續新增", CB_ADD)));
        if (!isConfigured(chatId)) {
            lines.add("\n⚙️ 還沒設定推播時段與間隔，設定後才會開始定時推播。");
            rows.add(List.of(new Button("⚙️ 設定推播", CB_SET_WINDOW)));
        }
        return new View(String.join("\n", lines), Keyboards.inline(rows));
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
        boolean wasEnabled = subscribers.isEnabled(chatId);
        subscribers.upsert(chatId, username);
        subscribers.setEnabled(chatId, enabled);
        log.info("[訂閱] chat {} username={} {}{}", chatId, username,
                enabled ? "恢復訂閱推播" : "取消訂閱推播（暫停）",
                wasEnabled == enabled ? "，原本就是這個狀態" : "");
    }

    /** 每筆訊息 / 按鈕都記一次這位使用者的訂閱時間與狀態。 */
    private void logSubscriber(long chatId) {
        subscribers.findSummary(chatId).ifPresentOrElse(
                s -> log.info("使用者 chat {} 訂閱於 {}，推播{}，觀察 {} 檔", chatId,
                        s.createdAt().format(SUBSCRIBED_AT), s.enabled() ? "開啟中" : "已暫停", s.watchCount()),
                () -> log.info("使用者 chat {} 尚未訂閱", chatId));
    }

    private static Map<String, String> menu() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("now", "立即查詢目前價格");
        m.put("list", "觀察清單（可移除）");
        m.put("add", "新增股票，例：/add 2330");
        m.put("settings", "推播設定（時段、間隔、暫停）");
        m.put("help", "使用說明（重新顯示選單按鈕）");
        return m;
    }
}
