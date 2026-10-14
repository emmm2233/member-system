package com.company.member.modules.store.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.R;
import com.company.member.modules.store.dto.StoreSaveCmd;
import com.company.member.modules.store.service.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端 · 门店管理
 */
@Tag(name = "管理端-门店管理")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @Operation(summary = "门店列表")
    @GetMapping
    @PreAuthorize("@ps.has('store:list')")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status) {
        return R.ok(storeService.list(keyword, status));
    }

    @Operation(summary = "门店详情")
    @GetMapping("/{id}")
    @PreAuthorize("@ps.has('store:list')")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(storeService.detail(id));
    }

    @Operation(summary = "新增门店（编码留空自动生成 S001/S002…）")
    @PostMapping
    @PreAuthorize("@ps.has('store:edit')")
    public R<Map<String, Object>> create(@Valid @RequestBody StoreSaveCmd cmd) {
        return R.ok(storeService.create(cmd));
    }

    @Operation(summary = "编辑门店")
    @PutMapping("/{id}")
    @PreAuthorize("@ps.has('store:edit')")
    public R<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody StoreSaveCmd cmd) {
        return R.ok(storeService.update(id, cmd));
    }

    @Operation(summary = "停业/营业")
    @PutMapping("/{id}/status")
    @PreAuthorize("@ps.has('store:edit')")
    @OpAudit(opType = "UPDATE", desc = "门店停业营业")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        storeService.changeStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除门店（有在职员工者拒绝，请改用停业）")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ps.has('store:edit')")
    public R<Void> remove(@PathVariable Long id) {
        storeService.remove(id);
        return R.ok();
    }
}
