/**
 * 迭代 14.5 headless Chrome 冒烟（无需额外 npm 依赖）：
 * - 通过 Chrome DevTools Protocol 驱动已启动的 headless Chrome（端口 9222）
 * - 覆盖登录态：匿名表单 / 注册回显 / 重开弹窗 / 退出登录 / 双账号切换数据隔离 /
 *   Token 失效统一清理 / 鉴权关闭时的本地学习模式
 *
 * 运行前提：
 *   chrome --headless=new --remote-debugging-port=9222 --user-data-dir=<tmp> about:blank
 *   vite dev 已跑在 http://localhost:1420；后端已跑在 http://localhost:8080
 *   node frontend/smoke-auth.mjs          # 鉴权开启的云账号流程
 *   node frontend/smoke-auth.mjs --local  # 鉴权关闭的本地学习模式
 */
import assert from "node:assert";

const CHROME_DEBUG = process.env.CHROME_DEBUG ?? "http://127.0.0.1:9222";
const APP_URL = process.env.APP_URL ?? "http://localhost:1420";
const API_BASE = process.env.API_BASE ?? "http://localhost:8080";
const LOCAL_MODE = process.argv.includes("--local");
const RUN_ID = Date.now().toString(36);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class Cdp {
  constructor(url) {
    this.url = url;
    this.nextId = 1;
    this.pending = new Map();
    this.ws = null;
  }

  async connect() {
    this.ws = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      this.ws.addEventListener("open", resolve, { once: true });
      this.ws.addEventListener("error", reject, { once: true });
    });
    this.ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      }
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  async eval(expression) {
    const result = await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (result.exceptionDetails) {
      throw new Error(`页面执行失败：${result.exceptionDetails.text}`);
    }
    return result.result?.value;
  }

  async waitFor(expression, description, timeoutMs = 15000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      try {
        if (await this.eval(expression)) return;
      } catch {
        // 页面切换瞬间的评估失败忽略
      }
      await sleep(150);
    }
    throw new Error(`等待超时：${description}`);
  }

  close() {
    try {
      this.ws?.close();
    } catch {
      // 忽略
    }
  }
}

async function createTarget(url) {
  let target;
  try {
    const res = await fetch(`${CHROME_DEBUG}/json/new?${encodeURIComponent(url)}`, { method: "PUT" });
    target = await res.json();
  } catch {
    // 某些版本不允许 /json/new，退化为复用现有页面
  }
  if (!target?.webSocketDebuggerUrl) {
    const list = await (await fetch(`${CHROME_DEBUG}/json/list`)).json();
    target = list.find((item) => item.type === "page") ?? list[0];
  }
  if (!target?.webSocketDebuggerUrl) throw new Error("未找到 Chrome 调试目标");
  return target;
}

async function api(path, { method = "GET", token, body } = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API ${method} ${path} 失败 ${res.status}：${text}`);
  }
  return res;
}

async function waitText(cdp, text, timeout = 15000) {
  await cdp.waitFor(`document.body.innerText.includes(${JSON.stringify(text)})`, `页面出现文案「${text}」`, timeout);
}

async function waitNoText(cdp, text, timeout = 15000) {
  await cdp.waitFor(`!document.body.innerText.includes(${JSON.stringify(text)})`, `页面文案「${text}」消失`, timeout);
}

async function click(cdp, expression, description) {
  const ok = await cdp.eval(`(() => { const el = ${expression}; if (!el) return false; el.click(); return true; })()`);
  assert.ok(ok, `未找到可点击元素：${description}`);
}

async function typeInto(cdp, selector, value) {
  const ok = await cdp.eval(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return false;
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
    setter.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  })()`);
  assert.ok(ok, `未找到输入框：${selector}`);
}

async function clickModalButton(cdp, text) {
  await click(
    cdp,
    `[...document.querySelectorAll(".ant-modal button")].find((b) => b.textContent.replace(/\\s+/g, "").trim() === ${JSON.stringify(text)})`,
    `弹窗按钮「${text}」`,
  );
}

async function clickModalTab(cdp, text) {
  await click(
    cdp,
    `[...document.querySelectorAll(".ant-modal .ant-tabs-tab")].find((el) => el.textContent.trim() === ${JSON.stringify(text)})`,
    `账号页标签「${text}」`,
  );
}

async function typeVisible(cdp, autocomplete, value, description) {
  const ok = await cdp.eval(`(() => {
    const el = [...document.querySelectorAll(".ant-modal input")]
      .find((node) => node.autocomplete === ${JSON.stringify(autocomplete)} && node.offsetParent !== null);
    if (!el) return false;
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
    setter.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  })()`);
  assert.ok(ok, `未找到可见输入框：${description}`);
}

