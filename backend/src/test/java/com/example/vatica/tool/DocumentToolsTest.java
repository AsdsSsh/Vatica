package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.vatica.permission.TestFileSandbox;

/**
 * 文档工具真实 IO 单测（迭代 3）：生成后用 POI 读回校验真实内容，不 mock。
 * 覆盖：正常生成（Word 标题/章节/段落、Excel 表头/数值/文本单元格）、扩展名补全/拒绝、
 * 参数边界（空值/超限/非法 sheet 名/行列数不匹配）、白名单外路径拒绝（复用 PathSecurityGuard）。
 */
class DocumentToolsTest {

    @TempDir
    Path tempDir;

    DocumentTools documentTools;

    @BeforeEach
    void setUp() {
        documentTools = new DocumentTools(TestFileSandbox.policy(tempDir));
    }

    // ══════════════ Word ══════════════

    /** 生成 docx 并读回校验：标题、一级/二级标题、正文段落齐全 */
    @Test
    void createWordReport_writesTitleHeadingsAndParagraphs() throws IOException {
        String result = documentTools.createWordReport("第 33 周工作周报",
                "# 本周进展\n完成登录模块\n## 关键数据\n修复 5 个 bug\n",
                "周报.docx");

        assertThat(result).contains("已生成 Word 文档").contains("字节");
        assertThat(result).contains("章节标题 2 个").contains("正文段落 2 段");

        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(tempDir.resolve("周报.docx")))) {
            List<String> texts = doc.getParagraphs().stream()
                    .map(p -> p.getText().trim())
                    .filter(t -> !t.isEmpty())
                    .toList();
            assertThat(texts).containsExactly("第 33 周工作周报", "本周进展", "完成登录模块", "关键数据", "修复 5 个 bug");
        }
    }

    /** 省略扩展名时自动补 .docx */
    @Test
    void createWordReport_appendsExtensionWhenMissing() {
        documentTools.createWordReport("标题", "# 章节\n内容", "周报");

        assertThat(tempDir.resolve("周报.docx")).exists();
    }

    /** 扩展名不符 → 指引错误 */
    @Test
    void createWordReport_wrongExtension_throws() {
        assertThatThrownBy(() -> documentTools.createWordReport("标题", "内容", "周报.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扩展名");
    }

    /** 空标题 → 报错 */
    @Test
    void createWordReport_blankTitle_throws() {
        assertThatThrownBy(() -> documentTools.createWordReport("  ", "# 章节\n内容", "周报.docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
    }

    /** 空内容 → 报错 */
    @Test
    void createWordReport_blankSections_throws() {
        assertThatThrownBy(() -> documentTools.createWordReport("标题", "\n  \n", "周报.docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容");
    }

    /** 内容超过字符上限 → 报错（护栏：防上下文/内存失控） */
    @Test
    void createWordReport_contentOverLimit_throws() {
        String huge = "a".repeat(DocumentTools.MAX_WORD_CHARS + 1);
        assertThatThrownBy(() -> documentTools.createWordReport("标题", huge, "周报.docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    /** 白名单外路径（目录穿越）→ 拒绝 */
    @Test
    void createWordReport_traversalRejected() {
        assertThatThrownBy(() -> documentTools.createWordReport("标题", "内容", "../evil.docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权目录");
    }

    // ══════════════ Excel ══════════════

    /** 生成 xlsx 并读回校验：sheet 名、表头、严格数字写数值单元格、其余写文本单元格 */
    @Test
    void createExcelStats_writesSheetHeaderAndTypedCells() throws IOException {
        String result = documentTools.createExcelStats("周报统计",
                "日期,事项,工时,编号",
                "周一,登录模块,8.5,001\n周二,订单查询,6,007",
                "统计表.xlsx");

        assertThat(result).contains("已生成 Excel 文档").contains("2 行 × 4 列");

        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(tempDir.resolve("统计表.xlsx")))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = wb.getSheet("周报统计");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("日期");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("编号");
            assertThat(wb.getFontAt(header.getCell(0).getCellStyle().getFontIndexAsInt()).getBold()).isTrue();

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row1.getCell(2).getNumericCellValue()).isEqualTo(8.5);
            // "001" 有前导零 → 必须保持文本，不能变 1
            assertThat(row1.getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row1.getCell(3).getStringCellValue()).isEqualTo("001");
        }
    }

    /** 省略扩展名自动补 .xlsx */
    @Test
    void createExcelStats_appendsExtensionWhenMissing() {
        documentTools.createExcelStats("统计", "a,b", "1,2", "统计表");

        assertThat(tempDir.resolve("统计表.xlsx")).exists();
    }

    /** 扩展名不符 → 报错 */
    @Test
    void createExcelStats_wrongExtension_throws() {
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "a", "1", "统计表.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扩展名");
    }

    /** sheet 名超过 31 字符 → 报错 */
    @Test
    void createExcelStats_sheetNameTooLong_throws() {
        assertThatThrownBy(() -> documentTools.createExcelStats("名".repeat(32), "a", "1", "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("31");
    }

    /** sheet 名含非法字符 → 报错 */
    @Test
    void createExcelStats_sheetNameIllegalChars_throws() {
        assertThatThrownBy(() -> documentTools.createExcelStats("周报/统计", "a", "1", "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作表名");
    }

    /** 空列名 → 报错 */
    @Test
    void createExcelStats_blankHeaders_throws() {
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "  ", "1", "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列名");
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "a,,b", "1,2,3", "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列名");
    }

    /** 数据行列数多于列名数 → 报错（多出数据不静默丢弃） */
    @Test
    void createExcelStats_moreCellsThanHeaders_throws() {
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "a,b", "1,2,3", "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列数");
    }

    /** 数据行列数少于列名数 → 空单元格补齐（宽松策略） */
    @Test
    void createExcelStats_fewerCellsPaddedWithEmpty() throws IOException {
        documentTools.createExcelStats("统计", "a,b,c", "1,2\n3,4,5", "统计表.xlsx");

        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(tempDir.resolve("统计表.xlsx")))) {
            Row row1 = wb.getSheet("统计").getRow(1);
            assertThat(row1.getCell(2).getStringCellValue()).isEmpty();
        }
    }

    /** 空数据行 → 报错 */
    @Test
    void createExcelStats_blankRows_throws() {
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "a", "  \n \n", "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据行");
    }

    /** 数据行数超过上限 → 报错（护栏） */
    @Test
    void createExcelStats_rowsOverLimit_throws() {
        String tooMany = "x\n".repeat(DocumentTools.MAX_EXCEL_ROWS + 1).trim();
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "a", tooMany, "统计表.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    /** 白名单外路径 → 拒绝 */
    @Test
    void createExcelStats_traversalRejected() {
        assertThatThrownBy(() -> documentTools.createExcelStats("统计", "a", "1", "../evil.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权目录");
    }

    /** 严格数字规则边界：整数/小数/负数 → 数值；前导零/日期/科学计数法 → 文本 */
    @Test
    void createExcelStats_strictNumberBoundary() throws IOException {
        documentTools.createExcelStats("规则",
                "值",
                "8\n-3.14\n0.5\n0\n001\n2026-01-01\n1e3\n12.",
                "边界.xlsx");

        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(tempDir.resolve("边界.xlsx")))) {
            Sheet sheet = wb.getSheet("规则");
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(2).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(3).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.STRING); // 001 前导零
            assertThat(sheet.getRow(6).getCell(0).getCellType()).isEqualTo(CellType.STRING); // 日期
            assertThat(sheet.getRow(7).getCell(0).getCellType()).isEqualTo(CellType.STRING); // 科学计数法
            assertThat(sheet.getRow(8).getCell(0).getCellType()).isEqualTo(CellType.STRING); // 尾随小数点
        }
    }
}
