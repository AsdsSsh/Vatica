//! Vatica 桌面壳（迭代 13 I13-8：纯前端瘦客户端——不再打包/拉起本地 sidecar）。
//! 后端独立运行（默认 http://localhost:8080），前端通过服务设置可切换任意后端基址。

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .build(tauri::generate_context!())
        .expect("error while building Vatica")
        .run(|_app, _event| {});
}
