package com.example.vatica.tool;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import com.example.vatica.permission.FileSandboxPolicy;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 文档生成工具（create_word_report / create_excel_stats）——迭代 3：Apache POI 交付文档成品。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>Word 用 XWPFDocument</b>（整文档在内存，配合内容上限防内存失控）；<b>Excel 用 SXSSFWorkbook</b>
 *       （流式写出、窗口内仅保留 100 行，万行级数据内存恒定——面试点"POI 大文档内存优化"）</li>
 *   <li><b>结构约定而非自由格式</b>：模型按行式排版约定传内容（# 标题 / ## 子标题 / 正文行），
 *       工具做确定性排版——与"数据类事实只从工具返回值取"的幻觉控制思路同源</li>
 *   <li><b>数字规则显式化</b>：Excel 中仅严格数字模式（无前导零/无科学计数法）写为数值单元格，
 *       其余一律文本单元格——避免"001"编号、"2026-01-01"日期被静默改写为数字</li>
 *   <li>落盘目录复用文件工具白名单（同一 workspace-dir + 同一 PathSecurityGuard 安全边界）</li>
 * </ul>
 *
 * <p>错误约定与 FileTools 一致：参数非法抛 {@link IllegalArgumentException}（message 是给模型的指引文案），
 * IO 异常包装为 {@link IllegalStateException}（严禁裸抛 IOException 中断整次会话）。
 */
public final class DocumentTools {

    /** 单个 Word 文档内容总字符数上限（防上下文/内存失控，周报体量远低于此值）。 */
    static final int MAX_WORD_CHARS = 100_000;
    /** 单个 Excel 数据行数上限（SXSSF 可扛更大规模，此护栏防模型超长输出烧资源）。 */
    static final int MAX_EXCEL_ROWS = 1_000;
    /** 单个 Excel 内容总字符数上限。 */
    static final int MAX_EXCEL_CHARS = 100_000;

    /** 严格数字模式：可选负号 + 整数/小数，无前导零、无指数、无千分位。 */
    private static final Pattern STRICT_NUMBER = Pattern.compile("-?(0|0\\.\\d+|[1-9]\\d*(\\.\\d+)?)");

    private static final String WORD_EXT = ".docx";
    private static final String EXCEL_EXT = ".xlsx";
    /** Excel 工作表名非法字符（POI 约束，超长另行限制）。 */
    private static final Pattern ILLEGAL_SHEET_CHARS = Pattern.compile("[\\\\/\\[\\]*?:]");

    private final FileSandboxPolicy sandboxPolicy;

    public DocumentTools(FileToolProperties props, FileSandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy;
    }

    // ══════════════════════════════ I3-1 Word 周报 ══════════════════════════════

