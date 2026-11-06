mod sidecar;

use tauri::{Manager, RunEvent};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
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
