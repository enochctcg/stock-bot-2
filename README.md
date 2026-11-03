# stock-bot

Telegram 機器人：交易日盤中依每位使用者自訂的時段與間隔（最短 1 分鐘），推播觀察清單的即時股價與漲跌幅，資料來源為富果行情 API（免費方案即可）。

- Spring Boot 3.5 / Java 21
- Telegram：直接呼叫 Bot HTTP API（long polling，不需要公網 IP）
- 富果：`GET /intraday/quote/{symbol}`（免費方案每分鐘 60 次，程式限制在 50 次）
- 儲存：MySQL 8（8.0.19 以上），資料表由 Flyway 管理（`src/main/resources/db/migration`），啟動時自動升級

## 快速開始

### 1. 準備 token

1. Telegram 找 **@BotFather** → `/newbot` → 取得 bot token
2. 富果開發者網站 → 行情 → 金鑰申請，取得 API key

### 2. 本機執行

先準備 MySQL。沒有現成的話，用 compose 只起資料庫：

```bash
cp .env.example .env              # 填入 DB_PASSWORD、DB_ROOT_PASSWORD
docker compose up -d mysql
```

或者用你自己的 MySQL，先建帳號（資料庫不存在時 JDBC URL 的 `createDatabaseIfNotExist=true` 會自動建立）：

```sql
CREATE DATABASE IF NOT EXISTS stockbot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'stockbot'@'%' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON stockbot.* TO 'stockbot'@'%';
```

接著執行程式：

```bash
export TELEGRAM_BOT_TOKEN=你的token
export FUGLE_API_KEY=你的富果key
export DB_PASSWORD=change-me      # DB_HOST / DB_PORT / DB_NAME / DB_USERNAME 預設為 localhost / 3306 / stockbot / stockbot
mvn spring-boot:run
```

IntelliJ：在 Run Configuration 的 Environment variables 填入上面的變數，執行 `StockBotApplication`。

### 3. 設定白名單（建議）

對 bot 傳送 `/start`，它會回覆你的 chat id。把它設進 `ALLOWED_CHAT_IDS` 後重啟，其他人就無法使用你的 bot 消耗富果額度：

```bash
export ALLOWED_CHAT_IDS=123456789          # 多人用逗號分隔
```

### 4. Docker 部署

```bash
cp .env.example .env    # 填入 token、key、chat id、DB 密碼
docker compose up -d --build
docker compose logs -f stock-bot
```

compose 會同時啟動 MySQL 和 bot，等 MySQL healthcheck 通過後才啟動 bot。
MySQL 資料在 `./data/mysql`，重建容器不會遺失訂閱。

查看資料：

```sql
SELECT chat_id, username, enabled, push_start, push_end, interval_minutes FROM stockbot.subscriber;
SELECT chat_id, symbol, name FROM stockbot.watch ORDER BY chat_id, id;
```

## 操作方式

傳 `/start` 後，輸入框下方會出現常駐的主選單（Reply Keyboard）：

| 按鈕 | 功能 |
|---|---|
| 📊 即時報價 | 查詢目前價格，訊息附 `🔄 更新`（原地更新）、`📋 管理清單` |
| 📋 觀察清單 | 每檔一個 `❌` 按鈕，點了就移除並原地更新清單；下方有 `➕ 新增`、`📊 即時報價` |
| ➕ 新增股票 | 送出 ForceReply 提示，直接回覆代號即可，可一次多檔（每人上限 20 檔） |
| ⚙️ 推播設定 | 顯示推播時段、間隔與狀態；可修改時段、間隔，或 `⏸ 暫停` / `▶️ 恢復` |

定時推播的訊息也附有 `🔄 更新`、`📋 管理清單` 按鈕。主選單被收起來時，隨便打字或傳 `/help` 會重新顯示。

原本的指令仍可使用：

| 指令 | 說明 |
|---|---|
| `/start` | 註冊、顯示 chat id 與主選單；尚未設定推播時直接進入設定流程 |
| `/add 2330 2317` | 加入觀察清單；不帶參數時送出新增提示 |
| `/remove 2330` | 移除；不帶參數時顯示可點擊移除的清單 |
| `/list`、`/now`、`/settings` | 觀察清單、即時報價、推播設定（時段、間隔） |
| `/pause`、`/resume` | 暫停、恢復定時推播 |

按鈕的 `callback_data` 格式定義在 `CommandHandler`（`q:refresh`、`l:rm:2330`、`s:pause`…），上限 64 bytes。

推播範例：

```
⏱ 10:20 盤中報價
🔴 2330 台積電  1,085.00  +15.00 (+1.40%)
🟢 2317 鴻海  212.50  -1.00 (-0.47%)
⚪ 6488 環球晶  尚無成交
```

## 推播設定

每位使用者自己設定**推播時段**與**推播間隔**，兩者都設定完成才會開始定時推播：

1. **時段**：交易日 09:00 ~ 13:30 之間。預設選項有「整個盤中 09:00–13:30」「開盤 1 小時」「收盤前 1 小時」，也可以自訂（輸入 `09:00-13:30`、`0900-1330`、`9:00~13:30` 都可以）
2. **間隔**：1 ~ 270 分鐘，預設選項 1 / 5 / 10 / 15 / 30 / 60 分鐘，也可以自訂

間隔從時段的開始時間起算，頭尾都包含。例如 `09:05–10:00、每 20 分鐘` → 09:05、09:25、09:45。

