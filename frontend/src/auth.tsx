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
import { fetchCurrentUser, getAuthToken, isAuthExpiredError, setAuthToken, type CurrentUserView } from "./api";
import { useBackendStatus } from "./backendStatus";

/**
 * 统一登录态（迭代 14.5 I14.5-2）：
 * - loading：启动/切换账号时正在向服务端确认身份；
 * - anonymous：鉴权开启且未登录（或 Token 已失效被统一清理）；
 * - authenticated：鉴权开启且 GET /api/auth/me 返回真实账号；
 * - local：后端未开启鉴权（vatica.auth.enabled=false）的本地学习模式。
 *
 * 身份事实源只来自服务端 /api/auth/me，不再以 localStorage 是否存在 Token 判断登录。
 */

export type AuthStatus = "loading" | "anonymous" | "authenticated" | "local";

export interface AuthState {
  status: AuthStatus;
  user: CurrentUserView | null;
}

interface AuthContextValue extends AuthState {
  refresh: () => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const { online } = useBackendStatus();
  const [auth, setAuth] = useState<AuthState>({ status: "loading", user: null });
  // 只采纳最新一次确认结果，避免慢返回覆盖新账号状态
  const generationRef = useRef(0);

  const refresh = useCallback(async () => {
    const generation = ++generationRef.current;
    try {
      const user = await fetchCurrentUser();
      if (generation !== generationRef.current) return;
      setAuth(
        user.role === "LOCAL"
          ? { status: "local", user }
          : { status: "authenticated", user },
      );
    } catch (e) {
      if (generation !== generationRef.current) return;
      if (isAuthExpiredError(e) || (online && !getAuthToken())) {
        // 401 或在线状态下无 Token 才是匿名；后端离线期间保持 loading，避免伪装成登录问题
        setAuth({ status: "anonymous", user: null });
      }
      // 其余情况（后端离线/5xx）保持原状态，后端恢复在线后自动重试
    }
  }, []);

  const logout = useCallback(() => {
    generationRef.current += 1;
    setAuth({ status: "anonymous", user: null });
    setAuthToken(null);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (online) void refresh();
  }, [online, refresh]);

  useEffect(() => {
    const onAuthChanged = () => void refresh();
    window.addEventListener("vatica-auth-changed", onAuthChanged);
    return () => window.removeEventListener("vatica-auth-changed", onAuthChanged);
  }, [refresh]);

  const value = useMemo<AuthContextValue>(
    () => ({ ...auth, refresh, logout }),
    [auth, refresh, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth 必须在 AuthProvider 内使用");
  return ctx;
}
