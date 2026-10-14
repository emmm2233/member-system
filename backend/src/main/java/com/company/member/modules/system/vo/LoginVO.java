package com.company.member.modules.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * 登录结果
 */
@Data
@Schema(description = "登录结果")
public class LoginVO implements Serializable {

    @Schema(description = "访问令牌")
    private String accessToken;

    @Schema(description = "刷新令牌")
    private String refreshToken;

    @Schema(description = "accessToken 有效期（秒）")
    private Long expiresIn;

    @Schema(description = "是否需要修改密码（首次登录/重置后）")
    private Boolean mustChangePassword = false;

    @Schema(description = "用户信息")
    private UserInfoVO userInfo;

    @Data
    @Schema(description = "登录用户信息")
    public static class UserInfoVO implements Serializable {
        private Long userId;
        private String username;
        private String realName;
        private String phone;
        private Long storeId;
        private String storeName;
        /** 1全部 2本店 3本人 4自定义 */
        private Integer dataScope;
        private List<String> roles;
        private Set<String> perms;
        private String userType;
    }
}
