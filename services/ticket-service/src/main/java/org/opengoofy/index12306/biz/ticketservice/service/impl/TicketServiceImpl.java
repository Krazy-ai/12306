/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.ticketservice.common.enums.RefundTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SourceEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.*;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.*;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatClassDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.mq.event.PurchaseTicketsEvent;
import org.opengoofy.index12306.biz.ticketservice.mq.produce.PurchaseTicketsSendProduce;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.OrderTrackingRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.TrainSeatTypeSelector;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.TimeStringComparator;
import org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.common.toolkit.BitmapUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.distributedid.toolkit.SnowflakeIdUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.*;
import static org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil.convertDateToLocalTime;

/**
 * 车票接口实现

 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, TicketDO> implements TicketService, CommandLineRunner {

    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final DistributedCache distributedCache;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final PayRemoteService payRemoteService;
    private final StationMapper stationMapper;
    private final OrderTrackingMapper orderTrackingMapper;
    private final SeatService seatService;
    private final TrainStationService trainStationService;
    private final TrainSeatTypeSelector trainSeatTypeSelector;
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final AbstractChainContext<TicketPageQueryReqDTO> ticketPageQueryAbstractChainContext;
    private final AbstractChainContext<PurchaseTicketReqDTO> purchaseTicketAbstractChainContext;
    private final AbstractChainContext<RefundTicketReqDTO> refundReqDTOAbstractChainContext;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private final PurchaseTicketsSendProduce purchaseTicketsSendProduce;
    private TicketService ticketService;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;
    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;

    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV1(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 列车查询逻辑较为复杂，详细解析文章查看 https://nageoffer.com/12306/question
        // v1 版本存在严重的性能深渊问题，v2 版本完美的解决了该问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        long count = stationDetails.stream().filter(Objects::isNull).count();
        // 如果城市数据为空，代表缓存中没有这个值，需要从数据库加载
        if (count > 0) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION_MAPPING);
            lock.lock();
            try {
                stationDetails = stringRedisTemplate.opsForHash()
                        .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
                count = stationDetails.stream().filter(Objects::isNull).count();
                if (count > 0) {
                    List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
                    Map<String, String> regionTrainStationMap = new HashMap<>();
                    //存的是RegionName
                    stationDOList.forEach(each -> regionTrainStationMap.put(each.getCode(), each.getRegionName()));
                    // 通过 putAll 批量保存方式存入 Redis，避免多次 put 网络 IO 消耗
                    stringRedisTemplate.opsForHash().putAll(REGION_TRAIN_STATION_MAPPING, regionTrainStationMap);
                    stationDetails = new ArrayList<>();
                    stationDetails.add(regionTrainStationMap.get(requestParam.getFromStation()));
                    stationDetails.add(regionTrainStationMap.get(requestParam.getToStation()));
                }
            } finally {
                lock.unlock();
            }
        }

        List<TicketListDTO> seatResults = new ArrayList<>();
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        if (MapUtil.isEmpty(regionTrainStationAllMap)) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION);
            lock.lock();
            try {
                regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
                if (MapUtil.isEmpty(regionTrainStationAllMap)) {
                    // 加载数据库列车相关信息，并构建出一趟列车详细记录
                    LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getStartRegion, stationDetails.get(0))
                            .eq(TrainStationRelationDO::getEndRegion, stationDetails.get(1));
                    List<TrainStationRelationDO> trainStationRelationList = trainStationRelationMapper.selectList(queryWrapper);
                    for (TrainStationRelationDO each : trainStationRelationList) {
                        TrainDO trainDO = distributedCache.safeGet(
                                TRAIN_INFO + each.getTrainId(),
                                TrainDO.class,
                                () -> trainMapper.selectById(each.getTrainId()),
                                ADVANCE_TICKET_DAY,
                                TimeUnit.DAYS);
                        TicketListDTO result = new TicketListDTO();
                        result.setTrainId(String.valueOf(trainDO.getId()));
                        result.setTrainNumber(trainDO.getTrainNumber());
                        result.setDepartureTime(convertDateToLocalTime(each.getDepartureTime(), "HH:mm"));
                        result.setArrivalTime(convertDateToLocalTime(each.getArrivalTime(), "HH:mm"));
                        result.setDuration(DateUtil.calculateHourDifference(each.getDepartureTime(), each.getArrivalTime()));
                        result.setDeparture(each.getDeparture());
                        result.setArrival(each.getArrival());
                        result.setDepartureFlag(each.getDepartureFlag());
                        result.setArrivalFlag(each.getArrivalFlag());
                        result.setTrainType(trainDO.getTrainType());
                        result.setTrainBrand(trainDO.getTrainBrand());
                        if (StrUtil.isNotBlank(trainDO.getTrainTag())) {
                            result.setTrainTags(StrUtil.split(trainDO.getTrainTag(), ","));
                        }
                        long betweenDay = cn.hutool.core.date.DateUtil.betweenDay(each.getDepartureTime(), each.getArrivalTime(), /*false 参数表示不包含当天*/false);
                        result.setDaysArrived((int) betweenDay);
                        result.setSaleStatus(new Date().after(trainDO.getSaleTime()) ? 0 : 1);
                        result.setSaleTime(convertDateToLocalTime(trainDO.getSaleTime(), "MM-dd HH:mm"));
                        seatResults.add(result);
                        regionTrainStationAllMap.put(CacheUtil.buildKey(String.valueOf(each.getTrainId()), each.getDeparture(), each.getArrival()), JSON.toJSONString(result));
                    }
                    stringRedisTemplate.opsForHash().putAll(buildRegionTrainStationHashKey, regionTrainStationAllMap);
                }
            } finally {
                lock.unlock();
            }
        }

        //查询出来列车基本信息后，开始对列车按照出发时间进行排序。
        seatResults = CollUtil.isEmpty(seatResults)
                ? regionTrainStationAllMap.values().stream().map(each -> JSON.parseObject(each.toString(), TicketListDTO.class)).toList()
                : seatResults;
        seatResults = seatResults.stream().sorted(new TimeStringComparator()).toList();

        for (TicketListDTO each : seatResults) {
            String trainStationPriceStr = distributedCache.safeGet(
                    String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()),
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationPriceDO> trainStationPriceQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                                .eq(TrainStationPriceDO::getDeparture, each.getDeparture())
                                .eq(TrainStationPriceDO::getArrival, each.getArrival())
                                .eq(TrainStationPriceDO::getTrainId, each.getTrainId());
                        return JSON.toJSONString(trainStationPriceMapper.selectList(trainStationPriceQueryWrapper));
                    },
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS
            );
            List<TrainStationPriceDO> trainStationPriceDOList = JSON.parseArray(trainStationPriceStr, TrainStationPriceDO.class);
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            trainStationPriceDOList.forEach(item -> {
                String seatType = String.valueOf(item.getSeatType());
                String keySuffix = StrUtil.join("_", each.getTrainId(), item.getDeparture(), item.getArrival());
                Object quantityObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_TICKET + keySuffix, seatType);
                int quantity = Optional.ofNullable(quantityObj)
                        // 如果 quantityObj 不为 null，将其转换为字符串
                        .map(Object::toString)
                        // 将字符串转换为整数
                        .map(Integer::parseInt)
                        // 如果 quantityObj 为 null，执行 orElseGet 中的逻辑
                        .orElseGet(() -> {
                            Map<String, String> seatMarginMap = seatMarginCacheLoader.load(String.valueOf(each.getTrainId()), seatType, item.getDeparture(), item.getArrival());
                            return Optional.ofNullable(seatMarginMap.get(String.valueOf(item.getSeatType())))
                                    .map(Integer::parseInt)
                                    .orElse(0);
                        });
                seatClassList.add(new SeatClassDTO(item.getSeatType(), quantity, new BigDecimal(item.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP), false));
            });
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // v2 版本更符合企业级高并发真实场景解决方案，完美解决了 v1 版本性能深渊问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        List<TicketListDTO> seatResults = regionTrainStationAllMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();
        List<String> trainStationPriceKeys = seatResults.stream()
                .map(each -> String.format(cacheRedisPrefix + TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()))
                .toList();
        //执行 Redis 的 管道（pipeline）操作，批量查询 trainStationPriceKeys 中的所有键。
        //connection.stringCommands().get(each.getBytes()) 将每个键转换为字节并查询其对应的值。
        List<Object> trainStationPriceObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            trainStationPriceKeys.forEach(each -> connection.stringCommands().get(each.getBytes()));
            return null;
        });
        List<TrainStationPriceDO> trainStationPriceDOList = new ArrayList<>();
        List<String> trainStationRemainingKeyList = new ArrayList<>();
        for (Object each : trainStationPriceObjs) {
            List<TrainStationPriceDO> trainStationPriceList = JSON.parseArray(each.toString(), TrainStationPriceDO.class);
            trainStationPriceDOList.addAll(trainStationPriceList);
            for (TrainStationPriceDO item : trainStationPriceList) {
                String trainStationRemainingKey = cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", item.getTrainId(), item.getDeparture(), item.getArrival());
                trainStationRemainingKeyList.add(trainStationRemainingKey);
            }
        }
        List<Object> trainStationRemainingObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            for (int i = 0; i < trainStationRemainingKeyList.size(); i++) {
                connection.hashCommands().hGet(trainStationRemainingKeyList.get(i).getBytes(), trainStationPriceDOList.get(i).getSeatType().toString().getBytes());
            }
            return null;
        });
        for (TicketListDTO each : seatResults) {
            List<Integer> seatTypesByCode = VehicleTypeEnum.findSeatTypesByCode(each.getTrainType());
            List<Object> remainingTicket = new ArrayList<>(trainStationRemainingObjs.subList(0, seatTypesByCode.size()));
            List<TrainStationPriceDO> trainStationPriceDOSub = new ArrayList<>(trainStationPriceDOList.subList(0, seatTypesByCode.size()));
            trainStationRemainingObjs.subList(0, seatTypesByCode.size()).clear();
            trainStationPriceDOList.subList(0, seatTypesByCode.size()).clear();
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (int i = 0; i < trainStationPriceDOSub.size(); i++) {
                TrainStationPriceDO trainStationPriceDO = trainStationPriceDOSub.get(i);
                SeatClassDTO seatClassDTO = SeatClassDTO.builder()
                        .type(trainStationPriceDO.getSeatType())
                        .quantity(Integer.parseInt(remainingTicket.get(i).toString()))
                        .price(new BigDecimal(trainStationPriceDO.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP))
                        .candidate(false)
                        .build();
                seatClassList.add(seatClassDTO);
            }
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    @Override
    public TicketPurchaseRespDTO purchaseTicketsV1(PurchaseTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        // v1 版本购票存在 4 个较为严重的问题，v2 版本相比较 v1 版本更具有业务特点以及性能，整体提升较大
        // 写了详细的 v2 版本购票升级指南，详情查看：https://nageoffer.com/12306/question
        String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS, requestParam.getTrainId()));
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            return ticketService.executePurchaseTickets(requestParam);
        } finally {
            lock.unlock();
        }
    }

    //避免重复创建相同的锁对象
    //不用ConcurrentHashMap做本地缓存：ConcurrentHashMap只能存储，但是没有任何过期策略。这样会导致一个问题就是应用长时间不发布，越来越多的列车数据存储在容器中，直到内存溢出为止。
    private final Cache<String, ReentrantLock> localLockMap = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    //设置这个对象的关键在于，避免大量的用户同一时间访问刷新 Token
    private final Cache<String, Object> tokenTicketsRefreshMap = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final ReentrantLock refreshLock = new ReentrantLock(); // 新增：类级锁
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        // 为什么需要令牌限流？余票缓存限流不可以么？详情查看：https://nageoffer.com/12306/question
        TokenResultDTO tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);

        if (tokenResult.getTokenIsNull()) {
            boolean tokenRealNull = true;
            //避免大量的用户同一时间访问刷新 Token
            Object ifPresentObj = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());
            //减少锁竞争：多数线程通过缓存标记直接跳过，只有极少数线程需要竞争锁。
            if (ifPresentObj == null) {
                // 使用 tryLock() 非阻塞获取锁，设置超时时间（如 100ms）
                try {
                    if (refreshLock.tryLock(100, TimeUnit.MILLISECONDS)) {
                        try {
                            // 双重检查：避免锁竞争时其他线程已处理
                            if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId()) == null) {
                                ifPresentObj = new Object();
                                tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);
                                if(!tokenIsNullRefreshToken(requestParam, tokenResult)){
                                    //如果数据库余票够，完全可以让这个请求成功去扣减数据库，然后加载新的令牌余量，原来设计感觉让请求不公平了
                                    //缺点：多查询了数据库，时间可能偏长;只有少数的线程可以进到这里，提升可能不明显
                                    tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);
                                    if(!tokenResult.getTokenIsNull()){
                                        tokenRealNull= false;
                                    }
                                }
                            }
                        } finally {
                            refreshLock.unlock(); // 确保释放锁
                        }
                    }
                } catch (InterruptedException e) {
                    throw new ServiceException("列车站点已无余票");
                }
                // 锁获取失败：说明其他线程正在处理，等待下次循环或直接跳过
            }
            if(tokenRealNull){
                throw new ServiceException("列车站点已无余票");
            }
        }
        // v1 版本购票存在 4 个较为严重的问题，v2 版本相比较 v1 版本更具有业务特点以及性能，整体提升较大

        List<ReentrantLock> localLockList = new ArrayList<>();
        List<RLock> distributedLockList = new ArrayList<>();
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));

        //座位类型加锁
        /*seatTypeMap.forEach((seatType, count) -> {
            String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, requestParam.getTrainId(), seatType));
            ReentrantLock localLock = localLockMap.getIfPresent(lockKey);
            if (localLock == null) {
                synchronized (TicketService.class) {
                    if ((localLock = localLockMap.getIfPresent(lockKey)) == null) {
                        localLock = new ReentrantLock(true);
                        localLockMap.put(lockKey, localLock);
                    }
                }
            }
            localLockList.add(localLock);
            RLock distributedLock = redissonClient.getFairLock(lockKey);
            distributedLockList.add(distributedLock);
        });*/

        //TODO 锁会不会太多了--压测
        List<String> stations = trainStationService
                .listPassThroughTrainStation(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        //车次加锁-> 站点间隙锁
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < stations.size() - 1; i++) {
            String start = stations.get(i);
            String end = stations.get(i + 1);
            segments.add(start + "_" + end);
        }
        seatTypeMap.forEach((seatType, count) -> {
            segments.forEach(segment -> {
                String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2_SEGMENT, requestParam.getTrainId(), segment, seatType));
                ReentrantLock localLock = localLockMap.getIfPresent(lockKey);
                if (localLock == null) {
                    // Caffeine 不像 ConcurrentHashMap 做了并发读写安全控制，这里需要自己控制
                    synchronized (TicketService.class) {
                        if ((localLock = localLockMap.getIfPresent(lockKey)) == null) {
                            // 创建本地公平锁并放入本地公平锁容器中
                            localLock = new ReentrantLock(true);
                            localLockMap.put(lockKey, localLock);
                        }
                    }
                }
                localLockList.add(localLock);
                RLock distributedLock = redissonClient.getFairLock(lockKey);
                distributedLockList.add(distributedLock);
            });
        });
        try {
            for (ReentrantLock localLock : localLockList) {
                if (!localLock.tryLock(10, TimeUnit.SECONDS)) {
                    // 处理获取锁失败的情况
                    throw new ServiceException("系统繁忙，请稍后重试");
                }
            }
            for (RLock distributedLock : distributedLockList) {
                if (!distributedLock.tryLock(10, TimeUnit.SECONDS)) {
                    // 处理获取锁失败的情况
                    throw new ServiceException("系统繁忙，请稍后重试");
                }
            }
            return ticketService.executePurchaseTickets(requestParam);
        } catch (InterruptedException e) {
            throw new ServiceException("系统繁忙，请稍后重试");
        } finally {
            localLockList.forEach(localLock -> {
                try {
                    if (localLock.isHeldByCurrentThread()) {
                        localLock.unlock();
                    }
                } catch (Throwable ignored) {
                }
            });
            distributedLockList.forEach(distributedLock -> {
                try {
                    if (distributedLock.isHeldByCurrentThread()) {
                        distributedLock.unlock();
                    }
                } catch (Throwable ignored) {
                }
            });
        }
    }

    @Override
    public String purchaseTicketsV3(PurchaseTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        // 为什么需要令牌限流？余票缓存限流不可以么？详情查看：https://nageoffer.com/12306/question
        TokenResultDTO tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);
        if (tokenResult.getTokenIsNull()) {
            Object ifPresentObj = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());
            if (ifPresentObj == null) {
                synchronized (TicketService.class) {
                    if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId()) == null) {
                        ifPresentObj = new Object();
                        tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);
                        tokenIsNullRefreshToken(requestParam, tokenResult);
                    }
                }
            }
            throw new ServiceException("列车站点已无余票");
        }
        // 通过消息队列方式异步完成下单接口吞吐量提升，通过业务模式规避复杂度
        PurchaseTicketsEvent purchaseTicketsEvent = PurchaseTicketsEvent.builder()
                .orderTrackingId(SnowflakeIdUtil.nextIdStr())
                .originalRequestParam(requestParam)
                .userInfo(UserContext.getUser())
                .build();
        SendResult sendResult = purchaseTicketsSendProduce.sendMessage(purchaseTicketsEvent);
        if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)) {
            throw new ServiceException("发送用户异步购票消息失败");
        }
        // 返回全局唯一标识，当做用户购票异步返回和订单之间的关联关系
        return purchaseTicketsEvent.getOrderTrackingId();
    }

    @Override
    public OrderTrackingRespDTO purchaseTicketsV3Query(String orderTrackingId) {
        LambdaQueryWrapper<OrderTrackingDO> queryWrapper = Wrappers.lambdaQuery(OrderTrackingDO.class)
                .eq(OrderTrackingDO::getId, orderTrackingId)
                // 添加 username 字段，以防止非登录用户访问其他用户的数据，避免数据横向越权
                .eq(OrderTrackingDO::getUsername, UserContext.getUsername());
        OrderTrackingDO orderTrackingDO = orderTrackingMapper.selectOne(queryWrapper);
        return BeanUtil.convert(orderTrackingDO, OrderTrackingRespDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public TicketPurchaseRespDTO executePurchaseTickets(PurchaseTicketReqDTO requestParam) {
        List<TicketOrderDetailRespDTO> ticketOrderDetailResults = new ArrayList<>();
        String trainId = requestParam.getTrainId();
        // 节假日高并发购票Redis能扛得住么？详情查看：https://nageoffer.com/12306/question
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId,
                TrainDO.class,
                () -> trainMapper.selectById(trainId),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults = trainSeatTypeSelector.select(trainDO.getTrainType(), requestParam);
        List<TicketDO> ticketDOList = trainPurchaseTicketResults.stream()
                .map(each -> TicketDO.builder()
                        .username(UserContext.getUsername())
                        .trainId(Long.parseLong(requestParam.getTrainId()))
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .passengerId(each.getPassengerId())
                        .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                        .build())
                .toList();
        saveBatch(ticketDOList);
        Result<String> ticketOrderResult;
        try {
            List<TicketOrderItemCreateRemoteReqDTO> orderItemCreateRemoteReqDTOList = new ArrayList<>();
            trainPurchaseTicketResults.forEach(each -> {
                TicketOrderItemCreateRemoteReqDTO orderItemCreateRemoteReqDTO = TicketOrderItemCreateRemoteReqDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .phone(each.getPhone())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build();
                TicketOrderDetailRespDTO ticketOrderDetailRespDTO = TicketOrderDetailRespDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build();
                orderItemCreateRemoteReqDTOList.add(orderItemCreateRemoteReqDTO);
                ticketOrderDetailResults.add(ticketOrderDetailRespDTO);
            });
            LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                    .eq(TrainStationRelationDO::getTrainId, trainId)
                    .eq(TrainStationRelationDO::getDeparture, requestParam.getDeparture())
                    .eq(TrainStationRelationDO::getArrival, requestParam.getArrival());
            TrainStationRelationDO trainStationRelationDO = trainStationRelationMapper.selectOne(queryWrapper);
            TicketOrderCreateRemoteReqDTO orderCreateRemoteReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                    .departure(requestParam.getDeparture())
                    .arrival(requestParam.getArrival())
                    .orderTime(new Date())
                    .source(SourceEnum.INTERNET.getCode())
                    .trainNumber(trainDO.getTrainNumber())
                    .departureTime(trainStationRelationDO.getDepartureTime())
                    .arrivalTime(trainStationRelationDO.getArrivalTime())
                    .ridingDate(trainStationRelationDO.getDepartureTime())
                    .userId(UserContext.getUserId())
                    .username(UserContext.getUsername())
                    .trainId(Long.parseLong(requestParam.getTrainId()))
                    .ticketOrderItems(orderItemCreateRemoteReqDTOList)
                    .build();
            ticketOrderResult = ticketOrderRemoteService.createTicketOrder(orderCreateRemoteReqDTO);
            if (!ticketOrderResult.isSuccess() || StrUtil.isBlank(ticketOrderResult.getData())) {
                log.error("订单服务调用失败，返回结果：{}", ticketOrderResult.getMessage());
                throw new ServiceException("订单服务调用失败");
            }

        } catch (Throwable ex) {
            log.error("远程调用订单服务创建错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
        return new TicketPurchaseRespDTO(ticketOrderResult.getData(), ticketOrderDetailResults);
    }

    @Override
    public PayInfoRespDTO getPayInfo(String orderSn) {
        return payRemoteService.getPayInfo(orderSn).getData();
    }

    @Override
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
        Result<Void> cancelOrderResult = ticketOrderRemoteService.cancelTicketOrder(requestParam);
        if (cancelOrderResult.isSuccess() && !StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> ticketOrderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
            org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetail = ticketOrderDetailResult.getData();
            String trainId = String.valueOf(ticketOrderDetail.getTrainId());
            String departure = ticketOrderDetail.getDeparture();
            String arrival = ticketOrderDetail.getArrival();
            List<TicketOrderPassengerDetailRespDTO> trainPurchaseTicketResults = ticketOrderDetail.getPassengerDetails();
            try {
                seatService.unlock(trainId, departure, arrival, BeanUtil.convert(trainPurchaseTicketResults, TrainPurchaseTicketRespDTO.class));
            } catch (Throwable ex) {
                log.error("[取消订单] 订单号：{} 回滚列车DB座位状态失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
            ticketAvailabilityTokenBucket.rollbackInBucket(ticketOrderDetail);
            try {
                StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
                Map<Integer, List<TicketOrderPassengerDetailRespDTO>> seatTypeMap = trainPurchaseTicketResults.stream()
                        .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType));
                List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
                routeDTOList.forEach(each -> {
                    String keySuffix = StrUtil.join("_", trainId, each.getStartStation(), each.getEndStation());
                    seatTypeMap.forEach((seatType, ticketOrderPassengerDetailRespDTOList) -> {
                        stringRedisTemplate.opsForHash()
                                .increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), ticketOrderPassengerDetailRespDTOList.size());
                    });
                });
                //TODO 订单时间移除bitmap
                int startIndex = BitmapUtil.getTenMinuteIndex(ticketOrderDetail.getDepartureTime());
                int endIndex = BitmapUtil.getTenMinuteIndex(ticketOrderDetail.getArrivalTime());
                List<TicketOrderPassengerDetailRespDTO> ticketOrderPassengerDetailRespDTOList = ticketOrderDetail.getPassengerDetails();
                for(TicketOrderPassengerDetailRespDTO each : ticketOrderPassengerDetailRespDTOList){
                    String keySuffix = BitmapUtil.buildRedisKey(each.getIdCard(), ticketOrderDetail.getDepartureTime());
                    for (int i = startIndex; i < endIndex; i++) {
                        stringRedisTemplate.opsForValue().setBit(TICKET_TIME_CONFLICT + keySuffix, i, false);
                    }
                }

            } catch (Throwable ex) {
                log.error("[取消关闭订单] 订单号：{} 回滚列车Cache余票失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
        }
    }

    @Override
    public RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填
        refundReqDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(), requestParam);
        Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> orderDetailRespDTOResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
        if (!orderDetailRespDTOResult.isSuccess() && Objects.isNull(orderDetailRespDTOResult.getData())) {
            throw new ServiceException("车票订单不存在");
        }
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetailRespDTO = orderDetailRespDTOResult.getData();
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = ticketOrderDetailRespDTO.getPassengerDetails();
        if (CollectionUtil.isEmpty(passengerDetails)) {
            throw new ServiceException("车票子订单不存在");
        }
        RefundReqDTO refundReqDTO = new RefundReqDTO();
        if (RefundTypeEnum.PARTIAL_REFUND.getType().equals(requestParam.getType())) {
            TicketOrderItemQueryReqDTO ticketOrderItemQueryReqDTO = new TicketOrderItemQueryReqDTO();
            ticketOrderItemQueryReqDTO.setOrderSn(requestParam.getOrderSn());
            ticketOrderItemQueryReqDTO.setOrderItemRecordIds(requestParam.getSubOrderRecordIdReqList());
            Result<List<TicketOrderPassengerDetailRespDTO>> queryTicketItemOrderById = ticketOrderRemoteService.queryTicketItemOrderById(ticketOrderItemQueryReqDTO);
            List<TicketOrderPassengerDetailRespDTO> partialRefundPassengerDetails = passengerDetails.stream()
                    .filter(item -> queryTicketItemOrderById.getData().contains(item))
                    .collect(Collectors.toList());
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.PARTIAL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(partialRefundPassengerDetails);
        } else if (RefundTypeEnum.FULL_REFUND.getType().equals(requestParam.getType())) {
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.FULL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(passengerDetails);
        }
        if (CollectionUtil.isNotEmpty(passengerDetails)) {
            Integer partialRefundAmount = passengerDetails.stream()
                    .mapToInt(TicketOrderPassengerDetailRespDTO::getAmount)
                    .sum();
            //TODO 全部退款?
            refundReqDTO.setRefundAmount(partialRefundAmount);
        }
        refundReqDTO.setOrderSn(requestParam.getOrderSn());
        Result<RefundRespDTO> refundRespDTOResult = payRemoteService.commonRefund(refundReqDTO);
        if (!refundRespDTOResult.isSuccess() && Objects.isNull(refundRespDTOResult.getData())) {
            throw new ServiceException("车票订单退款失败");
        }
        return null; // 暂时返回空实体
    }

    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture).distinct().collect(Collectors.toList());
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival).distinct().collect(Collectors.toList());
    }

    private List<Integer> buildSeatClassList(List<TicketListDTO> seatResults) {
        Set<Integer> resultSeatClassList = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            for (SeatClassDTO item : each.getSeatClassList()) {
                resultSeatClassList.add(item.getType());
            }
        }
        return resultSeatClassList.stream().toList();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        Set<Integer> trainBrandSet = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            if (StrUtil.isNotBlank(each.getTrainBrand())) {
                trainBrandSet.addAll(StrUtil.split(each.getTrainBrand(), ",").stream().map(Integer::parseInt).toList());
            }
        }
        return trainBrandSet.stream().toList();
    }

    private final ScheduledExecutorService tokenIsNullRefreshExecutor = Executors.newScheduledThreadPool(1);

    /**
        获取令牌失败，重新刷新令牌数量
        @param requestParam 购买车票请求参数
        @return 数据库中余票是否足够，足够则返回false
     */
    private Boolean tokenIsNullRefreshToken(PurchaseTicketReqDTO requestParam, TokenResultDTO tokenResult) {
        AtomicReference<Boolean> seatsRealNull = new AtomicReference<>(true);
        RLock lock = redissonClient.getLock(String.format(LOCK_TOKEN_BUCKET_ISNULL, requestParam.getTrainId()));
        if (!lock.tryLock()) {
            return seatsRealNull.get();
        }
        // 为什么要延迟更新？因为如果不延时，一辆列车突发将列车票售空，可能数据库还没有扣减完，所以有个 10 秒缓冲时间
        tokenIsNullRefreshExecutor.schedule(() -> {
            try {
                int fakeTokenCount = 0;
                // 组装出座位类型以及每个座位类型下购票人数
                List<Integer> seatTypes = new ArrayList<>();
                Map<Integer, Integer> tokenCountMap = new HashMap<>();
                tokenResult.getTokenIsNullSeatTypeCounts().stream()
                        .map(each -> each.split("_"))
                        .forEach(split -> {
                            int seatType = Integer.parseInt(split[0]);
                            seatTypes.add(seatType);
                            tokenCountMap.put(seatType, Integer.parseInt(split[1]));
                        });
                // 获取数据库中座位类型对应的余票数量
                List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(Long.parseLong(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(), seatTypes);
                for (SeatTypeCountDTO each : seatTypeCountDTOList) {
                    Integer tokenCount = tokenCountMap.get(each.getSeatType());
                    // 如果判断数据库余票数大于扣减不成功的数量
                    if (tokenCount < each.getSeatCount()) {
                        fakeTokenCount++;
                        //ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);
                        //break;
                    }
                }
                if (fakeTokenCount > 0) {
                    ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);
                }
                if(fakeTokenCount == seatTypeCountDTOList.size()){
                    seatsRealNull.set(false);
                }
            } finally {
                lock.unlock();
            }
        }, 10, TimeUnit.SECONDS);
        return seatsRealNull.get();
    }

    @Override
    public void run(String... args) throws Exception {
        ticketService = ApplicationContextHolder.getBean(TicketService.class);
    }
}
