//! Vatica 后端 sidecar 启动器（迭代 8 I8-1）。
//!
//! 打包后由桌面壳以子进程形式拉起；本程序负责：
//! 1. 用捆绑的 jlink 最小 JRE 启动 Spring Boot jar（-Xmx256m 控制内存，见规划文档 7-03）；
//! 2. 工作目录切到 `%APPDATA%\Vatica`（安装目录只读，文件工具白名单 data/ 落用户目录）；
//! 3. 把自身 PID 传给 Java（VATICA_WATCHDOG_PID）——本进程被强杀时后端看门狗自行退出；
//! 4. **父进程看门狗**（Windows）：桌面壳被强杀/崩溃时，本进程不会随壳退出（Windows
//!    无进程组语义），由本线程轮询父 PID——发现壳已死则收尾 Java 后自行退出。
//!
//! 两层收尾设计（对应"壳崩溃不留孤儿进程"）：
//! - 壳正常退出 → 壳 kill 本启动器 → Java 侧看门狗（≤10s）感知启动器消失 → 优雅自行退出；
//! - 壳被强杀/崩溃 → 本启动器的父进程看门狗（2s 轮询）自行退出 → Java 侧看门狗兜底优雅收尾；
//! - 本启动器单独被强杀 → Java 侧看门狗兜底。

use std::path::PathBuf;
use std::process::{Child, Command, ExitCode};
use std::sync::{Arc, Mutex};

fn main() -> ExitCode {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("[vatica-backend] 无法定位启动器路径: {e}");
            return ExitCode::FAILURE;
        }
    };
    let dir = match exe.parent() {
        Some(d) => d.to_path_buf(),
        None => {
            eprintln!("[vatica-backend] 无法定位应用目录");
            return ExitCode::FAILURE;
        }
    };
    let java = dir.join("jre").join("bin").join("java.exe");
    let jar = dir.join("vatica.jar");
    if !java.exists() || !jar.exists() {
        eprintln!("[vatica-backend] 后端组件缺失：{:?} 或 {:?}", java, jar);
        return ExitCode::FAILURE;
    }

    let data_dir = match std::env::var("APPDATA") {
        Ok(base) => PathBuf::from(base).join("Vatica"),
        Err(_) => dir.clone(),
    };
    if let Err(e) = std::fs::create_dir_all(&data_dir) {
        eprintln!("[vatica-backend] 无法创建工作目录 {:?}: {e}", data_dir);
        return ExitCode::FAILURE;
    }

    let child = match Command::new(&java)
        .arg("-Xmx256m")
        .arg("-Xms128m")
        .arg("-jar")
        .arg(&jar)
        // packaged profile：H2 文件库零依赖（切 MySQL 见 application-packaged.yml 注释）
        .arg("--spring.profiles.active=packaged")
        .current_dir(&data_dir)
        .env("VATICA_WATCHDOG_PID", std::process::id().to_string())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[vatica-backend] 启动后端失败: {e}");
            return ExitCode::FAILURE;
        }
    };

    let child = Arc::new(Mutex::new(child));
    start_parent_watchdog(Arc::clone(&child));

    // 主线程阻塞等待 Java 退出（正常路径：壳 kill 本进程，由 Java 侧看门狗收尾）
    let code = child
        .lock()
        .ok()
        .and_then(|mut c| c.wait().ok().and_then(|s| s.code()))
        .unwrap_or(1);
    ExitCode::from(code.min(255) as u8)
}

/// 父进程看门狗（仅 Windows，2 秒轮询）：桌面壳被强杀/崩溃时，本进程直接退出——
/// 收尾 Java 交给 Java 侧看门狗（≤10 秒，走 shutdown hook 优雅退出、H2 落盘），
/// 避免用 taskkill 强杀造成用户数据未落盘。
/// 拿不到父 PID（如本进程被其他方式拉起）时静默退化为不启用。
#[cfg(windows)]
fn start_parent_watchdog(_child: Arc<Mutex<Child>>) {
    use std::time::Duration;

    let parent = parent_pid();
    std::thread::Builder::new()
        .name("vatica-parent-watchdog".into())
        .spawn(move || {
            let Some(ppid) = parent else { return };
            loop {
                std::thread::sleep(Duration::from_secs(2));
                if !pid_alive(ppid) {
                    eprintln!("[vatica-backend] 桌面壳（pid={ppid}）已退出，启动器自行退出（后端由 Java 侧看门狗优雅收尾）");
                    std::process::exit(0);
                }
            }
        })
        .expect("spawn parent watchdog thread");
}

#[cfg(not(windows))]
fn start_parent_watchdog(_child: Arc<Mutex<Child>>) {}

#[cfg(windows)]
fn parent_pid() -> Option<u32> {
    process_entry(|entry| entry.th32ProcessID == std::process::id())
        .map(|e| e.th32ParentProcessID)
}

#[cfg(windows)]
fn pid_alive(pid: u32) -> bool {
    process_entry(|entry| entry.th32ProcessID == pid).is_some()
}

/// 用 Toolhelp 进程快照找一条进程记录（当前进程/父进程存活检查共用）。
#[cfg(windows)]
fn process_entry(predicate: impl Fn(&winapi::um::tlhelp32::PROCESSENTRY32W) -> bool)
        -> Option<winapi::um::tlhelp32::PROCESSENTRY32W> {
    use winapi::shared::minwindef::FALSE;
    use winapi::um::handleapi::{CloseHandle, INVALID_HANDLE_VALUE};
    use winapi::um::tlhelp32::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snap == INVALID_HANDLE_VALUE {
            return None;
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut found = None;
        if Process32FirstW(snap, &mut entry) != FALSE {
            loop {
                if predicate(&entry) {
                    found = Some(entry);
                    break;
                }
                if Process32NextW(snap, &mut entry) == FALSE {
                    break;
                }
            }
        }
        CloseHandle(snap);
        found
    }
}
