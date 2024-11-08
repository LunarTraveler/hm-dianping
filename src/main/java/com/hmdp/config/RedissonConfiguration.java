package com.hmdp.config;

import com.hmdp.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RedissonConfiguration {

    private final RedisProperties redisProperties;

    @Bean
    public RedissonClient redissonClient() {
        // 配置是单体项目还是集群项目 以及相关的密码和主机地址
        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisProperties.getAddress())
                .setPassword(redisProperties.getPassword());
        return Redisson.create(config);
    }

}
