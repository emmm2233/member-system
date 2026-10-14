package com.company.member.modules.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 门店新增/编辑入参
 */
@Data
public class StoreSaveCmd {

    @Size(max = 32, message = "门店编码过长")
    private String storeCode;

    @NotBlank(message = "门店名称不能为空")
    @Size(max = 100, message = "门店名称过长")
    private String storeName;

    @Size(max = 50)
    private String province;
    @Size(max = 50)
    private String city;
    @Size(max = 50)
    private String district;
    @Size(max = 255)
    private String address;

    private java.math.BigDecimal longitude;
    private java.math.BigDecimal latitude;

    @Size(max = 50)
    private String contactName;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$|^0\\d{2,3}-?\\d{7,8}$", message = "联系电话格式不正确")
    private String contactPhone;

    @Size(max = 100)
    private String businessHours;

    /** 店长（m_staff.id，可空） */
    private Long managerId;

    /** 0 停业 1 营业 */
    private Integer status;

    private Integer sortNo;
}
