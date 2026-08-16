import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { getApiBase } from "./api";

/**
 * 后端连接状态（迭代 12 I12-2）：GET / 探活 + 指数退避重试。
 * 打包模式后端 sidecar 启动较慢时，界面先显示离线横幅，服务就绪后自动转绿，
 * 并让模型/任务列表等数据随状态恢复自动刷新。
 */
export type BackendStatus = "checking" | "online" | "offline";

interface BackendStatusContextValue {
  status: BackendStatus;
  online: boolean;
  /** 手动立即重试一次。 */
  refresh: () => void;
}

const BackendStatusContext = createContext<BackendStatusContextValue | null>(null);

const ONLINE_INTERVAL_MS = 30_000;
const OFFLINE_BASE_MS = 1_000;
const OFFLINE_MAX_MS = 30_000;
const REQUEST_TIMEOUT_MS = 4_000;

export function BackendStatusProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<BackendStatus>("checking");
  const timerRef = useRef<number | null>(null);
  const checkingRef = useRef(false);
  const attemptsRef = useRef(0);

  const clearTimer = useCallback(() => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const schedule = useCallback(
    (delayMs: number) => {
      clearTimer();
      timerRef.current = window.setTimeout(() => void checkRef.current(), delayMs);
    },
    [clearTimer],
  );
  const checkRef = useRef<() => void>(() => {});

  const check = useCallback(async () => {
    if (checkingRef.current) return;
    checkingRef.current = true;
    clearTimer();
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const res = await fetch(`${getApiBase()}/`, {
        signal: controller.signal,
        cache: "no-store",
      });
      if (res.ok) {
        attemptsRef.current = 0;
        setStatus("online");
        schedule(ONLINE_INTERVAL_MS);
      } else {
        attemptsRef.current += 1;
        setStatus("offline");
        schedule(Math.min(OFFLINE_BASE_MS * 2 ** (attemptsRef.current - 1), OFFLINE_MAX_MS));
      }
    } catch {
      attemptsRef.current += 1;
      setStatus("offline");
      schedule(Math.min(OFFLINE_BASE_MS * 2 ** (attemptsRef.current - 1), OFFLINE_MAX_MS));
    } finally {
      window.clearTimeout(timeout);
      checkingRef.current = false;
    }
  }, [clearTimer, schedule]);

  checkRef.current = check;

  useEffect(() => {
    check();
    return clearTimer;
  }, [check, clearTimer]);

  const value = useMemo(
    () => ({ status, online: status === "online", refresh: () => void check() }),
    [status, check],
  );

  return (
    <BackendStatusContext.Provider value={value}>{children}</BackendStatusContext.Provider>
  );
}

export function useBackendStatus(): BackendStatusContextValue {
  const ctx = useContext(BackendStatusContext);
  if (!ctx) throw new Error("useBackendStatus 必须在 BackendStatusProvider 内使用");
  return ctx;
}
