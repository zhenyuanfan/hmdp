package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result typeList() {
        //1.从redis中查询列表
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);
        //2.存在，直接返回
        if (shopTypeJson != null){
            return Result.ok( JSONUtil.toList(JSONUtil.parseArray(shopTypeJson), ShopType.class));
        }
        //3.不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4.数据库不存在，报错
        if (typeList == null) {
            return Result.fail("店铺类型不存在");
        }
        //4.存在，保存到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE, JSONUtil.toJsonStr(typeList),CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        //5.返回
        return Result.ok(typeList);
    }
}
