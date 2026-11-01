# stock-bot 架構與流程

## 1. 整體架構

單一 Spring Boot 服務，對外只連三個東西：Telegram Bot API、富果行情 REST API、MySQL。
Telegram 用 long polling 收訊息，所以不需要公網 IP 或 HTTPS 網域。

```mermaid
flowchart TB
    user(["👤 使用者<br/>Telegram App"])
    tg["Telegram Bot API<br/>api.telegram.org"]

    subgraph app["stock-bot（Spring Boot 3.5 / Java 21）"]
        subgraph tgl["telegram/"]
            poller["TelegramPoller<br/>getUpdates long polling<br/>virtual thread 分派"]
            client["TelegramClient<br/>sendMessage / editMessageText<br/>answerCallbackQuery"]
            notifier["Notifier<br/>節流・429 重試・403 停推・切段"]
            kb["Keyboards<br/>主選單 / inline / ForceReply"]
        end
        subgraph botl["bot/"]
            handler["CommandHandler<br/>指令・選單・按鈕 → 畫面(View)<br/>推播設定流程"]
            notice["SetupNoticeSender<br/>啟動時通知既有使用者"]
        end
        subgraph rpt["report/"]
            job["IntradayReportJob<br/>@Scheduled 每分鐘檢查<br/>只推給到期的使用者"]
            fmt["ReportFormatter<br/>紅漲綠跌訊息格式"]
        end
        subgraph mkt["market/"]
            qs["QuoteService<br/>30 秒快取"]
            fc["FugleClient<br/>RateLimiter 50 次/分<br/>429 重試"]
        end
        subgraph sub["subscription/"]
            repo["SubscriberRepository<br/>WatchRepository<br/>PushSchedule"]
            ju["JdbcUtil<br/>JdbcTemplate 封裝"]
        end
    end

    fugle["富果行情 REST API<br/>/intraday/quote/{symbol}<br/>免費方案 60 次/分"]
    db[("MySQL 8<br/>subscriber / watch")]

    user <--> tg
    tg -- "updates（訊息 / 按鈕點擊）" --> poller
    poller --> handler
    handler --> kb
    handler --> notifier
    handler -- "原地編輯 / 回應按鈕" --> client
    notifier --> client
    client --> tg
    job --> fmt
    job --> notifier
    handler --> qs
    job --> qs
    qs --> fc --> fugle
    handler --> repo
    notice --> handler
    notice --> notifier
    job --> repo
    repo --> ju --> db
```

## 2. 定時推播流程

交易日 09:00 ~ 13:59 每分鐘觸發一次；每位使用者依自己設定的時段與間隔決定這一分鐘要不要推。
例如 `09:05–10:00、每 20 分鐘` → 09:05、09:25、09:45 會推，其他分鐘不推。

```mermaid
sequenceDiagram
    autonumber
    participant S as Spring Scheduler
    participant J as IntradayReportJob
    participant SR as SubscriberRepository
    participant WR as WatchRepository
    participant Q as QuoteService
    participant F as 富果 API
    participant N as Notifier
    participant T as Telegram

    S->>J: cron 每分鐘觸發（Asia/Taipei）
    J->>SR: findPushReady()
    SR-->>J: 啟用中、且已設定時段與間隔的使用者
    Note over J: PushSchedule.isDue(這一分鐘)<br/>在時段內，且 (現在 − 開始) % 間隔 == 0
    alt 這一分鐘沒有人到期
        Note over J: 結束（不查富果）
    else 有人到期
        J->>WR: findGroupedByChat()
        WR-->>J: {chatId → [2330, 2317 ...]}，只留到期的人
        Note over J: 股票取聯集，每檔只查一次，結果共用
        loop 每一檔股票
            J->>Q: getQuote(symbol)
            alt 30 秒內查過
                Q-->>J: 快取結果
            else
                Q->>F: GET /intraday/quote/{symbol}（RateLimiter 排隊）
                F-->>Q: 報價 JSON
            end
        end
        alt 沒有任何一檔的資料日期是今天
            Note over J: 判斷為休市（國定假日 / 颱風假），結束
        else 有今天的資料
            loop 每個到期的使用者
                J->>N: send(文字, [🔄 更新][📋 管理清單])
                N->>T: sendMessage
                alt 403 使用者封鎖 bot
                    N->>SR: setEnabled(false)
                else 429 太頻繁
                    N->>N: 等待 retry_after 後重送
                end
            end
        end
    end
```

### 推播設定流程

