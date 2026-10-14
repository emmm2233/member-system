package com.company.member.modules.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "新建/编辑会员")
public class MemberSaveCmd {

    @Schema(description = "会员 id（编辑时必填）")
    private Long id;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "手机号（唯一，可空）", example = "13800001234")
    private String phone;

    @Size(max = 50, message = "姓名过长")
    private String realName;

    @Size(max = 64, message = "昵称过长")
    private String nickname;

    private String avatar;

    @Schema(description = "性别 0未知 1男 2女")
    private Integer gender;

    @Schema(description = "生日（生日礼依据，年份可默认 1900）")
    private LocalDate birthday;

    @Schema(description = "归属门店 id")
    private Long storeId;

    @Schema(description = "来源 1门店建档 2小程序注册 3批量导入 4推荐注册")
    private Integer source;

    @Schema(description = "推荐人手机号（建档时绑定推荐关系）")
    private String referrerPhone;

    @Schema(description = "推荐人会员 id")
    private Long referrerId;

    @Schema(description = "标签 id 列表（全量覆盖）")
    private List<Long> tagIds;

    @Size(max = 255)
    private String remark;

    @Schema(description = "初始等级 id（不传则取默认等级）")
    private Long levelId;
}