- **新使用者**：`/start` 後直接進入兩步驟設定；設定前加入的股票會提示「還沒設定推播」
- **既有使用者**（升級前就訂閱的人）：V2 migration 會把他們標記為待通知，服務啟動後發一次「請重新設定推播」的訊息（附設定按鈕），重啟不會重複通知。重新設定前不會收到定時推播
- **排程**：`bot.schedule.cron`（預設 `0 * 9-13 * * MON-FRI`）每分鐘檢查一次，只推給這一分鐘輪到的使用者
- **國定假日、颱風假**：富果回傳的資料日期不是今天時，自動略過推播，不需要維護假日表

## 額度估算

同一分鐘輪到的所有使用者，觀察清單取**聯集**後每檔只查一次，結果共用。
30 秒內重複查詢同一檔會直接用快取（`bot.fugle.cache-ttl`）。

最壞情況是大家都設定每 1 分鐘：每分鐘的請求數 = 所有人觀察清單的聯集檔數。
富果免費方案每分鐘 60 次，程式限制在 50 次，所以**聯集最好控制在 50 檔以內**。
超過時限流器會自動排隊、不會被回 429，但那一輪推播會延遲到下一分鐘之後，下一分鐘的推播可能被略過。

## Log

每一行都帶**台北時間（毫秒）**、**執行緒名稱**，處理使用者操作時還會帶 chat id 與 Telegram update id：

```
2026-11-02 09:10:00.012 INFO  [push-job-1] [chat=- upd=-] IntradayReportJob - 09:10 排程檢查：3 位已設定推播，1 位到期 [987654321]
2026-11-02 09:10:00.358 INFO  [push-job-1] [chat=987654321 upd=-] IntradayReportJob - 09:10 推播成功 → chat 987654321，2 檔：[2330, 2317]
2026-11-02 10:03:41.207 INFO  [tg-update-12] [chat=987654321 upd=123456790] TelegramPoller - 收到 按鈕 data=l:rm:2330 msg=55 user=eno_dev
2026-11-02 10:03:41.231 INFO  [tg-update-12] [chat=987654321 upd=123456790] WatchRepository - [DB] 觀察清單移除 chat 987654321：2330（已刪除）
```

| 執行緒 | 用途 |
|---|---|
| `tg-update-N` | 處理一筆使用者訊息或按鈕（virtual thread，每筆一條） |
| `push-job-N` | 每分鐘的定時推播排程 |
| `setup-notice` | 啟動時通知既有使用者重新設定 |
| `telegram-poller` | long polling 收訊迴圈 |

記錄的事件：

- **收到更新**：每筆訊息 / 按鈕的內容、使用者，以及處理耗時
- **資料變更**（`[DB]`）：註冊、新增 / 移除股票、修改推播時段 / 間隔、暫停 / 恢復
- **訊息**：每則送出的訊息（只印第一行）、每次原地更新、按鈕提示
- **推播**：每輪到期的使用者、查詢的股票、每位使用者的推播結果、整輪耗時；沒人到期的分鐘只記 DEBUG
- **錯誤**：Telegram 限流 / 網路重試、使用者封鎖 bot、富果 API 失敗

輸出位置：console，以及 `logs/stock-bot.log`（10MB 或跨日切檔，壓縮保留 30 天）。
Docker 部署時 `logs/` 掛載在主機上。格式設定在 `application.yml` 的 `logging.pattern`。

## 資料表版本（Flyway）

- `V1__init.sql`：原本的 `subscriber`、`watch`
- `V2__push_schedule.sql`：`subscriber` 新增 `push_start`、`push_end`、`interval_minutes`、`setup_notice_pending`

之前由 `schema.sql` 建好的資料庫，第一次啟動時會自動標記為 V1（`baseline-on-migrate`），再執行 V2。
之後要改表結構，新增 `V3__xxx.sql` 即可，不要修改已執行過的檔案。

## 專案結構

程式交互與使用者操作流程圖見 [docs/flow.md](docs/flow.md)。

```
com.eno.stockbot
├── common/        JdbcUtil（JdbcTemplate 封裝：Optional 單筆、record 映射、慢查詢 log）
├── bot/           Command、Incoming、CommandHandler（畫面、按鈕、推播設定流程）、SetupNoticeSender（啟動時通知既有使用者）
├── config/        BotProperties（bot.* 設定）、AppConfig
├── market/        FugleClient（REST + 限流 + 429 重試）、QuoteService（快取）、Quote、RateLimiter
├── report/        IntradayReportJob（每分鐘檢查、推給到期的使用者）、ReportFormatter（訊息格式）
├── subscription/  Subscriber、PushSchedule（時段 / 間隔解析與到期判斷）、SubscriberRepository、WatchRepository
└── telegram/      TelegramClient、TelegramPoller（long polling）、Notifier（429/403 處理、訊息切段）、Keyboards（按鈕）
```

## 第一次執行前建議

`Quote.from()` 依富果文件的欄位（`lastPrice`、`closePrice`、`change`、`changePercent`、`isTrial`…）解析。
建議先用 curl 打一次確認回應格式：

```bash
curl -H "X-API-KEY: $FUGLE_API_KEY" https://api.fugle.tw/marketdata/v1.0/stock/intraday/quote/2330
```

## 之後可以擴充

- 漲跌幅超過門檻的即時警報 → 行情改用富果 WebSocket（免費方案可訂閱 5 檔）
