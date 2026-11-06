ㄉ# stock-bot 流程圖

只記錄兩件事：**程式之間怎麼互相呼叫**、**使用者怎麼操作**。
欄位、設定值等細節請看 README 與程式碼。

## 1. 程式交互

### 1.1 元件關係

入口有兩種：`handler/`（外部觸發：Telegram 訊息、服務啟動）與 `schedule/`（排程）。
入口只負責接收觸發、轉交 service；service 再透過 mapper 存取 MySQL，透過共用的 `HttpUtil` 呼叫外部 API。
`handler`、`service`、`mapper`、`bean` 底下都依對象分成 `subscriber`（訂閱者）、`fugle`（富果）、`telegram`。

| 入口 | 類別 | 觸發時機 | 執行緒 |
|---|---|---|---|
| `handler/telegram` | `TelegramPoller` → `CommandHandler` | 使用者傳訊息 / 點按鈕 | `telegram-poller` 收訊，`tg-update-N` 處理 |
| `handler/telegram` | `SetupNoticeSender` | 服務啟動完成（`ApplicationReadyEvent`） | `setup-notice` |
| `handler/subscriber` | `SubscriberStartupLog` | 服務啟動完成（`ApplicationReadyEvent`） | main |
| `schedule` | `IntradayReportJob` | 每分鐘一次（交易日盤中） | `push-job-N` |
| `schedule` | `QuoteRecordJob` | 09:00 ~ 13:30 每 30 分鐘，以及 13:35 收盤 | `push-job-N` |
| `schedule` | `DailyReportJob` | 每天 14:00（交易日） | `push-job-N` |

```mermaid
flowchart TB
    tg(["Telegram Bot API"])
    fugle(["富果行情 API"])
    db[("MySQL")]

    subgraph entry["handler / schedule 入口"]
        subgraph h_tg["handler/telegram"]
            poller["TelegramPoller"]
            handler["CommandHandler"]
            notice["SetupNoticeSender"]
        end
        slog["handler/subscriber<br/>SubscriberStartupLog"]
        subgraph sch["schedule"]
            job["IntradayReportJob"]
            rec["QuoteRecordJob"]
            daily["DailyReportJob"]
        end
    end

    subgraph s_sub["service/subscriber"]
        subs["SubscriptionService"]
        watch["WatchService"]
        hist["QuoteHistoryService"]
        push["IntradayPushService"]
        recs["QuoteRecordService"]
        dailys["DailyReportService<br/>+ DailyExcel"]
    end

    subgraph s_fugle["service/fugle"]
        qs["QuoteService<br/>30 秒快取"]
        fc["FugleClient<br/>限流 + 重試"]
    end

    subgraph s_tg["service/telegram"]
        notifier["Notifier"]
        tclient["TelegramClient"]
    end

    subgraph mapper["mapper/subscriber"]
        smap["SubscriberMapper"]
        wmap["WatchMapper"]
        qmap["QuoteSnapshotMapper"]
    end

    http["common/http<br/>HttpUtil"]

    tg -- "getUpdates" --> poller --> handler
    handler --> subs & watch & qs
    handler -- "send / editMessageText" --> notifier & tclient
    notice -- "windowView()" --> handler
    notice --> subs
    slog --> subs
    job --> push
    rec --> recs
    daily --> dailys

    watch -- "查股票是否存在" --> qs
    push --> subs & watch & qs & notifier
    recs --> watch & qs & hist
    dailys --> subs & watch & hist & notifier
    notifier -. "403 封鎖 → disable" .-> subs

    subs --> smap
    watch --> wmap
    hist --> qmap
    smap & wmap & qmap --> db

    qs -- "快取過期才查" --> fc
    notifier --> tclient
    fc & tclient --> http
    http --> fugle & tg
```

### 1.2 使用者操作的處理路徑

`CommandHandler` 每次都先算出一個 **View（文字 + 按鈕）**，再決定怎麼送出：
文字訊息一律送新訊息；按鈕大多原地編輯原訊息，最後一定回 `answerCallbackQuery`。

```mermaid
sequenceDiagram
    autonumber
    actor U as 使用者
    participant T as Telegram
    participant P as TelegramPoller
    participant H as CommandHandler
    participant S as Subscription / Watch<br/>Service
    participant Q as QuoteService
    participant N as Notifier
    participant C as TelegramClient

    U->>T: 傳訊息 / 點按鈕
    P->>T: getUpdates(offset)（long polling）
    T-->>P: update
    P->>H: 開一條 virtual thread<br/>handleMessage / handleCallback
    H->>H: 檢查白名單
    H->>S: 讀寫使用者、觀察清單、推播設定
    opt 需要報價（即時報價 / 新增股票）
        H->>Q: getQuote(s)
        Q-->>H: Quote
    end
    H->>H: 產生 View
    alt 文字訊息
        H->>N: send(View)
        N->>C: sendMessage
    else 按鈕
        H->>C: editMessageText（原地更新；失敗改走 Notifier.send）
        H->>C: answerCallbackQuery（小提示）
    end
    C->>T: HTTP
    T-->>U: 顯示結果
```

### 1.3 定時推播

