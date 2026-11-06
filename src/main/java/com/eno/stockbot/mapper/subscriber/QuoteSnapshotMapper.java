package com.eno.stockbot.mapper.subscriber;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eno.stockbot.bean.subscriber.QuoteSnapshot;

/**
 * quote_snapshot 資料表。查詢 / 刪除用 BaseMapper + Wrapper，這裡只放自訂 SQL。
 */
public interface QuoteSnapshotMapper extends BaseMapper<QuoteSnapshot> {

    /** 一次寫入多筆；同一時間點重複記錄（例如服務重啟後補跑）會覆蓋成最新的報價。 */
    @Insert("""
            <script>
            INSERT INTO quote_snapshot
                   (trade_date, snapshot_time, symbol, name, price, change_amount, change_percent)
            VALUES
            <foreach collection="list" item="s" separator=",">
                   (#{s.tradeDate}, #{s.snapshotTime}, #{s.symbol}, #{s.name},
                    #{s.price}, #{s.changeAmount}, #{s.changePercent})
            </foreach>
            AS n
            ON DUPLICATE KEY UPDATE name = n.name, price = n.price,
                   change_amount = n.change_amount, change_percent = n.change_percent
            </script>
            """)
    int upsertBatch(@Param("list") List<QuoteSnapshot> snapshots);
}
