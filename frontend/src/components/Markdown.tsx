import MarkdownPreview from "@uiw/react-markdown-preview";
import "@uiw/react-markdown-preview/markdown.css";

/**
 * Markdown 渲染（迭代 6 I6-3；迭代 12 I12-1/I12-10；迭代 12.5 规范化）：
 * 直接使用 @uiw/react-markdown-preview，并按当前主题切换 data-color-mode。
 * 模型输出常见"###标题"（# 后无空格）这类不合法语法，渲染前统一规范化，
 * 否则界面会原样显示 ### 等符号。
 */
function normalizeMarkdown(source: string): string {
  return source
    .replace(/^(#{1,6})(?=\S)/gm, "$1 ")
    .replace(/^>(?=\S)/gm, "> ")
    .replace(/^(\d+\.)(?=\S)/gm, "$1 ")
    .replace(/^([-*+])(?=\S)/gm, "$1 ");
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
