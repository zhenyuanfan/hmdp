package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result killOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long execute = stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(),userId.toString());
        //判断结果是否为0
        int result = execute.intValue();
        if (result != 0) {
            //不为0，返回错误信息
            return Result.fail(result == 1 ? "库存不足1" : "不能重复下单");
        }
        //为0，创建订单
        Long orderId = redisIdWorker.nextId("order");
        // TODO 生成订单
        //返回订单号
        return Result.ok(orderId);
    }
//    public Result killOrder(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //3.判断库存是否充足
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            //4.不充足，返回错误信息
//            return Result.fail("库存不足1");
//        }
//        //5.扣减库存
//        Long id = UserHolder.getUser().getId();
////        synchronized (id.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        RLock lock = redissonClient.getLock("order" + id);
//        boolean tryLock = lock.tryLock();
//        if (!tryLock){
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }

    /// /        IlockImpl ilock1 = new IlockImpl(stringRedisTemplate, "order" + id);
    /// /        boolean tryLock = ilock1.tryLock(10);
    /// /        if (!tryLock){
    /// /            return Result.fail("一人只能下一单");
    /// /        }
    /// /        try {
    /// /            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
    /// /            return proxy.createVoucherOrder(voucherId);
    /// /        } finally {
    /// /            ilock1.unLock();
    /// /        }
//
//    }
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //判断用户是否已经购买过
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", UserHolder.getUser().getId()).count();
        if (count > 0) {
            //已经购买过，返回错误信息
            return Result.fail("已经购买过");
        }
        //5.充足，扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        //6.创建订单
        if (!success) {
            return Result.fail("库存不足2");
        }
        VoucherOrder order = new VoucherOrder();
        //6.1.生成订单号
        long order1 = redisIdWorker.nextId("order");
        //6.2.保存订单
        order.setId(order1);
        order.setUserId(UserHolder.getUser().getId());
        order.setVoucherId(voucherId);
        save(order);
        return Result.ok(order1);
    }
}