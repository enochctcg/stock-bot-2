package com.eno.stockbot.mapper.subscriber;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eno.stockbot.bean.subscriber.Watch;

/**
 * watch 資料表。簡單的查詢 / 刪除用 BaseMapper + Wrapper，這裡只放自訂 SQL。
 */
public interface WatchMapper extends BaseMapper<Watch> {

    /** @return MySQL：新增回 1、已存在且名稱有變回 2、完全相同回 0 */
    @Insert("""
            INSERT INTO watch (chat_id, symbol, name) VALUES (#{chatId}, #{symbol}, #{name}) AS n
            ON DUPLICATE KEY UPDATE name = n.name
            """)
    int upsert(@Param("chatId") long chatId, @Param("symbol") String symbol, @Param("name") String name);
}
