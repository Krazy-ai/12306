package org.opengoofy.index12306.biz.ticketservice.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;

/**
 * 用户异步购票事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseTicketsEvent {

    private String orderTrackingId;

    private PurchaseTicketReqDTO originalRequestParam;

    private UserInfoDTO userInfo;
}
