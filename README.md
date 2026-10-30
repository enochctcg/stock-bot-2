ㄊ# stock-bot

Telegram 機器人：交易日盤中每 10 分鐘推播觀察清單的即時股價與漲跌幅，資料來源為富果行情 API（免費方案即可）。

- Spring Boot 3.5 / Java 21
- Telegram：直接呼叫 Bot HTTP API（long polling，不需要公網 IP）
- 富果：`GET /intraday/quote/{symbol}`（免費方案每分鐘 60 次，程式限制在 50 次）
- 儲存：MySQL 8（8.0.19 以上），啟動時自動執行 `schema.sql` 建表

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
SELECT * FROM stockbot.subscriber;
SELECT chat_id, symbol, name FROM stockbot.watch ORDER BY chat_id, id;
```

## 操作方式

傳 `/start` 後，輸入框下方會出現常駐的主選單（Reply Keyboard）：

| 按鈕 | 功能 |
|---|---|
| 📊 即時報價 | 查詢目前價格，訊息附 `🔄 更新`（原地更新）、`📋 管理清單` |
| 📋 觀察清單 | 每檔一個 `❌` 按鈕，點了就移除並原地更新清單；下方有 `➕ 新增`、`📊 即時報價` |
| ➕ 新增股票 | 送出 ForceReply 提示，直接回覆代號即可，可一次多檔（每人上限 20 檔） |
| ⚙️ 推播設定 | 顯示推播狀態，`⏸ 暫停推播` / `▶️ 恢復推播` 切換 |

每 10 分鐘的推播訊息也附有 `🔄 更新`、`📋 管理清單` 按鈕。主選單被收起來時，隨便打字或傳 `/help` 會重新顯示。

原本的指令仍可使用：

| 指令 | 說明 |
|---|---|
| `/start` | 註冊、顯示 chat id 與主選單 |
| `/add 2330 2317` | 加入觀察清單；不帶參數時送出新增提示 |
| `/remove 2330` | 移除；不帶參數時顯示可點擊移除的清單 |
| `/list`、`/now`、`/settings` | 觀察清單、即時報價、推播設定 |
| `/pause`、`/resume` | 暫停、恢復定時推播 |

按鈕的 `callback_data` 格式定義在 `CommandHandler`（`q:refresh`、`l:rm:2330`、`s:pause`…），上限 64 bytes。

推播範例：

```
⏱ 10:20 盤中報價
🔴 2330 台積電  1,085.00  +15.00 (+1.40%)
🟢 2317 鴻海  212.50  -1.00 (-0.47%)
⚪ 6488 環球晶  尚無成交
```

## 推播時間

| 時間 | cron（`application.yml`） |
|---|---|
| 09:10 ~ 12:50 每 10 分鐘 | `bot.schedule.cron-intraday` |
| 13:00、13:10、13:20、13:33（收盤價） | `bot.schedule.cron-close` |

- 09:00 剛開盤很多股票還沒成交，所以從 09:10 開始
- 13:30 收盤撮合結果需要一點時間，最後一次排在 13:33 拿正式收盤價
- 國定假日、颱風假：富果回傳的資料日期不是今天時，自動略過推播，不需要維護假日表

## 額度估算

每輪推播時，所有使用者觀察清單的**聯集**中每檔只查一次，結果共用。
30 秒內重複查詢同一檔會直接用快取（`bot.fugle.cache-ttl`）。

聯集 30 檔 → 每 10 分鐘 30 次請求，遠低於免費方案的每分鐘 60 次。
聯集超過 50 檔時，限流器會自動排隊，一輪推播會花超過一分鐘，但不會被富果回 429。

## 專案結構

架構圖、推播流程、互動流程與資料表見 [docs/architecture.md](docs/architecture.md)。

```
com.eno.stockbot
├── common/        JdbcUtil（JdbcTemplate 封裝：Optional 單筆、record 映射、慢查詢 log）
├── bot/           Command（指令解析）、Incoming（訊息 / 按鈕輸入）、CommandHandler（畫面與按鈕邏輯）
├── config/        BotProperties（bot.* 設定）、AppConfig
├── market/        FugleClient（REST + 限流 + 429 重試）、QuoteService（快取）、Quote、RateLimiter
├── report/        IntradayReportJob（排程推播）、ReportFormatter（訊息格式）
├── subscription/  SubscriberRepository、WatchRepository（JdbcTemplate + MySQL）
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
- 表結構開始需要變更時，把 `schema.sql` 換成 Flyway migration
