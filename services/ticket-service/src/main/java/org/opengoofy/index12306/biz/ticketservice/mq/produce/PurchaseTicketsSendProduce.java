package org.opengoofy.index12306.biz.ticketservice.mq.produce;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.PurchaseTicketsEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 用户异步购票生产者
 */
@Slf4j
@Component
public class PurchaseTicketsSendProduce extends AbstractCommonSendProduceTemplate<PurchaseTicketsEvent>{

    private final ConfigurableEnvironment environment;

    public PurchaseTicketsSendProduce(@Autowired RocketMQTemplate rocketMQTemplate, @Autowired ConfigurableEnvironment environment) {
        super(rocketMQTemplate);
        this.environment = environment;
    }
    @Override
    protected BaseSendExtendDTO buildBaseSendExtendParam(PurchaseTicketsEvent messageSendEvent) {
        return BaseSendExtendDTO.builder()
                .eventName("用户异步购票")
                .keys(messageSendEvent.getOrderTrackingId())
                .topic(environment.resolvePlaceholders(TicketRocketMQConstant.PURCHASE_TICKET_ASYNC_TOPIC_KEY))
                .tag(environment.resolvePlaceholders(TicketRocketMQConstant.PURCHASE_TICKET_ASYNC_TAG_KEY))
                .sentTimeout(2000L)
                .build();
    }

    @Override
    protected Message<?> buildMessage(PurchaseTicketsEvent messageSendEvent, BaseSendExtendDTO requestParam) {
        String keys = StrUtil.isEmpty(requestParam.getKeys()) ? UUID.randomUUID().toString() : requestParam.getKeys();
        return MessageBuilder
                .withPayload(new MessageWrapper(requestParam.getKeys(), messageSendEvent))
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .setHeader(MessageConst.PROPERTY_TAGS, requestParam.getTag())
                .build();
    }
}
