import { useEffect, useMemo, useState } from "react";
import { Tooltip } from "antd";
import {
  BorderOutlined,
  FundProjectionScreenOutlined,
  CloseOutlined,
  MinusOutlined,
} from "@ant-design/icons";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { useBackendStatus, type BackendStatus } from "../backendStatus";
import VaticaMark from "./VaticaMark";

/**
 * 全局标题栏（迭代 12 I12-1，U14）：自绘窗口边框替代 Windows 原生标题栏。
 * 整条为 Tauri 拖拽区；右侧窗口三键只在 Tauri 桌面环境显示（浏览器开发模式自动隐藏）。
 * 品牌 logo 旁的圆点 = 签名元素"呼吸状态灯"：后端在线常亮、连接中琥珀脉冲、
 * 离线变灰、Agent 工作时青蓝呼吸（含外环光环，全应用唯一的持续性动画）。
 */

function isTauriEnv(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

function statusText(status: BackendStatus, working: boolean): string {
  if (status === "offline") return "后端未连接（自动重试中）";
  if (status === "checking") return "正在连接后端…";
  return working ? "Agent 正在工作" : "后端服务在线";
}

export default function TitleBar({ working }: { working: boolean }) {
  const { status } = useBackendStatus();
  const [tauri] = useState(isTauriEnv);
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (!tauri) return;
    let disposed = false;
    const win = getCurrentWindow();
    win.isMaximized().then((v) => {
      if (!disposed) setMaximized(v);
    });
    let unlisten: (() => void) | undefined;
    win.onResized(() => {
      void win.isMaximized().then((v) => {
        if (!disposed) setMaximized(v);
      });
    }).then((fn) => {
      if (disposed) fn();
      else unlisten = fn;
    });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [tauri]);

  const dotClass = useMemo(() => {
    if (status === "offline") return "vatica-dot offline";
    if (status === "checking") return "vatica-dot checking";
    return working ? "vatica-dot working" : "vatica-dot online";
  }, [status, working]);

  async function minimize() {
    if (!tauri) return;
    await getCurrentWindow().minimize();
  }

  async function toggleMaximize() {
    if (!tauri) return;
    await getCurrentWindow().toggleMaximize();
  }

  async function close() {
    if (!tauri) return;
    await getCurrentWindow().close();
  }

  return (
    <header
      className="titlebar"
      data-tauri-drag-region
      onDoubleClick={(e) => {
        // 双击标题栏空白区 = 最大化/还原（窗口按钮区不参与）
        if (tauri && !(e.target as HTMLElement).closest(".titlebar-controls")) {
          void toggleMaximize();
        }
      }}
    >
      <div className="titlebar-brand" data-tauri-drag-region>
        <VaticaMark />
        <span className="titlebar-name">Vatica · 个人 AI 助理</span>
        <Tooltip title={statusText(status, working)}>
          <span className={dotClass} aria-label={statusText(status, working)} />
        </Tooltip>
      </div>
      <div className="titlebar-spacer" data-tauri-drag-region />
      <div className="titlebar-nav" onDoubleClick={(e) => e.stopPropagation()}>
        <Tooltip title="Agent 可观测性">
          <button
            type="button"
            className="titlebar-button"
            aria-label="打开 Agent 可观测性"
            onClick={() => { window.location.hash = "#/observability"; }}
          >
            <FundProjectionScreenOutlined />
          </button>
        </Tooltip>
      </div>
      {tauri && (
        <div className="titlebar-controls" onDoubleClick={(e) => e.stopPropagation()}>
          <button type="button" className="titlebar-button" aria-label="最小化" onClick={() => void minimize()}>
            <MinusOutlined />
          </button>
          <button
            type="button"
            className="titlebar-button"
            aria-label={maximized ? "还原" : "最大化"}
            onClick={() => void toggleMaximize()}
          >
            <BorderOutlined />
          </button>
          <button type="button" className="titlebar-button close" aria-label="关闭" onClick={() => void close()}>
            <CloseOutlined />
          </button>
        </div>
      )}
    </header>
  );
}
