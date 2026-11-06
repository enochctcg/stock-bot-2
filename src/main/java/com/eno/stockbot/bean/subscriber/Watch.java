package com.eno.stockbot.bean.subscriber;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * watch 資料表：某位使用者觀察清單裡的一檔股票。
 */
@Data
@TableName("watch")
public class Watch {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chatId;
    private String symbol;
    private String name;
    private LocalDateTime createdAt;
}
