package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson分布式锁的配置
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置，设置地址和密码
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("123456");
        //创建RedissonClient对象
        return Redisson.create(config);
    }
}
