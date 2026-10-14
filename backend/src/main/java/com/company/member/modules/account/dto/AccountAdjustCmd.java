package com.company.member.modules.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 资产调整命令（必填原因，操作留痕）
 */
@Data
@Schema(description = "资产调整")
public class AccountAdjustCmd {

    @NotNull(message = "会员 id 不能为空")
    @Schema(description = "会员 id")
    private Long memberId;

    @Schema(description = "本金余额变动（可为负，不可使余额为负）")
    private BigDecimal balanceDelta;

    @Schema(description = "赠送余额变动")
    private BigDecimal giftDelta;

    @Schema(description = "积分变动")
    private Long pointsDelta;

    @Schema(description = "成长值变动")
    private Long growthDelta;

    @Size(max = 500, message = "原因最长 500 字")
    @Schema(description = "调整原因（必填）")
    private String reason;

    @Schema(description = "凭证图片 URL")
    private String attachImage;

    /**
     * 幂等键（前端传入，与请求头 X-Idempotency-Key 同值）
     *
     * <p><b>为什么这个字段必须存在</b>：资产调整此前是「前端发了幂等头、后端没有接收」——
     * 控制器缺 {@code @Idempotent}，服务层又自造 {@code ADJ+时间戳} 单号，
     * 于是唯一索引永不冲突，点两下就把余额调两次。
     *
     * <p>现在这个值会一路传到 {@code AccountChangeCmd.idempotencyKey}，
     * 由流水表的 {@code uk_idem(idempotency_key, biz_type)} 兜底：
     * 即使缓存里的幂等占位已过期，同一个键重复提交也会在读侧被识别并直接返回原结果，
     * 不会产生第二笔变动。
     */
    @Size(max = 64, message = "幂等键最长 64 字符")
    @Schema(description = "幂等键（客户端生成；重复提交必须传相同值）")
    private String idempotencyKey;
}
