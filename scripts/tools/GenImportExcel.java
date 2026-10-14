import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生成用于导入自检的 Excel 文件（含合法行与非法行），并校验导出文件的表头。
 *
 * 用法：
 *   javac -cp <easyexcel+依赖> GenImportExcel.java -d <outdir>
 *   java  -cp <outdir;easyexcel+依赖> GenImportExcel <outDir>
 *
 * 生成：
 *   member-import-valid.xlsx   全部合法（3 行）
 *   member-import-mixed.xlsx   混合（2 合法 + 3 非法：手机号格式错/重复/生日格式错）
 */
public class GenImportExcel {

    /** 与后端 MemberExcelRow 的列顺序保持一致 */
    private static final String[] HEAD = {
            "手机号", "姓名", "昵称", "性别", "生日", "来源", "推荐人手机号", "标签", "备注"
    };

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        new File(outDir).mkdirs();

        long seed = System.currentTimeMillis() % 100000000L;
        // 三组互不重叠的号段，避免"合法文件"与"混合文件"互相冲突
        String p1 = String.format("137%08d", seed);
        String p2 = String.format("137%08d", (seed + 1) % 100000000L);
        String p3 = String.format("137%08d", (seed + 2) % 100000000L);
        // 混合文件使用另一号段（135/136），保证"部分成功"分支可被验证
        String m1 = String.format("135%08d", seed);
        String m2 = String.format("135%08d", (seed + 1) % 100000000L);

        // 1. 全部合法
        List<List<String>> valid = new ArrayList<>();
        valid.add(row(p1, "自检甲", "甲甲", "男", "1990-01-15", "批量导入", "", "", "valid-1"));
        valid.add(row(p2, "自检乙", "乙乙", "女", "1992-03-20", "批量导入", p1, "", "valid-2 带推荐人"));
        valid.add(row(p3, "自检丙", "丙丙", "未知", "1988-12-01", "门店建档", "", "VIP", "valid-3 无推荐人"));
        write(outDir + "/member-import-valid.xlsx", valid);

        // 2. 混合（2 合法 + 3 非法）
        List<List<String>> mixed = new ArrayList<>();
        mixed.add(row(m1, "混合甲", "混甲", "男", "1991-05-05", "批量导入", "", "", "mixed-valid-1"));
        mixed.add(row(m2, "混合乙", "混乙", "女", "1993-07-07", "分店导入", "", "", "mixed-valid-2 来源未知按导入处理"));
        mixed.add(row("12345", "手机号错", "错1", "男", "1990-01-01", "批量导入", "", "", "invalid: phone format"));
        mixed.add(row(m1, "重复手机号", "错2", "男", "1990-01-01", "批量导入", "", "", "invalid: duplicate in batch"));
        mixed.add(row(String.format("138%08d", (seed + 9) % 100000000L), "生日错", "错3", "女", "not-a-date", "批量导入", "", "", "invalid: birthday"));
        write(outDir + "/member-import-mixed.xlsx", mixed);

        System.out.println("VALID_FILE=" + outDir + "/member-import-valid.xlsx");
        System.out.println("MIXED_FILE=" + outDir + "/member-import-mixed.xlsx");
        System.out.println("PHONE_1=" + p1);
        System.out.println("PHONE_2=" + p2);
        System.out.println("PHONE_3=" + p3);
        System.out.println("MIXED_PHONE_1=" + m1);
        System.out.println("MIXED_PHONE_2=" + m2);
    }

    private static List<String> row(String... cells) {
        List<String> r = new ArrayList<>();
        for (String c : cells) {
            r.add(c);
        }
        return r;
    }

    /** 写出：第一行为表头，其余为数据 */
    private static void write(String path, List<List<String>> rows) {
        List<List<String>> head = new ArrayList<>();
        for (String h : HEAD) {
            List<String> col = new ArrayList<>();
            col.add(h);
            head.add(col);
        }
        EasyExcel.write(path).head(head).sheet("会员数据").doWrite(rows);
        System.out.println("written: " + path + " rows=" + rows.size());
    }

    /** 供排障：打印一个 xlsx 的内容 */
    public static void dump(String path) {
        EasyExcel.read(path, new AnalysisEventListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                System.out.println("row " + context.readRowHolder().getRowIndex() + ": " + data);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
            }
        }).sheet().doRead();
    }
}
