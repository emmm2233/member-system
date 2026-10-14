package com.company.member.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "管理端登录请求")
public class LoginCmd {

    @NotBlank(message = "登录账号不能为空")
    @Schema(description = "登录账号", example = "admin")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码", example = "Admin@123")
    private String password;

    @Schema(description = "图形验证码（连续失败后必填）")
    private String captcha;

    @Schema(description = "验证码标识")
    private String captchaKey;
}
