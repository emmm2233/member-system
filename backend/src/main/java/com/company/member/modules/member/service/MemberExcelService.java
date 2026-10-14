package com.company.member.modules.member.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.constant.OperatorContext;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.mapper.MemberAccountMapper;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.member.dto.MemberExcelRow;
import com.company.member.modules.member.dto.MemberQuery;
import com.company.member.modules.member.dto.MemberSaveCmd;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberImportTask;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.mapper.MemberImportTaskMapper;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.member.vo.MemberVO;
import com.company.member.modules.store.entity.Store;
import com.company.member.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会员导入 / 导出服务（EasyExcel）
 *
 * <p>设计要点：
 * <ul>
 *   <li>**先校验后落库**：逐行校验（手机号格式/重复/生日格式/来源），错误行单独收集</li>
 *   <li>**部分成功**：合法行入库，非法行写入错误明细文件，任务状态标记为"部分失败"</li>
 *   <li>每个合法会员走 {@link MemberService#create} 的同一套逻辑（账户/统计/标签/推荐关系）</li>
 *   <li>导出行数与上限受 {@code member.excel.max-rows} 约束，超限提示缩小范围</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberExcelService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final MemberAccountMapper accountMapper;
    private final MemberImportTaskMapper taskMapper;
    private final MemberService memberService;
    private final AccountService accountService;
    private final StoreMapper storeMapper;
    private final BizNoGenerator noGenerator;

    @Value("${member.storage.local-path:./data/files}")
    private String storagePath;

    @Value("${member.excel.max-rows:20000}")
    private int maxRows;

    /* ==================================================================
     *  模板下载
     * ================================================================== */

    /** 生成导入模板（含表头与一行示例，示例行以 # 开头便于识别） */
    public byte[] template() {
        List<MemberExcelRow> sample = new ArrayList<>();
        MemberExcelRow demo = new MemberExcelRow();
        demo.setPhone("13800001234");
        demo.setRealName("张三");
        demo.setNickname("小三儿");
        demo.setGender("男");
        demo.setBirthday("1995-06-18");
        demo.setSource("门店建档");
        demo.setReferrerPhone("");
        demo.setTags("");
        demo.setRemark("示例行，导入前请删除");
        sample.add(demo);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, MemberExcelRow.class)
                .sheet("会员导入模板")
                .doWrite(sample);
        return out.toByteArray();
    }

    /* ==================================================================
     *  导入
     * ================================================================== */

    /**
     * 导入会员（同步解析 + 异步落库）
     *
     * <p>文件较小时同步完成；行数超过 500 时改为异步任务，接口立即返回任务号。
     */
    public Map<String, Object> importMembers(InputStream inputStream, String fileName) {
        List<MemberExcelRow> rows;
        try {
            rows = EasyExcel.read(inputStream).head(MemberExcelRow.class).sheet().doReadSync();
        } catch (Exception e) {
            log.warn("[导入] Excel 解析失败：{}", e.getMessage());
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID, "Excel 解析失败，请使用下载的模板");
        }
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID, "文件无有效数据行");
        }
        if (rows.size() > maxRows) {
            throw new BizException(ErrorCode.EXPORT_TOO_LARGE,
                    "单次最多导入 " + maxRows + " 行，当前 " + rows.size() + " 行，请拆分后重试");
        }

        String taskNo = noGenerator.importTaskNo();
        MemberImportTask task = new MemberImportTask();
        task.setTaskNo(taskNo);
        task.setFileName(fileName);
        task.setTotalRows(rows.size());
        task.setSuccessRows(0);
        task.setFailRows(0);
        task.setStatus(MemberImportTask.STATUS_RUNNING);
        task.setOperatorId(OperatorContext.userId());
        taskMapper.insert(task);

        // 同步处理（行数受上限约束，单批处理耗时可控；如需超大文件可改为异步分片）
        ImportResult result = doImport(rows, taskNo);
        task.setSuccessRows(result.successCount());
        task.setFailRows(result.failCount());
        task.setStatus(result.failCount() == 0 ? MemberImportTask.STATUS_SUCCESS
                : (result.successCount() == 0 ? MemberImportTask.STATUS_FAILED : MemberImportTask.STATUS_PARTIAL));
        task.setErrorMsg(result.failCount() > 0
                ? "失败 " + result.failCount() + " 行，请下载错误明细" : null);
        task.setFinishedAt(LocalDateTime.now());
        if (result.failCount() > 0) {
            task.setErrorFileUrl(writeErrorFile(taskNo, result.errors()));
        }
        taskMapper.updateById(task);

        Map<String, Object> summary = new HashMap<>();
        summary.put("taskNo", taskNo);
        summary.put("totalRows", rows.size());
        summary.put("successRows", result.successCount());
        summary.put("failRows", result.failCount());
        summary.put("status", task.getStatus());
        summary.put("statusText", MemberImportTask.statusText(task.getStatus()));
        summary.put("errorFileUrl", task.getErrorFileUrl());
        summary.put("errors", result.errors().size() > 20 ? result.errors().subList(0, 20) : result.errors());
        summary.put("tip", result.failCount() == 0 ? "全部导入成功"
                : "成功 " + result.successCount() + " 行，失败 " + result.failCount() + " 行，可下载错误明细修正后重导");
        return summary;
    }

    private record ImportResult(int successCount, int failCount, List<Map<String, Object>> errors) {
    }

    private ImportResult doImport(List<MemberExcelRow> rows, String taskNo) {
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        // 预取已存在手机号（含本批次内重复检测）
        List<String> phones = rows.stream().map(MemberExcelRow::getPhone)
                .filter(p -> p != null && !p.isBlank()).map(String::trim).distinct().toList();
        Map<String, Long> existed = new HashMap<>();
        if (!phones.isEmpty()) {
            memberMapper.selectList(new LambdaQueryWrapper<Member>().in(Member::getPhone, phones))
                    .forEach(m -> existed.put(m.getPhone(), m.getId()));
        }
        java.util.Set<String> batchSeen = new java.util.HashSet<>();

        int rowNum = 1; // Excel 第 1 行是表头
        for (MemberExcelRow row : rows) {
            rowNum++;
            try {
                String phone = row.getPhone() == null ? null : row.getPhone().trim();
                if (phone == null || phone.isBlank()) {
                    // 无手机号：允许导入（微信游客会员），但手机号为空时不校验唯一性
                    phone = null;
                } else if (!phone.matches("^1[3-9]\\d{9}$")) {
                    throw new BizException(ErrorCode.PHONE_INVALID, "手机号格式不正确：" + phone);
                } else if (existed.containsKey(phone)) {
                    throw new BizException(ErrorCode.PHONE_EXISTS, "手机号已存在，会员号 " + existed.get(phone));
                } else if (!batchSeen.add(phone)) {
                    throw new BizException(ErrorCode.PHONE_EXISTS, "本文件内手机号重复：" + phone);
                }
                if (row.getRealName() != null && row.getRealName().length() > 50) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "姓名过长");
                }

                MemberSaveCmd cmd = new MemberSaveCmd();
                cmd.setPhone(phone);
                cmd.setRealName(blankToNull(row.getRealName()));
                cmd.setNickname(blankToNull(row.getNickname()));
                cmd.setGender(parseGender(row.getGender()));
                cmd.setBirthday(parseBirthday(row.getBirthday()));
                cmd.setSource(parseSource(row.getSource()));
                cmd.setReferrerPhone(blankToNull(row.getReferrerPhone()));
                cmd.setRemark(blankToNull(row.getRemark()));
                cmd.setStoreId(OperatorContext.storeId() != null ? OperatorContext.storeId() : 1L);

                MemberVO created = memberService.create(cmd);
                if (phone != null) {
                    existed.put(phone, created.getId());
                }
                success++;
            } catch (BizException e) {
                errors.add(errorRow(rowNum, row, e.getMessage()));
            } catch (Exception e) {
                log.warn("[导入] 第 {} 行处理异常：{}", rowNum, e.getMessage());
                errors.add(errorRow(rowNum, row, "系统异常：" + e.getMessage()));
            }
        }
        log.info("[导入] 任务 {} 完成：成功 {}，失败 {}", taskNo, success, errors.size());
        return new ImportResult(success, errors.size(), errors);
    }

    private Map<String, Object> errorRow(int rowNum, MemberExcelRow row, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("rowNum", rowNum);
        m.put("phone", row.getPhone());
        m.put("realName", row.getRealName());
        m.put("errorMsg", msg);
        return m;
    }

    /** 错误明细写出为 xlsx，返回可下载路径 */
    private String writeErrorFile(String taskNo, List<Map<String, Object>> errors) {
        try {
            List<MemberExcelRow> list = new ArrayList<>();
            for (Map<String, Object> e : errors) {
                MemberExcelRow row = new MemberExcelRow();
                row.setRowNum(String.valueOf(e.get("rowNum")));
                row.setPhone(String.valueOf(e.get("phone")));
                row.setRealName(String.valueOf(e.get("realName")));
                row.setErrorMsg(String.valueOf(e.get("errorMsg")));
                list.add(row);
            }
            Path dir = Paths.get(storagePath, "error");
            Files.createDirectories(dir);
            Path file = dir.resolve("member-import-error-" + taskNo + ".xlsx");
            EasyExcel.write(file.toFile(), MemberExcelRow.class)
                    .sheet("错误明细")
                    .doWrite(list);
            return "/files/error/" + file.getFileName();
        } catch (Exception e) {
            log.error("[导入] 错误明细写出失败", e);
            return null;
        }
    }

    public MemberImportTask task(String taskNo) {
        MemberImportTask task = taskMapper.selectOne(new LambdaQueryWrapper<MemberImportTask>()
                .eq(MemberImportTask::getTaskNo, taskNo).last("LIMIT 1"));
        if (task == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "导入任务不存在");
        }
        return task;
    }

    /* ==================================================================
     *  导出
     * ================================================================== */

    /**
     * 导出会员（按查询条件）
     *
     * @return xlsx 字节流；同时可返回行数供调用方提示
     */
    public byte[] export(MemberQuery query) {
        // 复用列表查询逻辑，但放开分页（受 maxRows 约束）
        query.setPageNum(1);
        query.setPageSize(Math.min(maxRows, 10000));
        var page = memberService.page(query);
        if (page.getTotal() > maxRows) {
            throw new BizException(ErrorCode.EXPORT_TOO_LARGE,
                    "符合条件的数据 " + page.getTotal() + " 条，超过单次导出上限 " + maxRows + " 条，请缩小查询范围");
        }

        List<MemberVO> list = page.getList();
        // 余额等资产字段需要补充（列表已带，此处仅补齐等级名与状态文本）
        List<MemberExcelRow> rows = new ArrayList<>(list.size());
        for (MemberVO vo : list) {
            MemberExcelRow row = new MemberExcelRow();
            row.setMemberNo(vo.getMemberNo());
            row.setPhone(vo.getPhone() == null ? vo.getPhoneMasked() : vo.getPhone());
            row.setRealName(vo.getRealName());
            row.setNickname(vo.getNickname());
            row.setGender(genderText(vo.getGender()));
            row.setBirthday(vo.getBirthday() == null ? null : vo.getBirthday().format(DATE));
            row.setLevelName(vo.getLevelName());
            row.setBalance(fmt(vo.getBalance()));
            row.setGiftBalance(fmt(vo.getGiftBalance()));
            row.setPoints(vo.getPoints() == null ? "0" : String.valueOf(vo.getPoints()));
            row.setGrowth(vo.getGrowth() == null ? "0" : String.valueOf(vo.getGrowth()));
            row.setTotalConsume(fmt(vo.getTotalConsume()));
            row.setTotalConsumeCount(vo.getTotalConsumeCount() == null ? "0" : String.valueOf(vo.getTotalConsumeCount()));
            row.setCreatedAt(vo.getCreatedAt() == null ? null : vo.getCreatedAt().format(DT));
            row.setStatusText(vo.getStatusText());
            row.setRemark(vo.getRemark());
            rows.add(row);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, MemberExcelRow.class)
                .sheet("会员列表")
                .doWrite(rows);
        log.info("[导出] 会员导出完成，共 {} 行", rows.size());
        return out.toByteArray();
    }

    /* ==================================================================
     *  工具
     * ================================================================== */

    private static String fmt(java.math.BigDecimal v) {
        return MoneyUtil.scale(v == null ? java.math.BigDecimal.ZERO : v).toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Integer parseGender(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String t = text.trim();
        if (t.startsWith("男") || "1".equals(t)) {
            return 1;
        }
        if (t.startsWith("女") || "2".equals(t)) {
            return 2;
        }
        return 0;
    }

    private static String genderText(Integer gender) {
        if (gender == null) {
            return "未知";
        }
        return switch (gender) {
            case 1 -> "男";
            case 2 -> "女";
            default -> "未知";
        };
    }

    private static LocalDate parseBirthday(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().replace("/", "-");
        try {
            return LocalDate.parse(t);
        } catch (Exception ignored) {
            // 兼容 Excel 把日期读成 "1995-06-18 00:00:00" 或 "1995.06.18"
            String normalized = t.replace(".", "-");
            if (normalized.length() > 10) {
                normalized = normalized.substring(0, 10);
            }
            try {
                return LocalDate.parse(normalized);
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "生日格式应为 yyyy-MM-dd：" + text);
            }
        }
    }

    private static Integer parseSource(String text) {
        if (text == null || text.isBlank()) {
            return 3; // 导入默认来源
        }
        String t = text.trim();
        return switch (t) {
            case "门店建档", "1" -> 1;
            case "小程序注册", "2" -> 2;
            case "批量导入", "3" -> 3;
            case "推荐注册", "4" -> 4;
            default -> 3;
        };
    }
}
