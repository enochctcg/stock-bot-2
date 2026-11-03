-- 觀察清單中所有股票的歷史報價：交易日每 30 分鐘一筆，另加一筆收盤後最終價。
-- 收盤後依此產生每日 Excel 推播給使用者；超過保留期限（預設一個月）的資料每天自動刪除。
CREATE TABLE IF NOT EXISTS quote_snapshot (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    trade_date      DATE           NOT NULL COMMENT '交易日',
    snapshot_time   TIME           NOT NULL COMMENT '記錄時間（台北時間，到分鐘）',
    symbol          VARCHAR(10)    NOT NULL,
    name            VARCHAR(64),
    price           DECIMAL(12, 2) NULL     COMMENT '成交價；尚無成交時為 NULL',
    change_amount   DECIMAL(12, 2) NULL     COMMENT '漲跌（元）',
    change_percent  DECIMAL(8, 2)  NULL     COMMENT '漲跌幅（%）',
    created_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot (trade_date, snapshot_time, symbol)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
