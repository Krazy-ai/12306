package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTrackingRespDTO {

    /**
     * 订单号
     */
    private String orderSn;

    /**
     * 状态 0：请求下单成功 1：列车与余票不足 2：购票请求失败
     */
    private Integer status;
}