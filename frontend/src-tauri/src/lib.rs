//! Vatica 桌面壳（迭代 13 I13-8：纯前端瘦客户端——不打包/拉起本地 sidecar）。
//! 后端独立运行（默认 http://localhost:8080），前端通过服务设置可切换任意后端基址。
//!
//! 迭代 35：补系统集成层——系统托盘、关窗驻留、全局快捷键（Alt+Shift+V）、
//! 系统通知与深链接注册。壳只做操作系统触达，业务一律留在后端与 Web 前端。

use std::sync::atomic::{AtomicBool, Ordering};

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, WindowEvent};

/// 首次关窗驻留提示只发一次。
static HIDE_HINT_SHOWN: AtomicBool = AtomicBool::new(false);

/// 显示并聚焦主窗口（托盘左键、快捷键、深链接共用）。
fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// 快捷键触发：可见则隐藏，不可见则唤起。
fn toggle_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        match window.is_visible() {
            Ok(true) => {
                let _ = window.hide();
            }
            _ => show_main_window(app),
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 必须最先注册：重复启动（含 vatica:// 深链接触发的二次启动）唤起现有实例，
        // 不产生第二个窗口/进程。
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app);
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_deep_link::init())
        // 该插件无 init()，必须以 Builder 形式注册；快捷键与处理函数在 setup 中登记
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .setup(|app| {
            // ── 系统托盘：左键唤起，菜单提供显示/退出 ──────────────────────
            let show = MenuItem::with_id(app, "tray-show", "显示主窗口", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "tray-quit", "退出 Vatica", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;
            TrayIconBuilder::with_id("vatica-tray")
                .icon(app.default_window_icon().expect("缺少应用图标").clone())
                .tooltip("Vatica · 个人 AI 助理")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "tray-show" => show_main_window(app),
                    "tray-quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main_window(tray.app_handle());
                    }
                })
                .build(app)?;

            // ── 全局快捷键：Alt+Shift+V 任意应用内唤出/隐藏 ────────────────
            // 被其他软件占用时注册失败仅记日志，不阻断启动。
            use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};
            if let Err(error) = app.global_shortcut().on_shortcut("alt+shift+v", |app, _shortcut, event| {
                if event.state == ShortcutState::Pressed {
                    toggle_main_window(app);
                }
            }) {
                eprintln!("全局快捷键 Alt+Shift+V 注册失败：{error}");
            }

            // ── Windows 深链接注册（vatica://…）：写 HKCU，冷启动与运行中都生效 ──
            #[cfg(target_os = "windows")]
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                let _ = app.deep_link().register_all();
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            // 关窗驻留：退出只能走托盘菜单，避免误关丢提醒通道。
            if let WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
                if !HIDE_HINT_SHOWN.swap(true, Ordering::SeqCst) {
                    use tauri_plugin_notification::NotificationExt;
                    let _ = window.app_handle().notification().builder()
                        .title("Vatica 已驻留系统托盘")
                        .body("窗口已隐藏，可从托盘图标或 Alt+Shift+V 唤起；退出请使用托盘菜单。")
                        .show();
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building Vatica")
        .run(|_app, _event| {});
}
