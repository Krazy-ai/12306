package org.opengoofy.index12306.biz.ticketservice.mq.consumer;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.enums.PurchaseTicketsErrorCodeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.OrderTrackingDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.OrderTrackingMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.PurchaseTicketsEvent;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Component;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;

import java.util.Objects;

/**
 * 用户异步购票消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketRocketMQConstant.PURCHASE_TICKET_ASYNC_TOPIC_KEY,
        consumerGroup = TicketRocketMQConstant.PURCHASE_TICKET_ASYNC_CG_KEY
)
public class PurchaseTicketsConsumer implements RocketMQListener<MessageWrapper<PurchaseTicketsEvent>> {

    private final TicketService ticketService;
    private final OrderTrackingMapper orderTrackingMapper;

    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:purchase_tickets_v3:",
            key = "#messageWrapper.getKeys()",
            type = IdempotentTypeEnum.SPEL,
            scene = IdempotentSceneEnum.MQ,
            keyTimeout = 7200L
    )
    @Override
    public void onMessage(MessageWrapper<PurchaseTicketsEvent> messageWrapper) {
        log.info("[用户异步购票] 开始消费：{}", JSON.toJSONString(messageWrapper));

        // 获取用户购票参数
        PurchaseTicketsEvent purchaseTicketsEvent = messageWrapper.getMessage();
        PurchaseTicketReqDTO originalRequestParam = purchaseTicketsEvent.getOriginalRequestParam();
        String orderTrackingId = purchaseTicketsEvent.getOrderTrackingId();

        // 发起用户创建订单
        TicketPurchaseRespDTO ticketPurchaseRespDTO = null;
        boolean insufficientTrainTicketsFlag = false;
        try {
            UserContext.setUser(purchaseTicketsEvent.getUserInfo());
            //TODO 分布式锁
            ticketPurchaseRespDTO = ticketService.executePurchaseTickets(originalRequestParam);
        } catch (ServiceException se) {
            // 错误可能有两种，其中一个是列车无余票，另外可能是发起购票失败，比如订单服务宕机、Redis 宕机等极端情况
            insufficientTrainTicketsFlag = Objects.equals(se.getErrorCode(), PurchaseTicketsErrorCodeEnum.INSUFFICIENT_TRAIN_TICKETS.code());
        } finally {
            UserContext.removeUser();
        }

        // 根据用户是否创建订单成功构建订单追踪实体
        OrderTrackingDO orderTrackingDO = OrderTrackingDO.builder()
                .id(Long.parseLong(orderTrackingId))
                .orderSn(ticketPurchaseRespDTO != null ? ticketPurchaseRespDTO.getOrderSn() : null)
                // 状态 0：请求下单成功 1：列车与余票不足 2：购票请求失败
                .status(ticketPurchaseRespDTO != null ? 0 : insufficientTrainTicketsFlag ? 1 : 2)
                .build();

        // 新增订单追踪记录，方便为购票 v3 接口异步下单后的结果提供查询能力
        orderTrackingMapper.insert(orderTrackingDO);
    }
}
