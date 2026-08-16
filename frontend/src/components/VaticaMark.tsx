import { useId } from "react";

/**
 * Vatica 品牌 logo（迭代 12 I12-1）：靛蓝→青蓝渐变圆角方块 + 白色 "V"。
 * 使用 useId 生成独立渐变 id，允许标题栏与空状态等多实例同时渲染不冲突。
 */
export default function VaticaMark({ size = 18 }: { size?: number }) {
  const gid = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      aria-hidden="true"
      style={{ flexShrink: 0, display: "block" }}
    >
      <defs>
        <linearGradient id={`vatica-mark-g-${gid}`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#F99C00" />
          <stop offset="1" stopColor="#FFB200" />
        </linearGradient>
      </defs>
      <rect width="64" height="64" rx="14" fill={`url(#vatica-mark-g-${gid})`} />
      <path
        d="M18 21 L32 47 L46 21"
        fill="none"
        stroke="#fff"
        strokeWidth="7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
