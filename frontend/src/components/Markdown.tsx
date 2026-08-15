import MDEditor from "@uiw/react-md-editor";

/**
 * Markdown 渲染（迭代 6 I6-3）：@uiw/react-md-editor 的预览组件，
 * 用于渲染助手消息（流式打字机内容的最终形态是 Markdown）。
 */
export default function Markdown({ content }: { content: string }) {
  return (
    <div data-color-mode="light">
      <MDEditor.Markdown
        source={content}
        style={{ backgroundColor: "transparent", color: "inherit", fontSize: 14 }}
      />
    </div>
  );
}
