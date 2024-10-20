package com.hmdp.service.impl;

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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) throws JsonProcessingException {
        String key = CACHE_SHOP_KEY + id;
        String shopjson = stringRedisTemplate.opsForValue().get(key);

        // 命中缓存，直接返回
        if (shopjson != null) {
            return Result.ok(objectMapper.readValue(shopjson, Shop.class));
        }

        // 没有命中缓存，要查询数据库
        Shop shop = shopMapper.selectById(id);
        // 数据库中不存在，返回错误信息
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 存在，加入缓存，返回信息
        stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(shop));
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
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

