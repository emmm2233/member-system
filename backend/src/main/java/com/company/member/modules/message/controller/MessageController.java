package com.company.member.modules.message.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 管理端 · 消息中心（模板 / 发送记录 / 渠道诊断）
 */
@Tag(name = "管理端-消息中心")
@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "消息模板列表")
    @GetMapping("/tpls")
    @PreAuthorize("@ps.has('message:tpl:list')")
    public R<List<Map<String, Object>>> tpls() {
        return R.ok(messageService.listTpls());
    }

    @Operation(summary = "编辑消息模板（含微信模板ID与字段映射）")
    @PutMapping("/tpls/{id}")
    @PreAuthorize("@ps.has('message:tpl:edit')")
    @OpAudit(opType = "UPDATE", desc = "消息模板修改")
    public R<Void> updateTpl(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        messageService.updateTpl(id, body);
        return R.ok();
    }

    @Operation(summary = "渠道就绪状态（微信订阅消息是否可用）")
    @GetMapping("/channels")
    @PreAuthorize("@ps.has('message:tpl:list')")
    public R<List<Map<String, Object>>> channels() {
        return R.ok(messageService.channelStatus());
    }

    @Operation(summary = "消息发送记录")
    @GetMapping("/records")
    @PreAuthorize("@ps.has('message:record:list')")
    public R<PageResult<Map<String, Object>>> records(@RequestParam(required = false) Long memberId,
                                                      @RequestParam(required = false) String tplCode,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int pageNum,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(messageService.pageRecords(memberId, tplCode, status, keyword, pageNum, pageSize));
    }

    @Operation(summary = "手动重发消息")
    @PostMapping("/records/{id}/resend")
    @PreAuthorize("@ps.has('message:record:resend')")
    @OpAudit(opType = "RETRY", desc = "消息重发")
    public R<Void> resend(@PathVariable Long id) {
        messageService.resend(id);
        return R.ok();
    }

    @Operation(summary = "手动触发失败消息重试批次")
    @PostMapping("/records/retry")
    @PreAuthorize("@ps.has('message:record:resend')")
    public R<Integer> retry(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(messageService.retryPending(limit));
    }
}
