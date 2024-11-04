package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    private final ShopTypeMapper shopTypeMapper;

    private final ObjectMapper objectMapper;

    /**
     * 查询所有的商店类型
     * @return
     */
    @Override
    public List<ShopType> queryAll() throws JsonProcessingException {
        String key = CACHE_SHOP_TYPE_KEY;

        // 方式一 使用redis 的 list结构进行存储
        // return saveAsList(key);
        // 方式二 使用redis 的 string直接存储
        return saveAsString(key);
    }

    private List<ShopType> saveAsList(String key) throws JsonProcessingException {
        // 里面的字符串是json类型
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (shopTypeList != null && !shopTypeList.isEmpty()) {
            List<ShopType> result = new ArrayList<>();
            for (String s : shopTypeList) {
                ShopType shopType = objectMapper.readValue(s, ShopType.class);
                result.add(shopType);
            }
            return result;
        }

        // 没有找到，去数据库里查询
        List<ShopType> shopTypes = shopTypeMapper.selectList(null);
        if (shopTypes != null && !shopTypes.isEmpty()) {
            // 存入缓存
            for (ShopType shopType : shopTypes) {
                String shopTypeJson = objectMapper.writeValueAsString(shopType);
                stringRedisTemplate.opsForList().rightPush(key, shopTypeJson);
            }
            stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
            return shopTypes;
        }

        // 如果数据库里也没有的话，返回null还是抛出异常，看需求。。。
        return Collections.emptyList();
    }

    private List<ShopType> saveAsString(String key) throws JsonProcessingException {
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if (shopTypeJson != null && !shopTypeJson.isEmpty()) {
            return objectMapper.readValue(shopTypeJson, new TypeReference<List<ShopType>>() {});
        }

        // 没有找到，去数据库里查询
        List<ShopType> shopTypes = shopTypeMapper.selectList(null);
        if (shopTypes != null && !shopTypes.isEmpty()) {
            String jsonArray = objectMapper.writeValueAsString(shopTypes);
            stringRedisTemplate.opsForValue().set(key, jsonArray, 1, TimeUnit.DAYS);
            return shopTypes;
        }

        // 如果数据库里也没有的话，返回null还是抛出异常，看需求。。。
        return Collections.emptyList();
    }

}