新使用者 `/start` 後、既有使用者在服務升級後收到通知時，都會進入兩步驟設定；兩步都完成才開始推播。

```mermaid
flowchart TB
    new(["新使用者 /start"]) --> w
    old(["既有使用者<br/>啟動時收到通知"]) --> w
    menu(["⚙️ 推播設定 → 修改時段"]) --> w
    menu2(["⚙️ 推播設定 → 修改間隔"]) --> i
    w["① 選擇時段<br/>整個盤中 / 開盤 1 小時 / 收盤前 1 小時<br/>✏️ 自訂：回覆 09:00-13:30<br/>（格式錯誤會重新提示）"]
    i["② 選擇間隔<br/>1 / 5 / 10 / 15 / 30 / 60 分鐘<br/>✏️ 自訂：回覆分鐘數<br/>（非 1~270 會重新提示）"]
    w --> chk1{"已設定間隔？"}
    chk1 -- "否" --> i
    chk1 -- "是" --> done
    i --> chk2{"已設定時段？"}
    chk2 -- "否" --> w
    chk2 -- "是" --> done["✅ 設定完成<br/>顯示時段、間隔、每天推播時間"]
```

## 3. 使用者互動流程

三種輸入（斜線指令、主選單按鈕、inline 按鈕）最後都落到同一組畫面：報價、清單、設定、新增提示。

```mermaid
flowchart TD
    start(["收到 update"]) --> type{"update 類型"}

    type -- "message" --> allow1{"chat id<br/>在白名單？"}
    allow1 -- "否" --> deny["⛔ 沒有權限"]
    allow1 -- "是" --> kind{"訊息內容"}
    kind -- "/指令" --> cmd["/start /help /add /remove<br/>/list /now /settings /pause /resume"]
    kind -- "主選單文字" --> menu["📊 即時報價 / 📋 觀察清單<br/>➕ 新增股票 / ⚙️ 推播設定"]
    kind -- "回覆「新增提示」" --> addReply["解析代號 → 新增"]
    kind -- "回覆「時段 / 間隔提示」" --> setReply["解析 → 儲存設定"]
    kind -- "其他（私訊）" --> hint["提示 + 重新顯示主選單"]

    type -- "callback_query" --> allow2{"chat id<br/>在白名單？"}
    allow2 -- "否" --> toastDeny["跳出提示：沒有權限"]
    allow2 -- "是" --> cb{"callback_data"}
    cb -- "q:refresh" --> editQuote["原地更新報價"]
    cb -- "l:rm:2330" --> rm["移除 → 原地更新清單"]
    cb -- "s:pause / s:resume" --> toggle["切換推播 → 原地更新設定"]
    cb -- "q:new / l:new / a" --> newMsg["另送一則<br/>報價 / 清單 / 新增提示"]
    cb -- "w:0900-1330 / i:10<br/>s:window / s:interval" --> setCb["儲存設定 → 原地更新<br/>下一步或設定總覽"]

    cmd --> views
    menu --> views
    addReply --> views
    setReply --> views
    setCb --> views
    newMsg --> views
    editQuote --> views
    rm --> views
    toggle --> views

    subgraph views["畫面（View = 文字 + 按鈕）"]
        v1["報價<br/>🔄 更新 · 📋 管理清單"]
        v2["觀察清單<br/>❌ 每檔一顆 · ➕ 新增 · 📊 即時報價"]
        v3["推播設定<br/>🕘 時段 · ⏱ 間隔 · ⏸ 暫停 / ▶️ 恢復"]
        v4["新增提示<br/>ForceReply"]
    end

    views --> out{"來源"}
    out -- "按鈕原地更新" --> edit["editMessageText<br/>＋ answerCallbackQuery 小提示"]
    out -- "其他" --> send["Notifier.send"]
```

## 4. 資料表

```mermaid
erDiagram
    subscriber ||--o{ watch : "觀察"
    subscriber {
        BIGINT chat_id PK "Telegram chat id"
        VARCHAR username
        BOOLEAN enabled "是否接收定時推播"
        TIME push_start "推播開始（NULL = 未設定）"
        TIME push_end "推播結束"
        INT interval_minutes "推播間隔（分鐘）"
        BOOLEAN setup_notice_pending "啟動時待通知重新設定"
        DATETIME created_at
        DATETIME updated_at
    }
    watch {
        BIGINT id PK "自增，決定清單順序"
        BIGINT chat_id "UK(chat_id, symbol)"
        VARCHAR symbol "股票代號"
        VARCHAR name "股票名稱"
        DATETIME created_at
    }
```
