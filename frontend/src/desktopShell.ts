/**
 * 迭代 35：桌面系统集成——待办到期系统通知与深链接唤起。
 *
 * 壳职责边界：只做操作系统触达（通知、窗口），数据一律走现有带租户鉴权的
 * HTTP API；浏览器环境自动降级为无操作。通知聚合发送且按 (id, due) 去重，
 * 改期后的待办会重新提醒。
 */
import { fetchTodos, type TodoView } from "./api";
import { isTauriEnv } from "./directoryPicker";

const POLL_INTERVAL_MS = 5 * 60_000;
const INITIAL_DELAY_MS = 15_000;
const DEDUPE_STORAGE_KEY = "vatica.todoReminders.notified";

/** 纯函数：筛出需要提醒的待办——未完成且截止日不晚于今天（含逾期），按到期升序。 */
export function dueAlertTodos(todos: TodoView[], today: string): TodoView[] {
  return todos
    .filter((todo) => !todo.done && todo.due !== null && todo.due <= today)
    .sort((a, b) => (a.due ?? "").localeCompare(b.due ?? ""));
}

/** 纯函数：过滤掉已提醒过的项（id→due 记忆相同视为已提醒；改期后视为新提醒）。 */
export function filterNewlyDue(due: TodoView[], remembered: Record<string, string>): TodoView[] {
  return due.filter((todo) => remembered[todo.id] !== todo.due);
}

function loadRemembered(): Record<string, string> {
  try {
    const raw = localStorage.getItem(DEDUPE_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === "object" ? parsed as Record<string, string> : {};
  } catch {
    return {};
  }
}

function saveRemembered(map: Record<string, string>): void {
  try {
    localStorage.setItem(DEDUPE_STORAGE_KEY, JSON.stringify(map));
  } catch {
    // 存储不可用（隐私模式等）只影响去重，不影响本轮提醒
  }
}

async function sendDesktopNotification(title: string, body: string): Promise<boolean> {
  if (!isTauriEnv()) return false;
  try {
    const plugin = await import("@tauri-apps/plugin-notification");
    let granted = await plugin.isPermissionGranted();
    if (!granted) {
      granted = (await plugin.requestPermission()) === "granted";
    }
    if (!granted) return false;
    plugin.sendNotification({ title, body });
    return true;
  } catch {
    return false;
  }
}

/** 单轮检查：聚合提醒新到期的待办；后端离线/未授权时静默跳过。 */
export async function pollTodoRemindersOnce(): Promise<boolean> {
  let todos: TodoView[];
  try {
    todos = await fetchTodos();
  } catch {
    return false;
  }
  const today = new Date().toISOString().slice(0, 10);
  const fresh = filterNewlyDue(dueAlertTodos(todos, today), loadRemembered());
  if (fresh.length === 0) {
    return false;
  }
  const first = fresh[0];
  const firstDueLabel = first.due !== null && first.due < today ? `（已逾期 ${first.due}）` : "（今天到期）";
  const notified = await sendDesktopNotification(
    `待办提醒：${fresh.length} 项需要关注`,
    `最早：${first.title}${firstDueLabel}。打开 Vatica 查看待办清单。`,
  );
  if (notified) {
    const map = loadRemembered();
    for (const todo of fresh) {
      map[todo.id] = todo.due ?? "";
    }
    saveRemembered(map);
  }
  return notified;
}

let remindersStarted = false;

/** 启动待办提醒轮询（幂等；仅 Tauri 环境）。 */
export function startTodoReminders(): void {
  if (remindersStarted || !isTauriEnv() || typeof window === "undefined") return;
  remindersStarted = true;
  window.setTimeout(() => void pollTodoRemindersOnce(), INITIAL_DELAY_MS);
  window.setInterval(() => void pollTodoRemindersOnce(), POLL_INTERVAL_MS);
}

/** 深链接唤起：vatica://… 打开时显示并聚焦主窗口（v1 不做路由解析）。 */
export async function setupDeepLinkFocus(): Promise<void> {
  if (!isTauriEnv()) return;
  try {
    const { onOpenUrl } = await import("@tauri-apps/plugin-deep-link");
    const { getCurrentWindow } = await import("@tauri-apps/api/window");
    await onOpenUrl(async () => {
      const current = getCurrentWindow();
      await current.show();
      await current.setFocus();
    });
  } catch {
    // 插件不可用时忽略：深链接只影响唤起体验
  }
}
