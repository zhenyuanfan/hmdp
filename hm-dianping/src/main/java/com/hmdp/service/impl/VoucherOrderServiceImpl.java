package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
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

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    public class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder order = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(order);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder order) {
        Long id = order.getVoucherId();
        RLock lock = redissonClient.getLock("order" + id);
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            log.debug("不能重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    @Override
    public Result killOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long execute = stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        //判断结果是否为0
        int result = execute.intValue();
        if (result != 0) {
            //不为0，返回错误信息
            return Result.fail(result == 1 ? "库存不足1" : "不能重复下单");
        }
        //为0，创建订单，放入阻塞队列中
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(UserHolder.getUser().getId());
        order.setVoucherId(voucherId);
        orderTasks.add(order);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
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
    public void createVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        //判断用户是否已经购买过
        Integer count = query().eq("voucher_id", order.getVoucherId()).eq("user_id", userId).count();
        if (count > 0) {
            //已经购买过，返回错误信息
            log.debug("用户已经购买过");
            return;
        }
        //5.充足，扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();
        //6.创建订单
        if (!success) {
            log.debug("库存不足");
            return;
        }
        save(order);

    }
}