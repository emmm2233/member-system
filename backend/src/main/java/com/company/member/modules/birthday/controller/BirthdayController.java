package com.company.member.modules.birthday.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.birthday.entity.BirthdayGiftLog;
import com.company.member.modules.birthday.service.BirthdayGiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端 · 生日礼
 */
@Tag(name = "管理端-生日礼")
@RestController
@RequestMapping("/api/birthday")
@RequiredArgsConstructor
public class BirthdayController {

    private final BirthdayGiftService birthdayGiftService;

    @Operation(summary = "生日礼概览")
    @GetMapping("/stats")
    @PreAuthorize("@ps.has('birthday:list')")
    public R<Map<String, Object>> stats() {
        return R.ok(birthdayGiftService.stats());
    }

    @Operation(summary = "规则配置项")
    @GetMapping("/config")
    @PreAuthorize("@ps.has('birthday:config')")
    public R<List<Map<String, Object>>> config() {
        return R.ok(birthdayGiftService.describe());
    }

    @Operation(summary = "保存生日礼规则")
    @PostMapping("/config")
    @PreAuthorize("@ps.has('birthday:config')")
    @OpAudit(opType = "UPDATE", desc = "生日礼规则修改")
    public R<Void> saveConfig(@RequestBody Map<String, String> values) {
        birthdayGiftService.update(values);
        return R.ok();
    }

    @Operation(summary = "按日期预览将发放人数（不实际发放）")
    @GetMapping("/preview")
    @PreAuthorize("@ps.has('birthday:list')")
    public R<Map<String, Object>> preview(@RequestParam(required = false) String monthDay) {
        return R.ok(birthdayGiftService.preview(monthDay));
    }

    @Operation(summary = "生日礼发放记录")
    @GetMapping("/logs")
    @PreAuthorize("@ps.has('birthday:list')")
    public R<PageResult<Map<String, Object>>> logs(@RequestParam(required = false) Long memberId,
                                                   @RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) Integer year,
                                                   @RequestParam(defaultValue = "1") int pageNum,
                                                   @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(birthdayGiftService.pageLogs(memberId, status, year, pageNum, pageSize));
    }

    @Operation(summary = "手动发放/补发某会员生日礼")
    @PostMapping("/issue")
    @PreAuthorize("@ps.has('birthday:issue')")
    @OpAudit(opType = "GRANT", desc = "生日礼手工发放")
    public R<Map<String, Object>> issue(@RequestBody Map<String, Object> body) {
        Long memberId = body.get("memberId") == null ? null : Long.valueOf(String.valueOf(body.get("memberId")));
        Integer year = body.get("year") == null ? null : Integer.valueOf(String.valueOf(body.get("year")));
        boolean force = body.get("force") == null || Boolean.parseBoolean(String.valueOf(body.get("force")));
        BirthdayGiftLog log = birthdayGiftService.manualIssue(memberId, year, force);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", log.getId());
        out.put("giftYear", log.getGiftYear());
        out.put("status", log.getStatus());
        out.put("points", log.getPoints());
        out.put("balance", log.getBalance());
        out.put("couponId", log.getCouponId());
        out.put("failReason", log.getFailReason());
        return R.ok(out);
    }

    @Operation(summary = "手动触发批量发放（等同定时任务）")
    @PostMapping("/tasks/issue")
    @PreAuthorize("@ps.has('birthday:issue')")
    public R<Integer> issueBatch(@RequestParam(defaultValue = "2000") int limit) {
        return R.ok(birthdayGiftService.issueBatch(limit));
    }

    @Operation(summary = "手动触发失败修复")
    @PostMapping("/tasks/repair")
    @PreAuthorize("@ps.has('birthday:issue')")
    public R<Integer> repair(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(birthdayGiftService.repairFailed(limit));
    }
}
