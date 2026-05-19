package com.example.sellforeignprocessorservice.listener;

import com.example.common.constant.RabbitMQConstants;
import com.example.common.dto.message.SellForeignMessage;
import com.example.sellforeignprocessorservice.service.SellForeignProcessorService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SellForeignMessageListener {

    private final SellForeignProcessorService processorService;

    @RabbitListener(queues = RabbitMQConstants.QUEUE_PROCESSOR)
    public void onMessage(SellForeignMessage message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("[MESSAGE_LISTENER] Received message from queue - idempotencyKey: {}, customerId: {}, amount: {}",
            message.getIdempotencyKey(), message.getOwnerId(), message.getAmount());

        try {
            processorService.processTransaction(message);
            channel.basicAck(deliveryTag, false);
            log.info("[MESSAGE_LISTENER] Message processed and acknowledged - idempotencyKey: {}", message.getIdempotencyKey());

        } catch (Exception e) {
            log.error("[MESSAGE_LISTENER] Failed to process message - idempotencyKey: {}, error: {}",
                message.getIdempotencyKey(), e.getMessage(), e);
            try {
                // requeue = false → send to DLQ if configured, otherwise discard
                channel.basicNack(deliveryTag, false, false);
                log.warn("[MESSAGE_LISTENER] Message sent to DLQ - idempotencyKey: {}", message.getIdempotencyKey());
            } catch (Exception nackEx) {
                log.error("[MESSAGE_LISTENER] Failed to nack message", nackEx);
            }
        }
    }
}
