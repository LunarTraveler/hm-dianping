package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.rabbitmq.Publisher;
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
import com.google.common.util.concurrent.RateLimiter;

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

    private final Publisher publisher;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    public static final DefaultRedisScript<Long> SECKILL_WITH_RABBITMQ_SCRIPT;

    // 加载seckill.lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 加载seckillWithRabbitmq.lua脚本
    static {
        SECKILL_WITH_RABBITMQ_SCRIPT = new DefaultRedisScript<>();
        SECKILL_WITH_RABBITMQ_SCRIPT.setLocation(new ClassPathResource("seckillWithRabbitmq.lua"));
        SECKILL_WITH_RABBITMQ_SCRIPT.setResultType(Long.class);
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
    // @Override
    public Result seckillVoucher2(Long voucherId) {
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

    /**
     * 使用了rabbitmq消息队列来实现
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 使用令牌桶进行限流
        RateLimiter rateLimiter = RateLimiter.create(50.0, 2, TimeUnit.SECONDS);
        if (!rateLimiter.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
            return Result.fail("目前网络正忙，请重试");
        }

        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdIncrement.nextId("order");

        // 执行脚本进行资格的判断
        Long result = stringRedisTemplate.execute(SECKILL_WITH_RABBITMQ_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不允许重复下单");
        }

        // 封装成订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);

        // 加入rabbitmq消息队列(网络传输的数据格式是json)
        publisher.sendSeckillMessage(voucherOrder);

        return Result.ok(orderId);
    }

}
