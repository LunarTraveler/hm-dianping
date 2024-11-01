package com.test;

import cn.hutool.core.lang.UUID;
import com.hmdp.HmDianPingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@SpringBootTest(classes = HmDianPingApplication.class)
public class LoginTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void GenerateUser() {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("nickName", "******");
        userMap.put("icon", null);

        List<String> lines = new ArrayList<>();

        for (int i = 4; i < 1004; i++) {
            String token = UUID.randomUUID().toString(true);
            String tokenKey = LOGIN_USER_KEY + token;
            lines.add(token);
            userMap.put("id", String.valueOf(i));
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            // 这里应该设置为秒的，但是为了不反复测试直接设置为天
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.DAYS);
        }

        Path path = Paths.get("D:/loginUser.txt");
        try {
            Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


}
