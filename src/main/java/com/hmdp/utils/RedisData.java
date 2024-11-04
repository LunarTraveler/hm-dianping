package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

// 逻辑过期的附加字段的实现方式
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
