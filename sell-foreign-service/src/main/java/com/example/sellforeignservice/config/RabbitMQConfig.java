package com.example.sellforeignservice.config;

import brave.Tracing;
import brave.spring.rabbit.SpringRabbitTracing;
import com.example.common.constant.RabbitMQConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {


    @Bean
    public TopicExchange topicExchange() {
        return ExchangeBuilder
                .topicExchange(RabbitMQConstants.TOPIC_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue exchangeProcessQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.QUEUE_PROCESSOR)
                .build();
    }

    @Bean
    public Binding bindingConvert(Queue exchangeProcessQueue,
            TopicExchange topicExchange) {

        return BindingBuilder
                .bind(exchangeProcessQueue)
                .to(topicExchange)
                .with(RabbitMQConstants.ROUTING_PROCESSOR);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

//    @Bean
//    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
//        RabbitTemplate template = new RabbitTemplate(connectionFactory);
//        template.setMessageConverter(messageConverter());
//        return template;
//    }
    @Bean
    public SpringRabbitTracing springRabbitTracing(Tracing tracing) {
        return SpringRabbitTracing.newBuilder(tracing)
                .remoteServiceName("rabbitmq")
                .build();
    }
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         SpringRabbitTracing springRabbitTracing) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return springRabbitTracing.decorateRabbitTemplate(template);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SpringRabbitTracing springRabbitTracing) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        return springRabbitTracing.decorateSimpleRabbitListenerContainerFactory(factory);
    }
}
