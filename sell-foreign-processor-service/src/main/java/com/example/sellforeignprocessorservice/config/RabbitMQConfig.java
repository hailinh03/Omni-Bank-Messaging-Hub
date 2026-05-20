package com.example.sellforeignprocessorservice.config;

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
import org.springframework.amqp.core.AcknowledgeMode;

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
    public Queue processorQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.QUEUE_PROCESSOR)
                .build();
    }

    @Bean
    public Binding bindingProcessor(@Qualifier("processorQueue")Queue processorQueue, TopicExchange topicExchange) {
        return BindingBuilder
                .bind(processorQueue)
                .to(topicExchange)
                .with(RabbitMQConstants.ROUTING_PROCESSOR);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(RabbitMQConstants.QUEUE_NOTIFICATION).build();
    }

    @Bean
    public Binding bindingNotification(@Qualifier("notificationQueue") Queue notificationQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(notificationQueue).to(topicExchange).with(RabbitMQConstants.ROUTING_NOTIFICATION);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

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
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return springRabbitTracing.decorateSimpleRabbitListenerContainerFactory(factory);
    }
}
