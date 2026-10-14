package com.company.member.modules.account.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 资产变动结果（变动后的账户快照）
 */
@Data
@Schema(description = "资产变动结果")
public class AccountChangeResult implements Serializable {

    @Schema(description = "会员 id")
    private Long memberId;

    @Schema(description = "本金余额")
    private BigDecimal balance;

    @Schema(description = "赠送余额")
    private BigDecimal giftBalance;

    @Schema(description = "积分")
    private Long points;

    @Schema(description = "成长值")
    private Long growth;

    @Schema(description = "累计充值")
    private BigDecimal totalRecharge;

    @Schema(description = "累计消费")
    private BigDecimal totalConsume;

    @Schema(description = "本次本金变动")
    private BigDecimal balanceChange;

    @Schema(description = "本次赠送变动")
    private BigDecimal giftChange;

    @Schema(description = "本次积分变动")
    private Long pointsChange;

    @Schema(description = "本次成长值变动")
    private Long growthChange;

    @Schema(description = "积分批次 id（获得积分时有值）")
    private Long pointBatchId;

    @Schema(description = "是否命中幂等（true 表示本次为重复请求，未产生实际变动）")
    private boolean idempotentHit;

    public static AccountChangeResult of(com.company.member.modules.account.entity.MemberAccount acc) {
        AccountChangeResult r = new AccountChangeResult();
        r.setMemberId(acc.getMemberId());
        r.setBalance(acc.getBalance());
        r.setGiftBalance(acc.getGiftBalance());
        r.setPoints(acc.getPoints());
        r.setGrowth(acc.getGrowth());
        r.setTotalRecharge(acc.getTotalRecharge());
        r.setTotalConsume(acc.getTotalConsume());
        return r;
    }
}