```mermaid
sequenceDiagram
    autonumber
    participant S as Spring Scheduler
    participant J as IntradayReportJob
    participant P as IntradayPushService
    participant R as Subscription / Watch<br/>Service
    participant Q as QuoteService
    participant N as Notifier
    participant C as TelegramClient

    S->>J: tick()（每分鐘）
    J->>P: pushDue(這一分鐘)
    P->>R: findPushReady()
    R-->>P: 已設定且啟用的使用者
    P->>P: PushSchedule.isDue(現在) 篩出到期的人
    alt 沒人到期
        P-->>J: 結束
    else 有人到期
        P->>R: findGroupedByChat()
        R-->>P: chatId → 股票清單
        P->>Q: getQuotes(所有到期者的股票聯集)
        Q-->>P: 報價
        alt 沒有今天的資料（休市）
            P-->>J: 結束
        else
            loop 每位到期的使用者
                P->>N: sendHtml(ReportFormatter.format(...), 報價按鈕)
                N->>C: sendMessage
            end
        end
    end
```

### 1.4 報價紀錄與每日 Excel

```mermaid
sequenceDiagram
    autonumber
    participant S as Spring Scheduler
    participant RJ as QuoteRecordJob
    participant RS as QuoteRecordService
    participant DJ as DailyReportJob
    participant DS as DailyReportService
    participant R as Subscription / Watch<br/>Service
    participant Q as QuoteService
    participant H as QuoteHistoryService
    participant N as Notifier

    Note over S,N: 09:00 ~ 13:30 每 30 分鐘、13:35 收盤
    S->>RJ: record() / recordClose()
    RJ->>RS: record(今天, 這一分鐘)
    RS->>R: findAllSymbols()（所有人清單的聯集）
    RS->>Q: getQuotes(symbols)
    Q-->>RS: 報價
    alt 沒有今天的資料（休市）
        RS-->>RJ: 結束
    else
        RS->>H: save(今天, 這一分鐘, 報價)
    end

    Note over S,N: 14:00
    S->>DJ: run()
    DJ->>DS: sendReports(今天)
    DS->>R: findEnabledChatIds() / findGroupedByChat()
    loop 每位啟用中的使用者
        DS->>H: find(QuoteSnapshotSo(今天, 自己的清單))
        alt 沒有紀錄（休市）
            Note over DS: 不產生 Excel
        else 有紀錄
            DS->>DS: DailyExcel.build → .xlsx（總覽 + 明細）
            DS->>N: sendDocument
        end
    end
    DJ->>DS: purgeExpired(今天)
    DS->>H: deleteBefore(今天 − 一個月)
```

### 1.5 啟動時通知既有使用者

```mermaid
sequenceDiagram
    autonumber
    participant B as Spring Boot
    participant S as SetupNoticeSender
    participant R as SubscriptionService
    participant H as CommandHandler
    participant N as Notifier

    B->>S: ApplicationReadyEvent
    S->>R: findPendingSetupNotice()
    loop 每位待通知的使用者
        opt 啟用中、未設定、在白名單
            S->>H: windowView(chatId)
            H-->>S: 「選擇時段」View
            S->>N: send(通知 + 時段選擇)
        end
        S->>R: clearSetupNotice(chatId)（只通知一次）
    end
```

## 2. 使用者操作流程

每個畫面裡的按鈕寫在方塊內；`→` 表示會跳到另一個畫面。

```mermaid
%%{init: {"flowchart": {"curve": "linear", "nodeSpacing": 24, "rankSpacing": 40}}}%%
flowchart TD
    start["/start<br/>或 既有使用者收到「請重新設定」通知"]
    setup["⚙️ 推播設定（兩步都完成才開始推播）<br/>① 選時段 → ② 選間隔 → ✅ 設定完成<br/>已設定過的使用者 /start 會直接到主選單"]

    subgraph menu["主選單"]
        quote["📊 即時報價<br/>──────<br/>🔄 更新（原地刷新）<br/>📋 管理清單 → 觀察清單"]
        list["📋 觀察清單<br/>──────<br/>❌ 點股票即移除<br/>➕ 新增 → 新增股票<br/>📊 即時報價 → 即時報價"]
        add["➕ 新增股票<br/>──────<br/>回覆股票代號<br/>📋 查看清單 → 觀察清單<br/>⚙️ 設定推播 → ①<br/>（尚未設定時才有）"]
        settings["⚙️ 推播設定<br/>──────<br/>🕘 修改時段 → ①<br/>⏱ 修改間隔 → ②<br/>⏸ 暫停 / ▶️ 恢復"]
    end

    subgraph auto["交易日自動收到"]
        push["⏱ 定時推播<br/>依自己的時段與間隔<br/>附 🔄 更新、📋 管理清單"]
        excel["📑 14:00 當天 Excel<br/>暫停推播時不會收到"]
    end

    start --> setup --> menu
    menu ~~~ auto
```

> 修改時段或間隔時，若另一項已設定，存完就直接回到設定總覽，不會再走下一步。
> 斜線指令（`/now` `/list` `/add` `/settings` `/pause` `/resume`）對應到上圖同一組畫面。