    @Tool(name = "create_word_report", description = "生成一份 Word（.docx）周报/报告并保存到已授权工作目录。"
            + "sections 参数使用行式排版约定：以 \"# \" 开头的行是一级标题，以 \"## \" 开头的是二级标题，"
            + "其余非空行是正文段落。适用于把 read_file 收集到的资料整理成交付文档。"
            + "文件名省略扩展名时自动补 .docx。生成后返回文件路径、大小与章节数。")
    public String createWordReport(
            @ToolParam(description = "文档标题（显示在文档最顶部，如\"2026 年第 33 周工作周报\"）", required = true) String title,
            @ToolParam(description = "文档内容，行式排版：\"# \"开头=一级标题，\"## \"开头=二级标题，其余行=正文段落；"
                    + "空行分隔段落。正文请用规范中文", required = true) String sections,
            @ToolParam(description = "保存的文件名，如\"本周周报.docx\"或\"周报\"；必须以 .docx 结尾或省略扩展名", required = true) String filename) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("操作失败：title（文档标题）不能为空。");
        }
        if (sections == null || sections.isBlank()) {
            throw new IllegalArgumentException("操作失败：sections（文档内容）不能为空。请基于已收集的资料撰写内容。");
        }
        if (sections.length() > MAX_WORD_CHARS) {
            throw new IllegalArgumentException("操作失败：文档内容（" + sections.length() + " 字符）超过上限（"
                    + MAX_WORD_CHARS + " 字符）。请精简内容或拆分为多份文档。");
        }
        String target = normalizeFilename(filename, WORD_EXT);
        Path path = sandboxPolicy.resolveForWrite(target, "create_word_report 需要写入该路径");

        int headingCount = 0;
        int paragraphCount = 0;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph titlePara = doc.createParagraph();
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title.trim());
            styleRun(titleRun, 20, true);

            for (String rawLine : sections.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                if (line.startsWith("## ")) {
                    run.setText(line.substring(3));
                    styleRun(run, 14, true);
                    headingCount++;
                } else if (line.startsWith("# ")) {
                    run.setText(line.substring(2));
                    styleRun(run, 16, true);
                    headingCount++;
                } else {
                    run.setText(line);
                    styleRun(run, 12, false);
                    paragraphCount++;
                }
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                doc.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：生成 Word 文档失败。" + e.getMessage(), e);
        }
        return "已生成 Word 文档：" + path + "（" + sizeOf(path)
                + "，章节标题 " + headingCount + " 个，正文段落 " + paragraphCount + " 段）";
    }

    // ══════════════════════════════ I3-2 Excel 统计 ══════════════════════════════

    @Tool(name = "create_excel_stats", description = "生成 Excel（.xlsx）统计表并保存到已授权工作目录。"
            + "headers 为逗号分隔的列名（如\"日期,事项,状态\"）；rows 为数据行，每行占一行、列值用逗号分隔"
            + "（如\"周一,完成登录模块,已完成\"），行内不要换行。"
            + "纯数字（无前导零、无科学计数法）会写为数值单元格，其余一律写为文本单元格。"
            + "适用于把 list_files / read_file 收集到的统计结果整理成可交付表格。文件名省略扩展名时自动补 .xlsx。")
    public String createExcelStats(
            @ToolParam(description = "工作表名（显示为底部 sheet 标签），如\"周报统计\"；限 31 个字符，不能含 \\ / [ ] * ? : 字符",
                    required = true) String sheetName,
            @ToolParam(description = "逗号分隔的列名，如\"日期,事项,状态\"，列数 1-30", required = true) String headers,
            @ToolParam(description = "数据行文本，每行一条记录、列值用逗号分隔；多行之间用换行分隔", required = true) String rows,
            @ToolParam(description = "保存的文件名，如\"统计表.xlsx\"或\"统计表\"；必须以 .xlsx 结尾或省略扩展名", required = true) String filename) {
        if (sheetName == null || sheetName.isBlank()) {
            throw new IllegalArgumentException("操作失败：sheetName（工作表名）不能为空。");
        }
        String sheet = sheetName.trim();
        if (sheet.length() > 31) {
            throw new IllegalArgumentException("操作失败：工作表名（" + sheet.length() + " 字符）超过 31 字符上限，请缩短。");
        }
        if (ILLEGAL_SHEET_CHARS.matcher(sheet).find()) {
            throw new IllegalArgumentException("操作失败：工作表名不能包含 \\ / [ ] * ? : 字符，请改用其他名称。");
        }
        List<String> headerCells = splitLine(headers, "headers（列名）");
        if (headerCells.isEmpty()) {
            throw new IllegalArgumentException("操作失败：headers（列名）不能为空。");
        }
        if (headerCells.size() > 30) {
            throw new IllegalArgumentException("操作失败：列数（" + headerCells.size() + "）超过 30 列上限。");
        }
        if (headerCells.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("操作失败：列名不能为空。请检查逗号分隔是否有多余逗号。");
        }
        if (rows == null || rows.isBlank()) {
            throw new IllegalArgumentException("操作失败：rows（数据行）不能为空。请基于已收集的数据整理表格内容。");
        }
        if (rows.length() > MAX_EXCEL_CHARS) {
            throw new IllegalArgumentException("操作失败：表格内容（" + rows.length() + " 字符）超过上限（"
                    + MAX_EXCEL_CHARS + " 字符）。请精简或拆分。");
        }
        List<String[]> dataRows = new ArrayList<>();
        for (String rawLine : rows.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = splitLine(line, "rows（数据行）");
            if (cells.size() > headerCells.size()) {
                throw new IllegalArgumentException("操作失败：数据行列数（" + cells.size()
                        + "）超过列名数（" + headerCells.size() + "）。请检查行内是否多出逗号；行内不要出现换行。");
            }
            String[] rowCells = new String[headerCells.size()];
            for (int i = 0; i < cells.size(); i++) {
                rowCells[i] = cells.get(i);
            }
            for (int i = cells.size(); i < headerCells.size(); i++) {
                rowCells[i] = "";
            }
            dataRows.add(rowCells);
        }
        if (dataRows.isEmpty()) {
            throw new IllegalArgumentException("操作失败：rows（数据行）没有有效数据行。");
        }
        if (dataRows.size() > MAX_EXCEL_ROWS) {
            throw new IllegalArgumentException("操作失败：数据行数（" + dataRows.size() + "）超过上限（"
                    + MAX_EXCEL_ROWS + " 行）。请精简数据或拆分表格。");
        }
        String target = normalizeFilename(filename, EXCEL_EXT);
        Path path = sandboxPolicy.resolveForWrite(target, "create_excel_stats 需要写入该路径");

        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            Sheet sh = wb.createSheet(sheet);

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sh.createRow(0);
            for (int i = 0; i < headerCells.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headerCells.get(i));
                cell.setCellStyle(headerStyle);
            }
            for (int r = 0; r < dataRows.size(); r++) {
                Row row = sh.createRow(r + 1);
                String[] cells = dataRows.get(r);
                for (int c = 0; c < cells.length; c++) {
                    String value = cells[c];
                    Cell cell = row.createCell(c);
                    if (STRICT_NUMBER.matcher(value).matches()) {
                        cell.setCellValue(Double.parseDouble(value));
                    } else {
                        cell.setCellValue(value);
                    }
                }
            }
            // 列宽按内容长度手动设置（确定性、无额外内存）；SXSSF 的 autoSizeColumn 依赖列跟踪有内存代价，不用
            for (int c = 0; c < headerCells.size(); c++) {
                int maxLen = headerCells.get(c).length();
                for (String[] rowCells : dataRows) {
                    maxLen = Math.max(maxLen, rowCells[c].length());
                }
                sh.setColumnWidth(c, Math.min(maxLen, 50) * 256 + 512);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                wb.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：生成 Excel 文档失败。" + e.getMessage(), e);
        }
        return "已生成 Excel 文档：" + path + "（" + sizeOf(path)
                + "，" + dataRows.size() + " 行 × " + headerCells.size() + " 列）";
    }

    // ══════════════════════════════ 内部工具方法 ══════════════════════════════

    /** 校验/补全扩展名：无扩展名自动补；扩展名不符直接拒绝（指引模型自纠）。 */
    private static String normalizeFilename(String filename, String expectedExt) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("操作失败：文件名不能为空。");
        }
        String name = filename.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        String base = name.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot < 0) {
            return name + expectedExt;
        }
        if (!base.substring(dot).toLowerCase(Locale.ROOT).equals(expectedExt)) {
            throw new IllegalArgumentException("操作失败：文件扩展名必须为 " + expectedExt
                    + "（或省略扩展名自动补充）。当前为：" + base.substring(dot));
        }
        return name;
    }

    /** 逗号切分一行：空白单元格显式保留（"a,,c" → 3 列），首尾单元格保留内容。 */
    private static List<String> splitLine(String line, String what) {
        List<String> cells = new ArrayList<>();
        for (String part : line.split(",", -1)) {
            cells.add(part.trim());
        }
        return cells;
    }

    /** 设置字号/加粗，并显式指定中文字体（微软雅黑），避免中文在低配机器上回退为默认字体。 */
    private static void styleRun(XWPFRun run, int size, boolean bold) {
        run.setFontSize(size);
        run.setBold(bold);
        run.setFontFamily("Microsoft YaHei"); // 同时设置 ascii/hAnsi；随后补 eastAsia
        // POI 5.5 采用 Ecma-376 第 5 版 schema：rPr 的 rFonts 是列表结构（setFontFamily 已创建首个元素）
        var rPr = run.getCTR().getRPr();
        if (rPr.sizeOfRFontsArray() > 0) {
            rPr.getRFontsArray(0).setEastAsia("微软雅黑");
        } else {
            rPr.addNewRFonts().setEastAsia("微软雅黑");
        }
    }

    private static String sizeOf(Path path) {
        try {
            return Files.size(path) + " 字节";
        } catch (IOException e) {
            return "大小未知";
        }
    }
}
