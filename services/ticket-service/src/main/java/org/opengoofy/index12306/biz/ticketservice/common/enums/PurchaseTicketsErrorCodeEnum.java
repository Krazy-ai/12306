package org.opengoofy.index12306.biz.ticketservice.common.enums;

import lombok.AllArgsConstructor;
import org.opengoofy.index12306.framework.starter.convention.errorcode.IErrorCode;

@AllArgsConstructor
public enum PurchaseTicketsErrorCodeEnum implements IErrorCode {
    INSUFFICIENT_TRAIN_TICKETS("B000001", "列车余票不足");

    /**
     * 错误码
     */
    private final String code;

    /**
     * 错误提示消息
     */
    private final String message;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
