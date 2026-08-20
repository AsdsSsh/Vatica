import MarkdownPreview from "@uiw/react-markdown-preview";
import "@uiw/react-markdown-preview/markdown.css";

/**
 * Markdown 渲染（迭代 6 I6-3；迭代 12 I12-1/I12-10；迭代 12.5 规范化）：
 * 直接使用 @uiw/react-markdown-preview，并按当前主题切换 data-color-mode。
 * 模型输出常见"###标题"（# 后无空格）这类不合法语法，渲染前统一规范化，
 * 否则界面会原样显示 ### 等符号。
 */
function normalizeMarkdown(source: string): string {
  return normalizeInlineNumberedList(
    normalizeInlineTables(
      source
        .replace(/^(#{1,6})(?=[^\s#])/gm, "$1 ")
        .replace(/^>(?=\S)/gm, "> ")
        .replace(/^(\d+\.)(?=\S)/gm, "$1 ")
        .replace(/^([-*+])(?=\S)/gm, "$1 "),
    ),
  );
}

/** 模型常把两列表格写在一行里：|路径|权限|---|---|...；拆成真正的 GFM 表格。 */
function normalizeInlineTables(source: string): string {
  const table = /\|([^|\n]+)\|([^|\n]+)\|\s*\|\s*---\s*\|\s*---\s*\|((?:\|[^|\n]+\|[^|\n]+\|)+)/g;
  return source.replace(table, (_whole, h1: string, h2: string, rows: string) => {
    const rowLines = rows.match(/\|([^|\n]+)\|([^|\n]+)\|/g) ?? [];
    const rebuilt = rowLines
      .map((row) => {
        const cells = row.match(/^\|([^|]+)\|([^|]+)\|$/);
        return cells ? `| ${cells[1].trim()} | ${cells[2].trim()} |` : "";
      })
      .filter(Boolean)
      .join("\n");
    return `\n\n| ${h1.trim()} | ${h2.trim()} |\n| --- | --- |\n${rebuilt}`;
  });
}

/** 模型把有序/无序列表也常写在一行里：1.📁xxx 2.📄yyy；识别 emoji 开头的条目并换行。 */
function normalizeInlineNumberedList(source: string): string {
  const ordered = /(^|[^\n])(\d+)\.[ \t]*([📁📄📊📎✉️📌🗂️🔗📝📅📋🧾])/g;
  const bullet = /(^|[^\n])([-*+])\s*([📁📄📊📎✉️📌🗂️🔗📝📅📋🧾])/g;
  return source
    .replace(ordered, (_m, prefix: string, num: string, emoji: string) =>
      `${prefix === "" ? "" : `${prefix}\n`}${num}. ${emoji}`)
    .replace(bullet, (_m, prefix: string, mark: string, emoji: string) =>
      `${prefix === "" ? "" : `${prefix}\n`}${mark} ${emoji}`);
}

export default function Markdown({ content, dark = false }: { content: string; dark?: boolean }) {
  return (
    <div data-color-mode={dark ? "dark" : "light"} className="vatica-markdown">
      <MarkdownPreview
        source={normalizeMarkdown(content)}
        style={{ backgroundColor: "transparent", color: "inherit", fontSize: 14 }}
        wrapperElement={{ "data-color-mode": dark ? "dark" : "light" }}
      />
    </div>
  );
}
