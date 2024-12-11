package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期时间添加
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    // 用来存 Shop
    private Object data;
}
