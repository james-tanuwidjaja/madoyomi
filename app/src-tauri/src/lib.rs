mod diagnostics;
mod sidecar;
mod updater;

use tauri::{Manager, RunEvent};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        // The save dialog of Export logs and the downloads folder picker, used from Rust only.
        .plugin(tauri_plugin_dialog::init())
        // The app's own updates (src/updater.rs), driven from Rust; the feed and the key in tauri.conf.json.
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(updater::AppUpdates::new())
        .setup(|app| {
            // Start the JVM backend right away. The UI follows it through the `backend-status` event and
            // `get_backend_status`, and gets `{ baseUrl, token }` from `get_backend_info` once it is healthy.
            app.manage(sidecar::Backend::start(app.handle()));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            sidecar::get_backend_status,
            sidecar::get_backend_info,
            sidecar::restart_backend,
            sidecar::open_backend_logs,
            sidecar::log_from_ui,
            diagnostics::export_logs,
            diagnostics::pick_folder,
            updater::get_app_update_settings,
            updater::set_app_update_check,
            updater::skip_app_update,
            updater::check_app_update,
            updater::install_app_update,
            updater::restart_app,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                // Normal shutdown: the backend closes its database. Crashes are covered by the Windows job object.
                app.state::<sidecar::Backend>().stop();
            }
        });
}
