package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfiguration {

    public static final String QUEUE = "seckill.queue";
    public static final String EXCHANGE = "seckill.exchange";
    public static final String ROUTING_KEY = "seckill.#";

    @Bean
    public Queue seckillQueue() {
        return QueueBuilder
                .durable(QUEUE)
                // .lazy()
                .build();
    }

    @Bean
    public TopicExchange seckillExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue()).to(seckillExchange()).with(ROUTING_KEY);
    }

    // 消息转换器使用json 如果是jdk(体积大 可读性差 有安全隐患)
    @Bean
    public MessageConverter jackson2MessageConverter() {
        Jackson2JsonMessageConverter jackson2JsonMessageConverter = new Jackson2JsonMessageConverter();
        // 对于每一条消息都配备上一个唯一的id，用于识别不同的消息
        jackson2JsonMessageConverter.setCreateMessageIds(true);
        return jackson2JsonMessageConverter;
    }

}
