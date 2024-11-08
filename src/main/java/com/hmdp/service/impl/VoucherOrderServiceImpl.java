package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;

import static com.hmdp.utils.RedisConstants.LOCK_MSG_KEY;
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

    private final SeckillVoucherMapper seckillVoucherMapper;

    private final RedissonClient redissonClient;

    private final RedisIdIncrement redisIdIncrement;

    private final VoucherOrderMapper voucherOrderMapper;

    private final PlatformTransactionManager transactionManager;

    private final StringRedisTemplate stringRedisTemplate;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    // 加载lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 一个单线程执行下单任务
    private static final ExecutorService SEC_KILL_EXECUTOR = Executors.newSingleThreadExecutor();

    private final String messageQueue = "stream.orders";

    @PostConstruct
    private void init() {
        SEC_KILL_EXECUTOR.submit(new VoucherOrderTask());
    }

    private class VoucherOrderTask implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    // 获取消息队列里的消息订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(messageQueue, ReadOffset.lastConsumed())
                    );

                    // 检查是否有消息，如果没有的话，那么就阻塞2秒防止cpu压力太大
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    // 解析出具体的信息并完成下单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(list.get(0).getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder, list.get(0).getId());

                } catch (Exception e) {
                    log.error("从redis队列获取订单有异常 {}",e.getMessage());
                    // 如果有没有确认的消息，那么就在等待队列里面确认，做一个保障
                    handlePendingList();
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder, RecordId messageId) {
        // 单服务器的话甚至不需要考虑加锁的问题
        createVoucherOrder(voucherOrder);

        // 多服务器的话就要确保一条消息只能执行一次(其实这里是对于这一条消息加锁，只要是唯一标识就行)
//        Long orderId = voucherOrder.getId();
//        RLock lock = redissonClient.getLock(LOCK_MSG_KEY + orderId);
//
//        try {
//            if (lock.tryLock()) {
//                // 在分布式的环境中虽然增加多台机器的并发处理，但是也增加了相应的判断
//                Long count = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
//                        .eq(VoucherOrder::getUserId, voucherOrder.getUserId())
//                        .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId()));
//                if (count > 0) {
//                    log.info("订单已处理，忽略重复处理");
//                    return;  // 处理完跳出，不需要确认消息
//                }
//
//                createVoucherOrder(voucherOrder);
//                stringRedisTemplate.opsForStream().acknowledge(messageQueue, "g1", messageId);
//            }
//        } catch (Exception e) {
//            log.error("处理订单时出现异常: {}", e.getMessage(), e);
//        } finally {
//            if (lock.isHeldByCurrentThread()) {
//                lock.unlock();  // 只有当前线程持有锁时才能解锁
//            }
//        }

    }

    private void handlePendingList() {
        while(true) {
            try {
                // 获取等待队列里的消息订单
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(messageQueue, ReadOffset.lastConsumed())
                );

                // 检查是否有消息，如果没有的话直接回退到消息队列里面去
                if (list == null || list.isEmpty()) {
                    break;
                }

                // 解析出具体的信息并完成异步下单
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(list.get(0).getValue(), new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder, list.get(0).getId());

            } catch (Exception e) {
                log.error("从等待队列获取订单有异常 {}", e.getMessage());
                // 为了不立即就访问
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ex) {
                    log.error(Thread.currentThread().getId() + " 线程中断发生异常");
                }
            }
        }

    }

    // 保证消息队列里面的消息幂等性（要么提前判断在lua脚本中判断过了，要么之后判断）
    private void createVoucherOrder(VoucherOrder voucherOrder){
        TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());

        try {
            // 这里面的每一条消息都是可以执行的，但是由于redis的stream队列消息确认机制时没有那么完善的，会造成并发事务比较高的情况
            // 加上一个悲观锁来应对高并发的情况
            seckillVoucherMapper.decreaseStock(voucherOrder);
            voucherOrderMapper.insert(voucherOrder);

            // 提交事务
            transactionManager.commit(transaction);
        } catch (Exception e) {
            // 回滚事务
            transactionManager.rollback(transaction);
        }
    }

    /**
     * 性能优化 这里是使用redis缓存 + lua脚本 + redis的stream消息队列实现
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 这里就不判断活动是否在规定时间了，直接能访问就是开启之后了
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdIncrement.nextId("order");

        // 分布式环境的一人一单资格判断和库存的判断(使用redis缓存 + lua脚本)
        // 这里的意思是库存充足并且该用户也没有购买过 也就是代表之后的过程是一定会下单的，又要注意有多个线程的并发的访问，所以要加分布式锁
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString());
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不允许重复下单");
        }

        return Result.ok(orderId);
    }

    /*
    使用的jdk本身的阻塞队列实现的
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
    */

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

//    @Transactional
//    public Result createVoucherOrder(SeckillVoucher seckillVoucher) {
//        Long userId = UserHolder.getUser().getId();
//        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
//        queryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, seckillVoucher.getVoucherId());
//        long count = voucherOrderMapper.selectCount(queryWrapper);
//
//        if (count > 0) {
//            return Result.fail("用户已经购买过了一次");
//        }
//
//        // 解决超卖的问题（乐观锁， 悲观锁）
//        // 1 悲观锁（其实是数据库在更新操作时，是会对修改的这几行做一个排他锁）
//        // 这里库存的减少和数据库的更新最好是原子操作，这样是能够避免脏读和幻读
//        // 扣减库存
//        int result = seckillVoucherMapper.updateWithDecreaseStock(seckillVoucher);
//        if (result == 0) {
//            return Result.fail("库存不足");
//        }
//
//        // 2 乐观锁（其实是不符合条件的认为是其他线程在修改，符合条件的一定是唯一在修改的）
////        int result = seckillVoucherMapper.updateWithDecreaseStockOptimistic(seckillVoucher);
////        if (result == 0) {
////            return Result.fail("库存不足");
////        }
//
//        // 生成订单(需要orderId, userId, voucherId)
//        Long orderId = redisIdIncrement.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(orderId);
//
//        voucherOrderMapper.insert(voucherOrder);
//        return Result.ok(orderId);
//
//    }


}