async function typeVisibleByPlaceholder(cdp, placeholder, value, description) {
  const ok = await cdp.eval(`(() => {
    const el = [...document.querySelectorAll(".ant-modal input")]
      .find((node) => node.offsetParent !== null && node.placeholder === ${JSON.stringify(placeholder)});
    if (!el) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    setter.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  })()`);
  assert.ok(ok, `未找到可见输入框：${description}`);
}

async function clickSelectorByMouse(cdp, selector, description) {
  const rect = await cdp.eval(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  })()`);
  assert.ok(rect, `未找到元素：${description}`);
  await cdp.send("Input.dispatchMouseEvent", { type: "mousePressed", x: rect.x, y: rect.y, button: "left", clickCount: 1 });
  await cdp.send("Input.dispatchMouseEvent", { type: "mouseReleased", x: rect.x, y: rect.y, button: "left", clickCount: 1 });
}

async function registerViaUi(cdp, username, orgName) {
  await clickModalTab(cdp, "注册");
  await cdp.waitFor(
    `[...document.querySelectorAll(".ant-modal input[autocomplete='new-password']")].some((el) => el.offsetParent !== null)`,
    "注册表单出现",
  );
  await typeVisible(cdp, "username", username, "注册用户名");
  await typeVisible(cdp, "new-password", "secret123", "注册密码");
  await typeVisibleByPlaceholder(cdp, "我的组织", orgName, "组织名");
  await clickModalButton(cdp, "注册");
  await waitText(cdp, "退出登录");
  await waitText(cdp, username);
}

async function logoutViaUi(cdp) {
  await clickModalButton(cdp, "退出登录");
  await cdp.waitFor(`localStorage.getItem("vatica.authToken") === null`, "Token 被清除");
  await cdp.waitFor(
    `[...document.querySelectorAll(".ant-modal input[autocomplete='current-password']")].some((el) => el.offsetParent !== null)`,
    "退出后回到登录表单",
  );
}

async function waitOnline(cdp) {
  await cdp.waitFor(
    `document.body.innerText.includes("后端未连接") === false`,
    "后端在线横幅消失",
    30000,
  );
}

const cdp = new Cdp((await createTarget("about:blank")).webSocketDebuggerUrl);
await cdp.connect();
await cdp.send("Page.enable");
await cdp.send("Runtime.enable");
await cdp.send("Page.navigate", { url: APP_URL });
await cdp.waitFor(`document.readyState === "complete"`, "页面加载完成");
// 每次运行从干净的本机状态开始（避免上一次运行残留的 Token/会话缓存影响断言）
await cdp.eval(`localStorage.clear()`);
await cdp.send("Page.reload");
await cdp.waitFor(`document.readyState === "complete"`, "页面重载完成");
await cdp.waitFor(`!!document.querySelector("[aria-label='账号']")`, "账号按钮出现");

let passed = 0;
function ok(condition, label) {
  assert.ok(condition, label);
  passed += 1;
  console.log(`  ✔ ${label}`);
}

if (LOCAL_MODE) {
  await waitOnline(cdp);
  await click(cdp, `document.querySelector("[aria-label='账号']")`, "账号按钮");
  await waitText(cdp, "本地学习模式");
  ok(!(await cdp.eval(`document.body.innerText.includes("退出登录")`)), "本地模式不显示退出登录");
  ok(await cdp.eval(`localStorage.getItem("vatica.authToken") === null`), "本地模式无需 Token");
  console.log(`\n本地模式冒烟通过（${passed} 项断言）`);
  cdp.close();
  process.exit(0);
}

// ═══ 鉴权开启：匿名 → 注册 A → 回显/重开 → 退出 → 注册 B → 数据隔离 → 401 收口 ═══

await waitOnline(cdp);
await click(cdp, `document.querySelector("[aria-label='账号']")`, "账号按钮");
await waitText(cdp, "用户名");
ok(await cdp.eval(`document.body.innerText.includes("注册")`), "未登录时显示登录/注册表单");

const userA = `alice_${RUN_ID}`;
const userB = `bob_${RUN_ID}`;
await registerViaUi(cdp, userA, "团队A");
const tokenA = await cdp.eval(`localStorage.getItem("vatica.authToken")`);
ok(typeof tokenA === "string" && tokenA.length > 20, "注册 A 后 Token 已保存");
ok(
  await cdp.eval(`["平台管理员", "组织管理员", "成员"].some((r) => document.body.innerText.includes(r))`),
  "注册 A 后回显角色",
);
ok(await cdp.eval(`document.body.innerText.includes("Token 到期")`), "注册 A 后回显 Token 到期信息");

const meA = await (await api("/api/auth/me", { token: tokenA })).json();
ok(
  meA.username === userA &&
    ["PLATFORM_ADMIN", "ORG_ADMIN", "MEMBER"].includes(meA.role) &&
    !!meA.expiresAt,
  "GET /api/auth/me 返回 A 身份与到期时间",
);

await clickModalButton(cdp, "完成");
await click(cdp, `document.querySelector("[aria-label='账号']")`, "账号按钮");
await waitText(cdp, "退出登录");
ok(
  (await cdp.eval(`document.body.innerText.includes(${JSON.stringify(userA)})`)) &&
    !(await cdp.eval(`[...document.querySelectorAll("input[autocomplete='current-password']")].some((el) => el.offsetParent !== null)`)),
  "重开账号弹窗仍显示 A 登录态而非登录表单",
);

// A 的会话与用户模型写进服务端，用于双账号隔离断言
const sessionA = crypto.randomUUID();
await api(`/api/sessions/${sessionA}`, {
  method: "PUT",
  token: tokenA,
  body: { title: "A会话-隔离检查" },
});
await api("/api/models/user-slots", {
  method: "POST",
  token: tokenA,
  body: {
    name: "A模型-隔离检查", protocol: "openai", baseUrl: "https://api.example.com",
    model: "a-model", temperature: 0.7, enabled: true,
    credentialMode: "EPHEMERAL", apiKey: "sk-a",
  },
});

await cdp.send("Page.reload");
await cdp.waitFor(`document.readyState === "complete"`, "页面重载完成");
await waitOnline(cdp);
await waitText(cdp, "A会话-隔离检查");
ok(true, "A 登录后左栏只显示 A 自己的会话");

await click(cdp, `document.querySelector("[aria-label='账号']")`, "账号按钮");
await waitText(cdp, "退出登录");
await logoutViaUi(cdp);
await waitNoText(cdp, "A会话-隔离检查");
ok(true, "退出 A 后页面不再显示 A 会话");

await registerViaUi(cdp, userB, "团队B");
const tokenB = await cdp.eval(`localStorage.getItem("vatica.authToken")`);
ok(tokenB && tokenB !== tokenA, "注册 B 后 Token 已切换");
const meB = await (await api("/api/auth/me", { token: tokenB })).json();
ok(meB.username === userB && meB.userId !== meA.userId, "GET /api/auth/me 返回 B 身份且与 A 不同");

const sessionB = crypto.randomUUID();
await api(`/api/sessions/${sessionB}`, {
  method: "PUT",
  token: tokenB,
  body: { title: "B会话-隔离检查" },
});
await api("/api/models/user-slots", {
  method: "POST",
  token: tokenB,
  body: {
    name: "B模型-隔离检查", protocol: "openai", baseUrl: "https://api.example.com",
    model: "b-model", temperature: 0.7, enabled: true,
    credentialMode: "EPHEMERAL", apiKey: "sk-b",
  },
});

await clickModalButton(cdp, "完成");
await cdp.send("Page.reload");
await cdp.waitFor(`document.readyState === "complete"`, "页面重载完成");
await waitOnline(cdp);
await waitText(cdp, "B会话-隔离检查");
ok(!(await cdp.eval(`document.body.innerText.includes("A会话-隔离检查")`)), "B 页面不显示 A 会话");

// 打开模型选择器，断言只出现 B 的用户模型
await clickSelectorByMouse(cdp, ".ant-select", "模型选择器");
await cdp.waitFor(
  `[...document.querySelectorAll(".ant-select-item-option-content")].some((el) => el.textContent.includes("B模型-隔离检查"))`,
  "模型下拉出现 B 模型",
);
const optionTexts = await cdp.eval(
  `[...document.querySelectorAll(".ant-select-item-option-content")].map((el) => el.textContent).join("\\n")`,
);
ok(optionTexts.includes("B模型-隔离检查") && !optionTexts.includes("A模型-隔离检查"), "模型选择器只显示 B 的用户模型");
await cdp.eval(`document.body.click()`);

// 模拟过期/无效 Token：应被统一清理并回到未登录态，页面不再残留 B 数据
await cdp.eval(`localStorage.setItem("vatica.authToken", "invalid.header.signature")`);
await cdp.send("Page.reload");
await cdp.waitFor(`document.readyState === "complete"`, "页面重载完成");
await cdp.waitFor(`localStorage.getItem("vatica.authToken") === null`, "无效 Token 被统一清理", 20000);
await waitNoText(cdp, "B会话-隔离检查");
await click(cdp, `document.querySelector("[aria-label='账号']")`, "账号按钮");
await cdp.waitFor(
  `[...document.querySelectorAll(".ant-modal input[autocomplete='current-password']")].some((el) => el.offsetParent !== null)`,
  "Token 失效后账号页回到登录表单",
);
ok(true, "无效 Token 首次 401 后统一清 Token 并回到未登录态");

console.log(`\n鉴权开启冒烟通过（${passed} 项断言）`);
cdp.close();
process.exit(0);
