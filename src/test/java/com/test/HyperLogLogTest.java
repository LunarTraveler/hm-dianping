package com.test;

import com.hmdp.HmDianPingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(classes = HmDianPingApplication.class)
public class HyperLogLogTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * UV统计是指独立访问此网站的用户数（一个用户只算一次，访问可以是这个网站的任意内容）
     * PV统计是指访问的每一个页面的全部浏览量（只要点击了就是一次访问）
     */
    // 百万数据测试
    @Test
    public void testHyperLogLogInUV() {
        String[] values = new String[1000];
        for (int i = 0, j = 0 ; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hyperloglog", values);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hyperloglog");
        System.out.println(count); // 997593
    }

}
