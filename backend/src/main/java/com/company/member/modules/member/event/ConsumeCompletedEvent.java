package com.company.member.modules.member.event;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 消费完成事件
 *
 * <p>在收银结算事务提交后发布（afterCommit），用于驱动：
 * <ul>
 *   <li>等级实时评定与升级礼</li>
 *   <li>推荐有礼首单奖励结算</li>
 *   <li>订阅消息推送</li>
 *   <li>看板缓存失效</li>
 * </ul>
 *
 * <p>所有监听器必须自身幂等（同一事件可能因重试被投递多次）。
 */
@Data
@AllArgsConstructor
public class ConsumeCompletedEvent {

    private Long memberId;
    private Long orderId;
    private String orderNo;
    private Long storeId;
    private BigDecimal paidAmount;
    private BigDecimal pointsGained;
    private BigDecimal growthGained;
}
