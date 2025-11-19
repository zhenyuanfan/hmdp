package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class IlockImpl implements Ilock {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private final String name;
    private final String KEY = "lock:";

    public IlockImpl(StringRedisTemplate stringRedisTemplate,String name){
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    
    @Override
    public boolean tryLock(long timeoutSec) {
        String id = Thread.currentThread().getId() + "";
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY + name, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.delete(KEY + name);
    }
}
