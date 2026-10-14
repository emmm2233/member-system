package com.company.member.modules.member.controller;

import com.company.member.common.annotation.OpAudit;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.member.dto.MemberQuery;
import com.company.member.modules.member.dto.MemberSaveCmd;
import com.company.member.modules.member.entity.MemberImportTask;
import com.company.member.modules.member.entity.MemberTag;
import com.company.member.modules.member.mapper.MemberTagMapper;
import com.company.member.modules.member.service.MemberExcelService;
import com.company.member.modules.member.service.MemberService;
import com.company.member.modules.member.vo.MemberDetailVO;
import com.company.member.modules.member.vo.MemberVO;
import com.company.member.security.SecurityService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 管理端 · 会员中心
 */
@Slf4j
@Tag(name = "管理端-会员中心")
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final MemberExcelService excelService;
    private final MemberTagMapper tagMapper;
    private final SecurityService securityService;

    @Operation(summary = "会员分页列表")
    @GetMapping
    @PreAuthorize("@ps.has('member:list')")
    public R<PageResult<MemberVO>> page(MemberQuery query) {
        return R.ok(memberService.page(query));
    }

    @Operation(summary = "会员详情")
    @GetMapping("/{id}")
    @PreAuthorize("@ps.has('member:query')")
    public R<MemberVO> detail(@PathVariable Long id) {
        return R.ok(memberService.detail(id).getMember());
    }

    @Operation(summary = "会员 360° 视图（资产+统计+卡券+最近订单）")
    @GetMapping("/{id}/overview")
    @PreAuthorize("@ps.has('member:query')")
    public R<MemberDetailVO> overview(@PathVariable Long id) {
        return R.ok(memberService.detail(id));
    }

    @Operation(summary = "新建会员")
    @PostMapping
    @PreAuthorize("@ps.has('member:add')")
    @OpAudit(opType = "CREATE", desc = "会员建档")
    public R<MemberVO> create(@Valid @RequestBody MemberSaveCmd cmd) {
        return R.ok(memberService.create(cmd));
    }

    @Operation(summary = "编辑会员资料")
    @PutMapping("/{id}")
    @PreAuthorize("@ps.has('member:edit')")
    public R<MemberVO> update(@PathVariable Long id, @Valid @RequestBody MemberSaveCmd cmd) {
        return R.ok(memberService.update(id, cmd));
    }

    @Operation(summary = "冻结/解冻/注销会员（status=3 注销）")
    @PutMapping("/{id}/status")
    @PreAuthorize("@ps.has('member:edit')")
    @OpAudit(opType = "UPDATE", desc = "会员状态变更")
    public R<Void> changeStatus(@PathVariable Long id,
                                @RequestParam Integer status,
                                @RequestParam(required = false) String remark) {
        memberService.changeStatus(id, status, remark);
        return R.ok();
    }

    @Operation(summary = "手工调整会员等级")
    @PostMapping("/{id}/level")
    @PreAuthorize("@ps.has('member:level')")
    @OpAudit(opType = "UPDATE", desc = "会员调级")
    public R<Void> changeLevel(@PathVariable Long id,
                               @RequestParam Long levelId,
                               @RequestParam(required = false) String reason) {
        memberService.changeLevel(id, levelId, reason);
        return R.ok();
    }

    @Operation(summary = "设置会员标签（全量覆盖）")
    @PostMapping("/{id}/tags")
    @PreAuthorize("@ps.has('member:tag')")
    public R<Void> setTags(@PathVariable Long id, @RequestBody List<Long> tagIds) {
        memberService.saveTags(id, tagIds);
        return R.ok();
    }

    @Operation(summary = "标签列表（下拉用）")
    @GetMapping("/tags")
    @PreAuthorize("@ps.has('member:list')")
    public R<List<MemberTag>> tags() {
        return R.ok(tagMapper.selectList(new LambdaQueryWrapper<MemberTag>()
                .eq(MemberTag::getStatus, 1).orderByAsc(MemberTag::getSortNo)));
    }

    @Operation(summary = "收银台会员检索（手机号后4位/卡号/姓名，最多 10 条）")
    @GetMapping("/search-for-cashier")
    @PreAuthorize("@ps.hasAny('order:add','member:list')")
    public R<List<MemberVO>> searchForCashier(@RequestParam String keyword) {
        MemberQuery query = new MemberQuery();
        query.setKeyword(keyword);
        query.setStatus(1);
        query.setPageNum(1);
        query.setPageSize(10);
        return R.ok(memberService.page(query).getList());
    }

    /* ==================== 导入 / 导出 ==================== */

    @Operation(summary = "下载导入模板（xlsx）")
    @GetMapping("/import/template")
    @PreAuthorize("@ps.has('member:import')")
    public ResponseEntity<byte[]> template() {
        byte[] data = excelService.template();
        return download(data, "会员导入模板.xlsx");
    }

    @Operation(summary = "导入会员（Excel，返回逐行结果与错误明细）")
    @PostMapping("/import")
    @PreAuthorize("@ps.has('member:import')")
    @OpAudit(opType = "IMPORT", desc = "会员导入")
    public R<Map<String, Object>> importMembers(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择要导入的文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "仅支持 .xlsx / .xls 文件");
        }
        try (java.io.InputStream in = file.getInputStream()) {
            return R.ok(excelService.importMembers(in, file.getOriginalFilename()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会员导入] 处理失败", e);
            throw new BizException(ErrorCode.OPERATION_FAILED, "导入失败：" + e.getMessage());
        }
    }

    @Operation(summary = "查询导入任务结果")
    @GetMapping("/import/{taskNo}")
    @PreAuthorize("@ps.has('member:import')")
    public R<MemberImportTask> importTask(@PathVariable String taskNo) {
        return R.ok(excelService.task(taskNo));
    }

    @Operation(summary = "导出会员（按当前查询条件，xlsx）")
    @PostMapping("/export")
    @PreAuthorize("@ps.has('member:export')")
    @OpAudit(opType = "EXPORT", desc = "会员数据导出")
    public ResponseEntity<byte[]> export(@RequestBody(required = false) MemberQuery query) {
        MemberQuery q = query == null ? new MemberQuery() : query;
        byte[] data = excelService.export(q);
        String fileName = "会员列表-" + java.time.LocalDate.now() + ".xlsx";
        return download(data, fileName);
    }

    private ResponseEntity<byte[]> download(byte[] data, String fileName) {
        String encoded = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }
}
