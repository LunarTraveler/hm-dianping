package com.hmdp.rabbitmq;

import com.hmdp.config.RabbitMQConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 消息发送者
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Publisher {

    private final RabbitTemplate rabbitTemplate;

    private static final String ROUTINGKEY = "seckill.message";

    /**
     * 发送秒杀信息
     * @param msg
     */
    public void sendSeckillMessage(Object msg){
        log.info("发送消息 " + msg);
        rabbitTemplate.convertAndSend(RabbitMQConfiguration.EXCHANGE, ROUTINGKEY, msg);
    }

}
