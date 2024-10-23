package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.utils.RedisIdIncrement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ShopServiceImplTest {

    // 线程池(大小为10)
    private ExecutorService EXECUTOR = Executors.newFixedThreadPool(500);

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private RedisIdIncrement redisIdIncrement;

    @Test
    public void test() throws InterruptedException {
        shopService.saveShopToRedis(1L, 10L);
    }

    @Test
    public void testMain() {
        LocalDateTime now = LocalDateTime.now();
        System.out.println(now.toEpochSecond(ZoneOffset.UTC));
    }

    @Test
    public void testRedisIdIncrement() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdIncrement.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };

        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            task.run();
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("the time is " + (end - start));

    }

}