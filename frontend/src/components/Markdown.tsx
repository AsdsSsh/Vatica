import MarkdownPreview from "@uiw/react-markdown-preview";
import "@uiw/react-markdown-preview/markdown.css";

/**
 * Markdown 渲染（迭代 6 I6-3；迭代 12 I12-1/I12-10）：
 * 直接使用 @uiw/react-markdown-preview（替换 md-editor 整包引入），
 * 并按当前主题切换 data-color-mode，代码块在暗色主题下不再刺眼。
 */
export default function Markdown({ content, dark = false }: { content: string; dark?: boolean }) {
  return (
    <div data-color-mode={dark ? "dark" : "light"} className="vatica-markdown">
      <MarkdownPreview
        source={content}
        style={{ backgroundColor: "transparent", color: "inherit", fontSize: 14 }}
        wrapperElement={{ "data-color-mode": dark ? "dark" : "light" }}
      />
    </div>
  );
}
