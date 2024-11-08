package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class RedisIdIncrement {

    private final StringRedisTemplate stringRedisTemplate;

    // 这个代表上线的那个时间点，这样的话这个系统可以使用69年
    public long BEGIN_TIMESTAMP = 1729696772L;

    public int COUNT_BITS = 12;

    public Long nextId(String keyPrefix) {
        // 第一步：生成一个时间戳
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 第二步：生成一个序列号(这里使用redis锁的自增长,一天为单位)
        // 同时也代表了每一天的营业单数
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("increment:" + keyPrefix + ":" + date);

        long id = timestamp << COUNT_BITS | count;
        // 拼接起来
        return Long.valueOf(id);
    }

}
