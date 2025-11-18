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

/**
 * 缓存客户端工具类
 * 提供了多种缓存策略，包括基础缓存、逻辑过期缓存等
 */
@Component
@Slf4j
public class CacheClient {
    public StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    
    /**
     * 设置缓存数据
     * @param key 缓存键
     * @param value 缓存值
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, Long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value),timeout,unit);
    }
    
    /**
     * 设置带有逻辑过期时间的缓存数据
     * @param key 缓存键
     * @param value 缓存值
     * @param timeout 逻辑过期时间
     * @param unit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long timeout, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds( timeout)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), timeout, unit);
    }
    
    /**
     * 使用缓存穿透解决方案查询数据
     * 当缓存中没有数据时，会查询数据库，如果数据库也没有则在缓存中存储空值防止穿透
     * @param keyPrefix 缓存键前缀
     * @param id 查询ID
     * @param type 返回值类型
     * @param dbFallback 数据库查询函数
     * @param timeout 缓存过期时间
     * @param unit 时间单位
     * @param <T> 返回值类型
     * @param <ID> ID类型
     * @return 查询结果
     */
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

    /**
     * 缓存重建线程池
     * 用于异步重建过期缓存
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    
    /**
     * 使用逻辑过期方案查询数据
     * 当缓存不存在时直接查询数据库并存入缓存
     * 当缓存存在但已过期时，返回旧数据的同时异步重建缓存
     * @param keyPrefix 缓存键前缀
     * @param id 查询ID
     * @param type 返回值类型
     * @param dbFallback 数据库查询函数
     * @param timeout 逻辑过期时间
     * @param unit 时间单位
     * @param <T> 返回值类型
     * @param <ID> ID类型
     * @return 查询结果
     */
    public <T,ID> T queryWithLogicalExpire(String keyPrefix,ID id,Class<T> type, Function<ID,T> dbFallback,Long timeout, TimeUnit unit) {
        //1.从redis中查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.如果不存在，从数据库查询并加入缓存
        if (StrUtil.isBlank(json)) {
            /**
             * 这里应该是要返回null的，因为逻辑缓存一般都是能查到
             * 但是这里返回null，会导致缓存穿透，所以这里返回null
             */
            T newData = dbFallback.apply(id);
            if (newData == null) {
                return null;
            }
            // 加入缓存，设置逻辑过期时间
            this.setWithLogicalExpire(key, newData, timeout, unit);
            return newData;
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
                    // 从数据库查询最新数据
                    T newData = dbFallback.apply(id);
                    // 写入redis，设置逻辑过期时间
                    this.setWithLogicalExpire(key, newData, timeout, unit);
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
    
    /**
     * 尝试获取互斥锁
     * @param key 锁键名
     * @return 是否获取成功
     */
    //尝试获取互斥锁
    private boolean tryLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    /**
     * 释放互斥锁
     * @param key 锁键名
     */
    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}