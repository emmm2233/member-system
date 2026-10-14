package com.company.member.modules.member.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会员列表行视图
 */
@Data
@Schema(description = "会员列表项")
public class MemberVO implements Serializable {

    private Long id;
    private String memberNo;
    @Schema(description = "手机号（默认脱敏，有权限时可传 plainPhone=true 获取完整）")
    private String phone;
    private String phoneMasked;
    private String nickname;
    private String avatar;
    private String realName;
    private Integer gender;
    private LocalDate birthday;

    private Long levelId;
    private String levelName;
    private Integer levelValue;
    private LocalDateTime levelExpireAt;

    private Integer status;
    private String statusText;
    private Integer source;
    private String sourceText;

    private Long storeId;
    private String storeName;

    private BigDecimal balance;
    private BigDecimal giftBalance;
    private Long points;
    private Long growth;

    private BigDecimal totalConsume;
    private Integer totalConsumeCount;
    private BigDecimal totalRecharge;

    private LocalDateTime lastConsumeAt;
    private LocalDateTime createdAt;
    private String remark;

    private List<TagBrief> tags = new ArrayList<>();

    @Data
    @Schema(description = "标签简要")
    public static class TagBrief implements Serializable {
        private Long id;
        private String tagName;
        private String tagColor;
    }

    /** 状态文本 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "冻结";
            case 3 -> "注销";
            default -> "未知";
        };
    }

    public static String sourceText(Integer source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case 1 -> "门店建档";
            case 2 -> "小程序注册";
            case 3 -> "批量导入";
            case 4 -> "推荐注册";
            default -> "其他";
        };
    }
}
