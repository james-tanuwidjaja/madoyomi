mod sidecar;

use tauri::{Manager, RunEvent};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            // Start the JVM backend right away; the UI asks for its URL via `get_backend_url`.
            app.manage(sidecar::Sidecar::spawn());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![sidecar::get_backend_url])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                // Normal shutdown. Crashes are covered by the Windows job object.
                app.state::<sidecar::Sidecar>().kill();
            }
        });
}
