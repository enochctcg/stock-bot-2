package com.eno.stockbot.mapper.subscriber;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eno.stockbot.bean.subscriber.Subscriber;
import com.eno.stockbot.bean.subscriber.SubscriberSummary;

/**
 * subscriber 資料表。簡單的查詢 / 更新用 BaseMapper + Wrapper，這裡只放自訂 SQL。
 */
public interface SubscriberMapper extends BaseMapper<Subscriber> {

    String SUMMARY_SQL = """
            SELECT s.chat_id, s.username, s.enabled, s.created_at,
                   (SELECT COUNT(*) FROM watch w WHERE w.chat_id = s.chat_id) AS watch_count
              FROM subscriber s
            """;

    /**
     * 新增或更新使用者；既有使用者只更新 username（傳 null 時保留原值），不動其他設定。
     *
     * @return MySQL：新增回 1、username 有變回 2、沒有變化回 0
     */
    @Insert("""
            INSERT INTO subscriber (chat_id, username) VALUES (#{chatId}, #{username}) AS n
            ON DUPLICATE KEY UPDATE username = COALESCE(n.username, subscriber.username)
            """)
    int upsert(@Param("chatId") long chatId, @Param("username") String username);

    @Select(SUMMARY_SQL + " WHERE s.chat_id = #{chatId}")
    SubscriberSummary selectSummary(@Param("chatId") long chatId);

    /** 所有使用者，依訂閱時間排序。 */
    @Select(SUMMARY_SQL + " ORDER BY s.created_at, s.chat_id")
    List<SubscriberSummary> selectAllSummaries();
}
