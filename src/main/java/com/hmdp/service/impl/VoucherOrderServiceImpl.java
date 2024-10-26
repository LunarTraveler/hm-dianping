package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdIncrement;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    // 感觉最好是是在service层次引入mapper这样层次分工明确
    private final SeckillVoucherMapper seckillVoucherMapper;

    private final VoucherOrderMapper voucherOrderMapper;

    private final RedisIdIncrement redisIdIncrement;

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    // 加载脚本
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 用于在生产者-消费者问题中进行线程之间的数据交换。
    // BlockingQueue 的主要特点是在队列为空时取元素或者队列为满时添加元素的操作会阻塞。
    private BlockingQueue<VoucherOrder> voucherOrderBlockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
    // 一个异步的单线程处理任务线程
    private static final ExecutorService SECKILL_VOUCHER_EXECUTOR = Executors.newSingleThreadExecutor();
    //
    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SECKILL_VOUCHER_EXECUTOR.submit(new VoucherOrderTask());
        // SECKILL_VOUCHER_EXECUTOR.submit(() -> {System.out.println("clnsc");});
    }

    private class VoucherOrderTask implements Runnable {
        // 线程任务
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取订单
                    VoucherOrder voucherOrder = voucherOrderBlockingQueue.take();
                    // 处理订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("从阻塞队列获取订单有异常 {}",e.getMessage());
                }
            }
        }
    }

    // 这个方法是从主线程中另派的一个异步线程（线程里的保存变量是不可用的）
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 获取锁对象
        // SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);

        // 这里默认的是重试时间为不重试（-1） 存活时间为30秒
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return ;
        }

        try {
            // 正常下单就行了
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 判断有没有购买资格通过lua脚本，全部在redis中完成
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), String.valueOf(voucherId), String.valueOf(userId));
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "这个用户已经购买过了");
        }
        Long orderId = redisIdIncrement.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .voucherId(voucherId)
                .userId(userId)
                .id(orderId)
                .build();
        // TODO 可以通过一些消息队列来异步处理消息(jdk本身的阻塞队列， redis实现的队列， rebitemq队列)
        voucherOrderBlockingQueue.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    // 这里的操作事项数据库中进行操作（其实就只要保存就行了， 其他的判断的都在redis中资格判断过了）
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        // 这里只需要唯一标识就行了
        queryWrapper.eq(VoucherOrder::getId, voucherOrder.getId());
        long count = voucherOrderMapper.selectCount(queryWrapper);

        if (count > 0) {
            log.error("用户已经购买过了一次");
            return;
        }

        int result = seckillVoucherMapper.decreaseStock(voucherOrder);
        if (result == 0) {
            log.info("库存不足");
            return ;
        }
        voucherOrderMapper.insert(voucherOrder);
    }

//    /**
//     * 抢购优惠券(秒杀下单)
//     * @param voucherId
//     * @return
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);
//
//        // 对于异常情况的处理
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("活动尚未开始");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("活动已经结束");
//        }
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
////        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(seckillVoucher);
////        }
//
//        Long userId = UserHolder.getUser().getId();
//        // 获取锁对象
//        // SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
//
//        // 这里默认的是重试时间为不重试（-1） 存活时间为30秒
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            // 正常下单就行了
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(seckillVoucher);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    /**
     * 虽然加在方法上是可行的，但是粒度太大了，这样的话每一个用户都要排队了
     * 但是我的本意是一个用户一单，也就是一个用户的多次访问要排队，所以要锁唯一标识用户的
     * 这个锁的范围是要大于事务的范围，要在事务提交之后才释放锁，要不然又会有多个线程在锁没提交就进入
     * @param seckillVoucher
     * @return
     */
    @Transactional
    public Result createVoucherOrder(SeckillVoucher seckillVoucher) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, seckillVoucher.getVoucherId());
        long count = voucherOrderMapper.selectCount(queryWrapper);

        if (count > 0) {
            return Result.fail("用户已经购买过了一次");
        }

        // 解决超卖的问题（乐观锁， 悲观锁）
        // 1 悲观锁（其实是数据库在更新操作时，是会对修改的这几行做一个排他锁）
        // 这里库存的减少和数据库的更新最好是原子操作，这样是能够避免脏读和幻读
        // 扣减库存
        int result = seckillVoucherMapper.updateWithDecreaseStock(seckillVoucher);
        if (result == 0) {
            return Result.fail("库存不足");
        }

        // 2 乐观锁（其实是不符合条件的认为是其他线程在修改，符合条件的一定是唯一在修改的）
//        int result = seckillVoucherMapper.updateWithDecreaseStockOptimistic(seckillVoucher);
//        if (result == 0) {
//            return Result.fail("库存不足");
//        }

        // 生成订单(需要orderId, userId, voucherId)
        Long orderId = redisIdIncrement.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(seckillVoucher.getVoucherId())
                .build();
        voucherOrderMapper.insert(voucherOrder);
        return Result.ok(orderId);

    }


}
