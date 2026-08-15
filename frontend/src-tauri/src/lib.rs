//! Vatica 桌面壳（迭代 8 I8-1 打包：release 构建自动拉起后端 sidecar，退出时收尾）。

use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;

use tauri::{Manager, RunEvent};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            // 仅打包模式自动拉起后端 sidecar；开发期后端手动启动（README 快速开始）
            if cfg!(debug_assertions) {
                app.manage(BackendProcess(Mutex::new(None)));
                return Ok(());
            }
            let sidecar = sidecar_path();
            let child = if sidecar.exists() {
                match Command::new(&sidecar).spawn() {
                    Ok(c) => Some(c),
                    Err(e) => {
                        eprintln!("[vatica] 启动后端 sidecar 失败（{:?}）：{e}", sidecar);
                        None
                    }
                }
            } else {
                eprintln!("[vatica] 未找到后端 sidecar，桌面壳将以纯前端模式运行");
                None
            };
            app.manage(BackendProcess(Mutex::new(child)));
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building Vatica")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                // 壳退出：终止后端进程（正常退出路径；强杀场景由后端看门狗兜底）
                if let Some(state) = app.try_state::<BackendProcess>() {
                    if let Some(mut child) = state.0.lock().ok().and_then(|mut g| g.take()) {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                }
            }
        });
}

/// sidecar 二进制与主程序同目录。Tauri 2 externalBin 在构建目录/安装目录都以
/// **去三元组后缀**的名字放置（安装包实测 `vatica-backend.exe`，打包时按
/// `vatica-backend-<triple>.exe` 查找、落盘时去掉后缀）；带三元组后缀仅作兼容回退。
fn sidecar_path() -> PathBuf {
    let triple = format!("{}-pc-{}-msvc", std::env::consts::ARCH, std::env::consts::OS);
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_default();
    for name in ["vatica-backend".to_string(), format!("vatica-backend-{triple}")] {
        let path = if cfg!(windows) {
            exe_dir.join(format!("{name}.exe"))
        } else {
            exe_dir.join(name)
        };
        if path.exists() {
            return path;
        }
    }
    exe_dir.join("vatica-backend.exe")
}

struct BackendProcess(Mutex<Option<Child>>);
