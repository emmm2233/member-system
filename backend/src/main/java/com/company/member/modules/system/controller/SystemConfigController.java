package com.company.member.modules.system.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.system.entity.SysConfig;
import com.company.member.modules.system.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端 · 系统参数
 */
@Tag(name = "管理端-系统参数")
@RestController
@RequestMapping("/api/admin/system/configs")
@RequiredArgsConstructor
public class SystemConfigController {

    private final ConfigService configService;

    @Operation(summary = "参数列表（按分组）")
    @GetMapping
    @PreAuthorize("@ps.has('system:config:list')")
    public R<List<SysConfig>> list() {
        return R.ok(configService.listAll());
    }

    @Operation(summary = "修改单个参数")
    @PutMapping
    @PreAuthorize("@ps.has('system:config:edit')")
    @OpAudit(opType = "UPDATE", desc = "系统参数修改")
    public R<Void> update(@RequestBody Map<String, String> body) {
        String key = body.get("configKey");
        String value = body.get("configValue");
        configService.update(key, value);
        return R.ok();
    }
}
