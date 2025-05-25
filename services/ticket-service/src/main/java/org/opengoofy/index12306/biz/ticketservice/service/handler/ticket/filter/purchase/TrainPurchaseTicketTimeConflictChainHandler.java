package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.userservice.dao.mapper.PassengerMapper;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.framework.starter.common.toolkit.BitmapUtil.buildRedisKey;
import static org.opengoofy.index12306.framework.starter.common.toolkit.BitmapUtil.getTenMinuteIndex;

/**
 * 购票流程过滤器之验证车票时间是否冲突

 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketTimeConflictChainHandler implements TrainPurchaseTicketChainFilter<PurchaseTicketReqDTO> {

    private final TrainMapper trainMapper;
    private final DistributedCache distributedCache;
    private final UserRemoteService userRemoteService;
    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {

        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + requestParam.getTrainId(),
                TrainDO.class,
                () -> trainMapper.selectById(requestParam.getTrainId()),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        if (Objects.isNull(trainDO)) {
            throw new ClientException("请检查车次是否存在");
        }
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        for(PurchaseTicketPassengerDetailDTO passengerDetail : requestParam.getPassengers()){
            String passengerId = passengerDetail.getPassengerId();
            String key = buildRedisKey(userRemoteService.getCardIdById(passengerId).getData(), trainDO.getDepartureTime());
            int startIndex = getTenMinuteIndex(trainDO.getDepartureTime());
            int endIndex = getTenMinuteIndex(trainDO.getArrivalTime());
            for (int i = startIndex; i < endIndex; i++) {
                Boolean isOccupied = stringRedisTemplate.opsForValue().getBit(key, i);
                if (isOccupied != null && isOccupied) {
                    throw new ClientException("订单存在时间冲突，请重新选择");
                }
            }
        }


    }

    @Override
    public int getOrder() {
        return 30;
    }
}
