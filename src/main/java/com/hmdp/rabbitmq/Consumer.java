package com.hmdp.rabbitmq;

import com.hmdp.config.RabbitMQConfiguration;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Component
@Slf4j
@RequiredArgsConstructor
public class Consumer {

    private final VoucherOrderMapper voucherOrderMapper;

    private final SeckillVoucherMapper seckillVoucherMapper;

    /**
     * 接收秒杀信息并下单
     * 保证消息队列里面的消息幂等性（提前lua脚本中判断过了）
     * @param voucherOrder
     */
    @RabbitListener(queues = RabbitMQConfiguration.QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void receiveSeckillMessage(VoucherOrder voucherOrder) {
        log.info("接收到消息: " + voucherOrder);

        // 这里能够确保是一条一条的执行，且在拥有多个消费者时，也能并发的处理消息，并且不用太注意重复消费的问题
        // 这里面的每一条消息都是可以执行的，但是由于redis的stream队列消息确认机制时没有那么完善的，会造成并发事务比较高的情况
        // 加上一个悲观锁来应对高并发的情况
        seckillVoucherMapper.decreaseStock(voucherOrder);
        voucherOrderMapper.insert(voucherOrder);
    }

}
