-- 每位使用者自訂推播時段與間隔。三個欄位都有值才算「已設定」，未設定的使用者不會收到定時推播。
ALTER TABLE subscriber
    ADD COLUMN push_start            TIME     NULL COMMENT '推播開始時間（含）'           AFTER enabled,
    ADD COLUMN push_end              TIME     NULL COMMENT '推播結束時間（含）'           AFTER push_start,
    ADD COLUMN interval_minutes      INT      NULL COMMENT '推播間隔（分鐘，最少 1）'      AFTER push_end,
    ADD COLUMN setup_notice_pending  BOOLEAN  NOT NULL DEFAULT FALSE
        COMMENT '啟動時是否要通知這位使用者重新設定推播' AFTER interval_minutes;

-- 既有使用者：標記為待通知。服務啟動後會發一次訊息請他們設定，送出後清除標記。
-- 之後新註冊的使用者預設為 FALSE，改由 /start 直接進入設定流程。
UPDATE subscriber SET setup_notice_pending = TRUE;
