package com.simlect.constants;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqPersistenceConfig {

    @Bean
    public RabbitTemplateCustomizer rabbitTemplatePersistenceCustomizer() {
        return (RabbitTemplate template) -> {
            template.setMandatory(true);
            template.addBeforePublishPostProcessors(message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
        };
    }
}
