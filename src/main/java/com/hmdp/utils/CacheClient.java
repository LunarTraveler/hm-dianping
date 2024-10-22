package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    // 线程池(大小为10)
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 将Java对象序列化为jsonString存储，并且可以设置过期时间ttl
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 将Java对象序列化为jsonString存储，并且可以设置逻辑过期时间ttl
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        // 这里是逻辑过期，在内存是没有过期时间的
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决查询
     * @param keyPrefix
     * @param id
     * @param clazz
     * @param function
     * @param time
     * @param timeUnit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T, ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> clazz, Function<ID, T> function, Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 命中缓存，直接返回
        if (!StringUtils.isEmpty(json)) {
            T t = JSONUtil.toBean(json, clazz);
            return t;
        }

        // 采用缓存空对象("")来解决缓存穿透问题
        if (json.isEmpty()) {
            return null;
        }

        // 没有命中缓存，要查询数据库
        T t = function.apply(id);
        // 数据库中不存在，返回错误信息
        if (t == null) {
            stringRedisTemplate.opsForValue().set(key, null, CACHE_SHOP_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, t, time, timeUnit);
        return t;
    }

    /**
     * 逻辑缓存解决缓存击穿
     * @param keyPrefix
     * @param lockPrefix
     * @param id
     * @param clazz
     * @param function
     * @param time
     * @param timeUnit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T, ID> T queryWithLogicCache(String keyPrefix, String lockPrefix, ID id, Class<T> clazz, Function<ID, T> function, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String lockKey = lockPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 逻辑缓存是一直存在的，主要是判断逻辑时间是否过期了
        if (json == null) {
            // 这里没有再做空值缓存了
            return null;
        }

        // 获取里面的数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);

        // 判断是否过期
        // 未过期(是正确的数据)
        if (expireTime.isAfter(LocalDateTime.now())) {
            return t;
        }

        // 获取到锁，另开一个线程帮我把数据更新写进redis中
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    T t1 = function.apply(id);
                    // 写入缓存
                    setWithLogicalExpire(key, t1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        return t;// 这个是已过期的数据（没有获取到锁）
    }

    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 防止自动拆箱装箱的异常结果
        return BooleanUtil.isTrue(result);
    }

    /**
     * 解锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
