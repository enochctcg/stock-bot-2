# stock-bot 流程圖

只記錄兩件事：**程式之間怎麼互相呼叫**、**使用者怎麼操作**。
欄位、設定值等細節請看 README 與程式碼。

## 1. 程式交互

### 1.1 元件關係

程式有三個入口，最後都經過同一組下層元件：

| 入口 | 觸發時機 | 執行緒 |
|---|---|---|
| `TelegramPoller` | 使用者傳訊息 / 點按鈕 | `telegram-poller` 收訊，`tg-update-N` 處理 |
| `IntradayReportJob` | 排程每分鐘一次（交易日盤中） | `push-job-N` |
| `QuoteRecordJob` | 排程 09:00 ~ 13:30 每 30 分鐘，以及 13:35 收盤 | `push-job-N` |
| `DailyReportJob` | 排程每天 14:00（交易日） | `push-job-N` |
| `SetupNoticeSender` | 服務啟動完成（`ApplicationReadyEvent`） | `setup-notice` |

```mermaid
flowchart LR
    tg(["Telegram Bot API"])
    fugle(["富果行情 API"])
    db[("MySQL")]

    subgraph entry["入口"]
        poller["TelegramPoller"]
        job["IntradayReportJob"]
        notice["SetupNoticeSender"]
        rec["QuoteRecordJob"]
        daily["DailyReportJob"]
    end

    handler["CommandHandler<br/>指令 / 選單 / 按鈕 → View"]

    subgraph lower["下層元件"]
        notifier["Notifier<br/>送訊息"]
        client["TelegramClient<br/>HTTP 呼叫"]
        qs["QuoteService<br/>30 秒快取"]
        fc["FugleClient<br/>限流 + 重試"]
        repo["SubscriberRepository<br/>WatchRepository"]
        hist["QuoteHistoryRepository"]
        excel["DailyExcel<br/>產生 .xlsx"]
    end

    tg -- "getUpdates" --> poller
    poller -- "handleMessage /<br/>handleCallback" --> handler
    notice -- "windowView()" --> handler

    handler -- "send" --> notifier
    handler -- "editMessageText /<br/>answerCallbackQuery" --> client
    handler -- "getQuote(s)" --> qs
    handler -- "讀寫使用者 / 清單" --> repo

    job -- "findPushReady /<br/>findGroupedByChat" --> repo
    job -- "getQuotes" --> qs
    job -- "send" --> notifier
    notice -- "findPendingSetupNotice /<br/>clearSetupNotice" --> repo
    notice -- "send" --> notifier

    rec -- "findAllSymbols" --> repo
    rec -- "getQuotes" --> qs
    rec -- "save" --> hist
    daily -- "findByDate /<br/>deleteBefore" --> hist
    daily -- "findEnabledChatIds /<br/>findGroupedByChat" --> repo
    daily -- "build" --> excel
    daily -- "sendDocument" --> notifier

    notifier -- "sendMessage /<br/>sendDocument" --> client
    notifier -. "403 封鎖 → setEnabled(false)" .-> repo
    client --> tg
    qs -- "快取過期才查" --> fc --> fugle
    repo --> db
    hist --> db
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
    participant R as Repository
    participant Q as QuoteService
    participant N as Notifier
    participant C as TelegramClient

    U->>T: 傳訊息 / 點按鈕
    P->>T: getUpdates(offset)（long polling）
    T-->>P: update
    P->>H: 開一條 virtual thread<br/>handleMessage / handleCallback
    H->>H: 檢查白名單
    H->>R: 讀寫使用者、觀察清單、推播設定
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
    participant R as Repository
    participant Q as QuoteService
    participant N as Notifier
    participant C as TelegramClient

    S->>J: tick()（每分鐘）
    J->>R: findPushReady()
    R-->>J: 已設定且啟用的使用者
    J->>J: PushSchedule.isDue(現在) 篩出到期的人
    alt 沒人到期
        J-->>S: 結束
    else 有人到期
        J->>R: findGroupedByChat()
        R-->>J: chatId → 股票清單
        J->>Q: getQuotes(所有到期者的股票聯集)
        Q-->>J: 報價
        alt 沒有今天的資料（休市）
            J-->>S: 結束
        else
            loop 每位到期的使用者
                J->>N: send(ReportFormatter.format(...), 報價按鈕)
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
    participant DJ as DailyReportJob
    participant R as Subscriber / Watch<br/>Repository
    participant Q as QuoteService
    participant H as QuoteHistoryRepository
    participant X as DailyExcel
    participant N as Notifier

    Note over S,N: 09:00 ~ 13:30 每 30 分鐘、13:35 收盤
    S->>RJ: record() / recordClose()
    RJ->>R: findAllSymbols()（所有人清單的聯集）
    RJ->>Q: getQuotes(symbols)
    Q-->>RJ: 報價
    alt 沒有今天的資料（休市）
        RJ-->>S: 結束
    else
        RJ->>H: save(今天, 這一分鐘, 報價)
    end

    Note over S,N: 14:00
    S->>DJ: run()
    DJ->>H: findByDate(今天)
    alt 沒有紀錄（休市）
        Note over DJ: 不產生 Excel
    else 有紀錄
        DJ->>R: findEnabledChatIds() / findGroupedByChat()
        loop 每位啟用中、清單不是空的使用者
            DJ->>X: build(今天, 自己的清單, 紀錄)
            X-->>DJ: .xlsx（總覽 + 明細）
            DJ->>N: sendDocument
        end
    end
    DJ->>H: deleteBefore(今天 − 一個月)
```

### 1.5 啟動時通知既有使用者

```mermaid
sequenceDiagram
    autonumber
    participant B as Spring Boot
    participant S as SetupNoticeSender
    participant R as SubscriberRepository
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

```mermaid
flowchart TD
    start(["/start"]) --> setupQ{"已設定推播？"}
    setupQ -- "否" --> w
    setupQ -- "是" --> menu
    notice(["既有使用者<br/>收到「請重新設定」通知"]) --> w

    subgraph setup["推播設定（兩步都完成才開始推播）"]
        w["① 選時段<br/>預設選項 或 ✏️ 自訂輸入"]
        i["② 選間隔<br/>預設選項 或 ✏️ 自訂輸入"]
        w --> i --> done["✅ 設定完成"]
    end
    done --> menu

    menu{{"主選單"}}
    menu -- "📊 即時報價" --> quote["報價訊息"]
    menu -- "📋 觀察清單" --> list["觀察清單"]
    menu -- "➕ 新增股票" --> add["回覆股票代號"]
    menu -- "⚙️ 推播設定" --> settings["推播設定總覽"]

    quote -- "🔄 更新" --> quote
    quote -- "📋 管理清單" --> list
    list -- "❌ 點股票" --> list
    list -- "➕ 新增" --> add
    list -- "📊 即時報價" --> quote
    add --> addResult["新增結果"]
    addResult -- "📋 查看清單" --> list
    addResult -- "➕ 繼續新增" --> add
    addResult -. "尚未設定時：⚙️ 設定推播" .-> w

    settings -- "🕘 修改時段" --> w
    settings -- "⏱ 修改間隔" --> i
    settings -- "⏸ 暫停 / ▶️ 恢復" --> settings

    push(["⏱ 交易日依設定收到定時推播"]) --> quote
    excelMsg(["📑 交易日 14:00 收到當天的 Excel<br/>（暫停推播時不會收到）"])
```

> 修改時段或間隔時，若另一項已設定，存完就直接回到設定總覽，不會再走下一步。
> 斜線指令（`/now` `/list` `/add` `/settings` `/pause` `/resume`）對應到上圖同一組畫面。
