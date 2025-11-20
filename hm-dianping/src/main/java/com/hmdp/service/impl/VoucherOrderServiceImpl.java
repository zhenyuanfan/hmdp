package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.Ilock;
import com.hmdp.utils.IlockImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
    @Override

    public Result killOrder(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //3.判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            //4.不充足，返回错误信息
            return Result.fail("库存不足1");
        }
        //5.扣减库存
        Long id = UserHolder.getUser().getId();
//        synchronized (id.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        RLock lock = redissonClient.getLock("order" + id);
        boolean tryLock = lock.tryLock();
        if (!tryLock){
            return Result.fail("一人只能下一单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
//        IlockImpl ilock1 = new IlockImpl(stringRedisTemplate, "order" + id);
//        boolean tryLock = ilock1.tryLock(10);
//        if (!tryLock){
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            ilock1.unLock();
//        }

    }
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
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
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