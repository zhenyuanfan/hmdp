package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class IlockImpl implements Ilock {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private final String name;

    public IlockImpl(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private final String KEY = "lock:";
    private final String UUID_PREFIX = UUID.randomUUID().toString(true) + "-";
    String s1 = Thread.currentThread().getId() + UUID_PREFIX;

    @Override
    public boolean tryLock(long timeoutSec) {
        String id = s1;
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY + name, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }

    @Override
    public void unLock() {

        String id = s1;
        String s = stringRedisTemplate.opsForValue().get(KEY + name);
        if (id.equals(s)) {
            stringRedisTemplate.delete(KEY + name);
        }

    }
}
