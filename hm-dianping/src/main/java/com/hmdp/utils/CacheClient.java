package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.select.FunctionItem;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    public StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value),timeout,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long timeout, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( timeout)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), timeout, unit);
    }
    public <T,ID> T queryWithPassThrough(String keyPrefix,ID id,Class<T> type, Function<ID,T> dbFallback, Long timeout, TimeUnit unit) {
        //1.从redis中查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.如果存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        //3.如果不存在，从数据库中查询
        T apply = dbFallback.apply(id);
        //3.1不存在，报错
        if (apply == null) {
            //给redis保存空值，解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //3.2.存在，保存到redis
        this.set(key, apply,timeout, unit);
        //4返回
        return  apply;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <T,ID> T queryWithLogicalExpire(String keyPrefix,ID id,Class<T> type, Function<ID,T> dbFallback,Long timeout, TimeUnit unit) {
        //1.从redis中查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.如果不存在，直接返回空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3.如果存在，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        T t = JSONUtil.toBean(data, type);
        //判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期返回信息
            return t;
        }
        //过期，尝试获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        if (tryLock(lock)) {
            //获取成功，开启线程从数据库查询
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //写入redis，设置逻辑过期时间
                    this.setWithLogicalExpire(key, t, timeout, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放互斥锁
                    unLock(lock);
                }
            });
        }
        //获取失败，返回信息
        return  t;
    }
    //尝试获取互斥锁
    private boolean tryLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
