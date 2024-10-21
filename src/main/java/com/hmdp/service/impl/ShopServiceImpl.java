package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final ShopMapper shopMapper;

    /**
     * 根据id查询商铺信息
     * 缓存穿透
     * 1.缓存一个空值（目前先使用这个）
     * 2.使用布隆过滤器加以判断
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) throws JsonProcessingException {
        // 解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        return Result.ok(shop);

        // 使用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        return Result.ok(shop);
    }

    /**
     * 解决缓存穿透（使用的是空值缓存）
     * @param id
     * @return
     * @throws JsonProcessingException
     */
    private Shop queryWithPassThrough(Long id) throws JsonProcessingException {
        String key = CACHE_SHOP_KEY + id;
        String shopjson = stringRedisTemplate.opsForValue().get(key);

        // 命中缓存，直接返回
        if (shopjson != null && !shopjson.isEmpty()) {
            Shop shop = objectMapper.readValue(shopjson, Shop.class);
            return shop;
        }

        // 采用缓存空对象来解决缓存穿透问题
        if (shopjson.isEmpty()) {
            return null;
        }

        // 没有命中缓存，要查询数据库
        Shop shop = shopMapper.selectById(id);
        // 数据库中不存在，返回错误信息
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, null, CACHE_SHOP_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，加入缓存，返回信息
        stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(shop));
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 解决缓存击穿（使用的是互斥锁）
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;

        // 命中缓存直接返回
        if (shopJson != null && !shopJson.isEmpty()) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        String lockKey = LOCK_SHOP_KEY + id;
        try {
            // 没有命中的话获取互斥锁
            // 没有获取到互斥锁，所以休眠一段时间
            if (!tryLock(lockKey)) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 做一个doubleCheck检查
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 命中缓存直接返回
            if (shopJson != null && !shopJson.isEmpty()) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 获取到互斥锁
            shop = shopMapper.selectById(id);
            // 不存在做空值缓存
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, null, CACHE_SHOP_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在存入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        return shop;
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

    @Override
    @Transactional // 默认触发为RuntimeException
    public Result update(Shop shop) {
        // 1.更新数据库（这样的先后顺序出错的概率比较低）
        if (shop.getId() == null) {
            return Result.fail("传来的商铺没有主键错误");
        }
        shopMapper.updateById(shop);
        // 2.删除缓存(分级缓存，层次结构比较清晰)
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}

