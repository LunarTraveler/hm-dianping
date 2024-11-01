package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

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

    private final CacheClient cacheClient;

    // 线程池(大小为10)
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 单个线程就够用了
    private static final ExecutorService CACHE_REBUILD_SINGLE_EXECUTOR = Executors.newSingleThreadExecutor();

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
        // 那个函数还可以是this::getById
//        Shop shop1 = cacheClient.
//                queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        // 使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        return Result.ok(shop);

        // 使用逻辑缓存解决缓存击穿
//        Shop shop = queryWithLogicCache(id);
//        return Result.ok(shop);

        Shop shop1 = cacheClient.queryWithLogicCache(CACHE_SHOP_KEY, LOCK_SHOP_KEY, 1L, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
        return Result.ok(shop1);
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
     * 解决缓存击穿（使用的是逻辑过期，这里的redis真是太快了）
     * @param id
     * @return
     */
    private Shop queryWithLogicCache(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 逻辑缓存是一直存在的，主要是判断逻辑时间是否过期了
        if (shopJson == null) {
            // 这里没有再做空值缓存了
            return null;
        }

        // 获取里面的数据
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 判断是否过期
        // 未过期(是正确的数据)
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 获取到锁，另开一个线程帮我把数据更新写进redis中
        if (tryLock(lockKey)) {
            CACHE_REBUILD_SINGLE_EXECUTOR.submit(() -> {
                try {
                    // 缓存重建
                    saveShopToRedis(id, LOCK_SHOP_TTL);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        return shop;// 这个是已过期的数据（没有获取到锁）
    }

    /**
     * 缓存重建
     * @param id
     * @param expireSeconds 这个是在当前的时间上的过期时间
     */
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = shopMapper.selectById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = new Page<>(current, DEFAULT_PAGE_SIZE);
            page = shopMapper.selectPage(page, new LambdaQueryWrapper<Shop>()
                    .eq(Shop::getTypeId, typeId));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // geo本质是一种zset数据类型，进行一个传统的分页并按照距离的远近来排序
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int to = from + DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;

        // GEOSEARCH key BYLONLAT x y BYRADIUS 5000 WITHDISTANCE
        // 从redis中筛选出符合条件的geo [0 to]
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key, GeoReference.fromCoordinate(x, y), new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(to));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> shopIdToDistance = new HashMap<>(list.size());

        // 截取[from to]这一页的全部商铺信息
        list.stream().skip(from).forEach(geoResult -> {
            // 获取商铺id
            String shopIdStr = geoResult.getContent().getName();
            // 获取相对的距离
            Distance distance = geoResult.getDistance();

            ids.add(Long.parseLong(shopIdStr));
            shopIdToDistance.put(shopIdStr, distance);
        });

        // 给查出的每一个shop都加上对应的distance信息
        List<Shop> shopList = shopMapper.selectList(new LambdaQueryWrapper<Shop>()
                .in(Shop::getId, ids).orderByDesc(Shop::getId));
        shopList.forEach(shop -> {
            shop.setDistance(shopIdToDistance.get(shop.getId().toString()).getValue());
        });

        return Result.ok(shopList);
    }

}

