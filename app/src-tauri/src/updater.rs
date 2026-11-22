//! The app's own updates, through `tauri-plugin-updater`, driven from Rust only.
//!
//! - The feed is `plugins.updater.endpoints` in tauri.conf.json: the GitHub release's `latest.json`. A test build
//!   points it at a local feed with a config of its own (never shipped).
//! - The check runs when the UI asks: on start only if "Check for app updates" is on (off by default, the user's
//!   decision) and the app is installed; "Check now" in Settings → General any time. The setting and a skipped
//!   version are kept in `<data dir>/app-update.json`.
//! - An offline check, and a feed that isn't there yet (the repo is private until the releases are public), are
//!   quiet on start: logged in `sidecar.log`, not shown. "Check now" shows every error.
//! - Only the per-user NSIS install updates itself (`passive`: the installer's progress window, no admin). An MSI
//!   install (per-machine) is told a version is out and pointed at the release page; a build that isn't installed
//!   (dev, the bare release exe) can check but not install.
//! - Install: the installer is downloaded and its signature checked while everything keeps running, then the
//!   backend is stopped the way the app's exit does (stdin closed: the server's shutdown quits mpv and leaves the
//!   running downloads to be queued again at its next start; killed after its grace), and only then the installer
//!   is started. On Windows the plugin exits the app once the installer runs; the installer starts the new version.

use crate::sidecar::Backend;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::utils::config::BundleType;
use tauri::utils::platform::bundle_type;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::{Error as UpdaterError, Update, UpdaterExt};

/// The Tauri event with an [`InstallProgress`] while an update downloads and installs.
pub const PROGRESS_EVENT: &str = "app-update-progress";
/// Where an MSI install (or anyone) gets the new version by hand.
pub const RELEASES_URL: &str = "https://github.com/james-tanuwidjaja/madoyomi/releases/latest";
const SETTINGS_FILE: &str = "app-update.json";

/// How this copy was installed, from the bundle type Tauri's bundler writes into the exe.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum InstallKind {
    /// The per-user NSIS installer: updates itself.
    Nsis,
    /// The per-machine MSI: says a version is out, links the release page.
    Msi,
    /// Not installed (`pnpm tauri dev`, `cargo run`, the bare release exe): checks only.
    None,
}

impl InstallKind {
    pub fn from_bundle(bundle: Option<BundleType>) -> Self {
        match bundle {
            Some(BundleType::Nsis) => Self::Nsis,
            Some(BundleType::Msi) => Self::Msi,
            _ => Self::None,
        }
    }

    pub fn current() -> Self {
        Self::from_bundle(bundle_type())
    }

    /// Whether the check on start may run at all (a build that isn't installed never checks by itself).
    pub fn checks_on_start(self) -> bool {
        self != Self::None
    }
}

/// `<data dir>/app-update.json`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StoredSettings {
    /// "Check for app updates" (on start). Off by default.
    pub check_on_start: bool,
    /// "Skip this version": no dialog on start for it (a newer one shows again).
    pub skipped_version: Option<String>,
}

impl StoredSettings {
    pub fn load(path: &Path) -> Self {
        fs::read_to_string(path)
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, path: &Path) -> Result<(), String> {
        let text = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        }
        // written beside it, then renamed, so a crash never leaves half a file
        let tmp = path.with_extension("json.tmp");
        fs::write(&tmp, text).map_err(|e| format!("{}: {e}", tmp.display()))?;
        fs::rename(&tmp, path).map_err(|e| format!("{}: {e}", path.display()))
    }
}

/// What Settings → General shows.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdateSettings {
    pub check_on_start: bool,
    pub skipped_version: Option<String>,
    pub current_version: String,
    pub install: InstallKind,
    pub releases_url: String,
}

/// A version newer than this one, from the feed.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AvailableUpdate {
    pub version: String,
    pub current_version: String,
    /// The release notes (plain text).
    pub notes: Option<String>,
    /// RFC 3339.
    pub date: Option<String>,
    /// Only the NSIS install installs; an MSI install opens the release page.
    pub can_install: bool,
}

/// The answer to a check.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    tag = "result"
)]
pub enum CheckOutcome {
    /// The check on start is off, or this build isn't installed (nothing was fetched).
    Off,
    /// Nothing newer.
    UpToDate {
        current_version: String,
    },
    /// A newer version the user skipped (only on start).
    Skipped {
        version: String,
    },
    /// The check on start failed quietly (offline, no feed yet); logged.
    Quiet {
        reason: String,
    },
    Available {
        update: AvailableUpdate,
    },
}

/// Progress of [`install_app_update`], as [`PROGRESS_EVENT`].
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    tag = "phase"
)]
pub enum InstallProgress {
    Downloading {
        downloaded: u64,
        total: Option<u64>,
    },
    /// The download is checked; the backend and mpv are being stopped.
    Stopping,
    /// The installer starts; the app exits.
    Installing,
}

/// Why a check failed, as far as the start-up check cares.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FailureKind {
    /// No network, the host not found, a timeout.
    Offline,
    /// The feed answered but there's no `latest.json` (404 while the repo is private, before the first release).
    NoFeed,
    /// Anything else (a broken `latest.json`, a bad signature, a server error page with JSON …).
    Other,
}

pub fn classify(error: &UpdaterError) -> FailureKind {
    match error {
        UpdaterError::Reqwest(e) if e.is_connect() || e.is_timeout() || e.is_request() => {
            FailureKind::Offline
        }
        UpdaterError::Network(_) => FailureKind::Offline,
        UpdaterError::ReleaseNotFound => FailureKind::NoFeed,
        _ => FailureKind::Other,
    }
}

/// What a failed check gives: quiet on start for [`FailureKind::Offline`] and [`FailureKind::NoFeed`], an error
/// otherwise (and always for "Check now").
pub fn failed_check(
    kind: FailureKind,
    on_start: bool,
    message: &str,
) -> Result<CheckOutcome, String> {
    let text = match kind {
        FailureKind::Offline => format!("Couldn't reach the update feed (offline?): {message}"),
        FailureKind::NoFeed => format!("No release found at the update feed: {message}"),
        FailureKind::Other => format!("The update check failed: {message}"),
    };
    if on_start && kind != FailureKind::Other {
        Ok(CheckOutcome::Quiet { reason: text })
    } else {
        Err(text)
    }
}

/// What a successful check gives.
pub fn found(
    update: Option<AvailableUpdate>,
    current_version: &str,
    on_start: bool,
    skipped: Option<&str>,
) -> CheckOutcome {
    match update {
        None => CheckOutcome::UpToDate {
            current_version: current_version.to_owned(),
        },
        Some(u) if on_start && skipped == Some(u.version.as_str()) => {
            CheckOutcome::Skipped { version: u.version }
        }
        Some(update) => CheckOutcome::Available { update },
    }
}

/// The steps of an install, in [`run_install`]'s order. A trait so the order can be tested without a feed.
pub trait InstallSteps {
    /// Downloads the installer and checks its signature (the app keeps running).
    fn download(&mut self) -> impl std::future::Future<Output = Result<Vec<u8>, String>> + Send;
    /// Stops the backend (and with it mpv and the downloads) the way the app's exit does.
    fn stop_backend(&mut self);
    /// Starts the installer. On Windows it doesn't return when it works: the app exits.
    fn install(&mut self, bytes: &[u8]) -> Result<(), String>;
}

/// Download, then stop the backend, then the installer. Nothing is stopped if the download fails.
pub async fn run_install(steps: &mut impl InstallSteps) -> Result<(), InstallFailure> {
    let bytes = steps.download().await.map_err(InstallFailure::Download)?;
    steps.stop_backend();
    steps.install(&bytes).map_err(InstallFailure::Installer)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum InstallFailure {
    /// The app is as it was.
    Download(String),
    /// The backend is stopped: the app has to restart.
    Installer(String),
}

/// The app's updater state, managed by Tauri.
pub struct AppUpdates {
    pending: Mutex<Option<Update>>,
    installing: AtomicBool,
}

impl AppUpdates {
    pub fn new() -> Self {
        Self {
            pending: Mutex::new(None),
            installing: AtomicBool::new(false),
        }
    }
}

fn settings_path(backend: &Backend) -> PathBuf {
    backend.data_dir().join(SETTINGS_FILE)
}

fn current_version(app: &AppHandle) -> String {
    app.package_info().version.to_string()
}

fn settings_view(app: &AppHandle, stored: StoredSettings) -> AppUpdateSettings {
    AppUpdateSettings {
        check_on_start: stored.check_on_start,
        skipped_version: stored.skipped_version,
        current_version: current_version(app),
        install: InstallKind::current(),
        releases_url: RELEASES_URL.to_owned(),
    }
}

#[tauri::command]
pub fn get_app_update_settings(
    app: AppHandle,
    backend: tauri::State<'_, Backend>,
) -> AppUpdateSettings {
    settings_view(&app, StoredSettings::load(&settings_path(&backend)))
}

#[tauri::command]
pub fn set_app_update_check(
    app: AppHandle,
    backend: tauri::State<'_, Backend>,
    check_on_start: bool,
) -> Result<AppUpdateSettings, String> {
    let path = settings_path(&backend);
    let mut stored = StoredSettings::load(&path);
    stored.check_on_start = check_on_start;
    stored.save(&path)?;
    backend.log_shell(&format!(
        "app updates: the check on start is {}",
        if check_on_start { "on" } else { "off" }
    ));
    Ok(settings_view(&app, stored))
}

/// "Skip this version": the check on start says nothing about `version` again.
#[tauri::command]
pub fn skip_app_update(
    app: AppHandle,
    backend: tauri::State<'_, Backend>,
    version: String,
) -> Result<AppUpdateSettings, String> {
    let path = settings_path(&backend);
    let mut stored = StoredSettings::load(&path);
    stored.skipped_version = Some(version.clone());
    stored.save(&path)?;
    backend.log_shell(&format!("app updates: {version} skipped"));
    Ok(settings_view(&app, stored))
}

/// Checks the feed. `onStart`: the check the UI runs once the backend is healthy, which does nothing unless the
/// setting is on and the app is installed, stays quiet when offline, and skips a skipped version.
#[tauri::command]
pub async fn check_app_update(
    app: AppHandle,
    backend: tauri::State<'_, Backend>,
    updates: tauri::State<'_, AppUpdates>,
    on_start: bool,
) -> Result<CheckOutcome, String> {
    let stored = StoredSettings::load(&settings_path(&backend));
    let install = InstallKind::current();
    if on_start && !(stored.check_on_start && install.checks_on_start()) {
        return Ok(CheckOutcome::Off);
    }
    let current = current_version(&app);
    backend.log_shell(&format!(
        "app updates: checking ({}, {current}, {install:?} install)",
        if on_start { "on start" } else { "asked" }
    ));
    let checked = match app.updater() {
        Ok(updater) => updater.check().await,
        Err(e) => Err(e),
    };
    let outcome = match checked {
        Ok(update) => {
            let available = update.as_ref().map(|u| AvailableUpdate {
                version: u.version.clone(),
                current_version: u.current_version.clone(),
                notes: u.body.clone(),
                date: u.date.and_then(|d| {
                    d.format(&time::format_description::well_known::Rfc3339)
                        .ok()
                }),
                can_install: install == InstallKind::Nsis,
            });
            *updates.pending.lock().unwrap() = update;
            Ok(found(
                available,
                &current,
                on_start,
                stored.skipped_version.as_deref(),
            ))
        }
        Err(e) => failed_check(classify(&e), on_start, &e.to_string()),
    };
    backend.log_shell(&match &outcome {
        Ok(CheckOutcome::Available { update }) => format!("app updates: {} is out", update.version),
        Ok(CheckOutcome::Skipped { version }) => format!("app updates: {version} is out (skipped)"),
        Ok(CheckOutcome::UpToDate { .. }) => "app updates: up to date".to_owned(),
        Ok(CheckOutcome::Quiet { reason }) => format!("app updates: {reason}"),
        Ok(CheckOutcome::Off) => "app updates: off".to_owned(),
        Err(e) => format!("app updates: {e}"),
    });
    outcome
}

struct RealInstall<'a> {
    app: &'a AppHandle,
    update: Update,
}

impl InstallSteps for RealInstall<'_> {
    async fn download(&mut self) -> Result<Vec<u8>, String> {
        let app = self.app.clone();
        let mut downloaded: u64 = 0;
        let mut last_sent: u64 = 0;
        let backend = self.app.state::<Backend>();
        backend.log_shell(&format!(
            "app updates: downloading {} from {}",
            self.update.version, self.update.download_url
        ));
        let bytes = self
            .update
            .download(
                |chunk, total| {
                    downloaded += chunk as u64;
                    // about every 1 MB, and the last chunk
                    if downloaded - last_sent >= 1 << 20 || Some(downloaded) == total {
                        last_sent = downloaded;
                        let _ = app.emit(
                            PROGRESS_EVENT,
                            InstallProgress::Downloading { downloaded, total },
                        );
                    }
                },
                || {},
            )
            .await
            .map_err(|e| e.to_string())?;
        backend.log_shell(&format!(
            "app updates: downloaded {} bytes, the signature checks out",
            bytes.len()
        ));
        Ok(bytes)
    }

    fn stop_backend(&mut self) {
        let _ = self.app.emit(PROGRESS_EVENT, InstallProgress::Stopping);
        let backend = self.app.state::<Backend>();
        backend.log_shell(&format!(
            "app updates: stopping the backend before installing {}",
            self.update.version
        ));
        backend.stop();
    }

    fn install(&mut self, bytes: &[u8]) -> Result<(), String> {
        let _ = self.app.emit(PROGRESS_EVENT, InstallProgress::Installing);
        let backend = self.app.state::<Backend>();
        backend.log_shell(&format!(
            "app updates: starting the installer of {} (the app exits)",
            self.update.version
        ));
        self.update.install(bytes).map_err(|e| e.to_string())
    }
}

/// What a failed install rejects with.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct InstallError {
    pub message: String,
    /// The backend was stopped before the installer failed to start: only a restart brings it back.
    pub needs_restart: bool,
}

impl InstallError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            needs_restart: false,
        }
    }
}

impl From<InstallFailure> for InstallError {
    fn from(failure: InstallFailure) -> Self {
        match failure {
            InstallFailure::Download(e) => Self::new(format!("The download failed: {e}")),
            InstallFailure::Installer(e) => Self {
                message: format!(
                    "The installer didn't start: {e}. The backend is stopped: restart Madoyomi."
                ),
                needs_restart: true,
            },
        }
    }
}

/// Installs the update the last check found (only on the NSIS install). Returns only when it failed.
#[tauri::command]
pub async fn install_app_update(
    app: AppHandle,
    updates: tauri::State<'_, AppUpdates>,
) -> Result<(), InstallError> {
    if InstallKind::current() != InstallKind::Nsis {
        return Err(InstallError::new(
            "This copy of Madoyomi wasn't installed with its installer, so it can't update itself.",
        ));
    }
    let Some(update) = updates.pending.lock().unwrap().clone() else {
        return Err(InstallError::new("No update to install: check again."));
    };
    if updates.installing.swap(true, Ordering::SeqCst) {
        return Err(InstallError::new("The update is already being installed."));
    }
    let mut steps = RealInstall { app: &app, update };
    let result = run_install(&mut steps).await;
    updates.installing.store(false, Ordering::SeqCst);
    result.map_err(|failure| {
        let error = InstallError::from(failure);
        app.state::<Backend>()
            .log_shell(&format!("app updates: {}", error.message));
        error
    })
}

/// After a failed installer start (the backend is stopped): the app starts again.
#[tauri::command]
pub fn restart_app(app: AppHandle) {
    app.state::<Backend>()
        .log_shell("app updates: restarting the app");
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn update(version: &str, can_install: bool) -> AvailableUpdate {
        AvailableUpdate {
            version: version.into(),
            current_version: "0.1.0".into(),
            notes: Some("notes".into()),
            date: None,
            can_install,
        }
    }

    #[test]
    fn the_install_kind_comes_from_the_bundle_type() {
        assert_eq!(
            InstallKind::from_bundle(Some(BundleType::Nsis)),
            InstallKind::Nsis
        );
        assert_eq!(
            InstallKind::from_bundle(Some(BundleType::Msi)),
            InstallKind::Msi
        );
        assert_eq!(InstallKind::from_bundle(None), InstallKind::None);
        assert!(InstallKind::Nsis.checks_on_start());
        assert!(InstallKind::Msi.checks_on_start());
        assert!(!InstallKind::None.checks_on_start());
    }

    #[test]
    fn settings_default_to_off_and_survive_a_round_trip() {
        let dir = std::env::temp_dir().join(format!("madoyomi-upd-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let path = dir.join("app-update.json");
        assert_eq!(StoredSettings::load(&path), StoredSettings::default());
        assert!(!StoredSettings::default().check_on_start);
        let s = StoredSettings {
            check_on_start: true,
            skipped_version: Some("0.2.0".into()),
        };
        s.save(&path).unwrap();
        assert_eq!(StoredSettings::load(&path), s);
        assert!(
            fs::read_to_string(&path)
                .unwrap()
                .contains("\"checkOnStart\": true")
        );
        // a broken file reads as the defaults
        fs::write(&path, "{ not json").unwrap();
        assert_eq!(StoredSettings::load(&path), StoredSettings::default());
        // a missing field takes its default
        fs::write(&path, r#"{"checkOnStart":true}"#).unwrap();
        assert_eq!(StoredSettings::load(&path).skipped_version, None);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn offline_and_no_feed_are_quiet_on_start_only() {
        for kind in [FailureKind::Offline, FailureKind::NoFeed] {
            assert!(matches!(
                failed_check(kind, true, "x"),
                Ok(CheckOutcome::Quiet { .. })
            ));
            assert!(failed_check(kind, false, "x").is_err());
        }
        assert!(failed_check(FailureKind::Other, true, "x").is_err());
        assert!(
            failed_check(FailureKind::Other, false, "x")
                .unwrap_err()
                .contains("x")
        );
    }

    #[test]
    fn a_missing_release_is_no_feed() {
        assert_eq!(
            classify(&UpdaterError::ReleaseNotFound),
            FailureKind::NoFeed
        );
        assert_eq!(
            classify(&UpdaterError::Network("down".into())),
            FailureKind::Offline
        );
        assert_eq!(classify(&UpdaterError::EmptyEndpoints), FailureKind::Other);
    }

    #[test]
    fn a_skipped_version_is_quiet_on_start_but_shown_when_asked() {
        assert_eq!(
            found(None, "0.1.0", true, None),
            CheckOutcome::UpToDate {
                current_version: "0.1.0".into()
            }
        );
        assert_eq!(
            found(Some(update("0.2.0", true)), "0.1.0", true, Some("0.2.0")),
            CheckOutcome::Skipped {
                version: "0.2.0".into()
            }
        );
        assert!(matches!(
            found(Some(update("0.2.0", true)), "0.1.0", false, Some("0.2.0")),
            CheckOutcome::Available { .. }
        ));
        // a newer version than the skipped one shows again
        assert!(matches!(
            found(Some(update("0.3.0", true)), "0.1.0", true, Some("0.2.0")),
            CheckOutcome::Available { .. }
        ));
    }

    #[test]
    fn outcomes_serialize_for_the_ui() {
        let json = serde_json::to_value(CheckOutcome::Available {
            update: update("0.2.0", false),
        })
        .unwrap();
        assert_eq!(json["result"], "available");
        // the variants' own fields are camelCase too (the UI reads `currentVersion`)
        assert_eq!(
            serde_json::to_value(CheckOutcome::UpToDate {
                current_version: "0.1.0".into()
            })
            .unwrap(),
            serde_json::json!({"result": "upToDate", "currentVersion": "0.1.0"})
        );
        assert_eq!(json["update"]["canInstall"], false);
        assert_eq!(json["update"]["currentVersion"], "0.1.0");
        let p = serde_json::to_value(InstallProgress::Downloading {
            downloaded: 5,
            total: Some(10),
        })
        .unwrap();
        assert_eq!(
            p,
            serde_json::json!({"phase": "downloading", "downloaded": 5, "total": 10})
        );
        assert_eq!(
            serde_json::to_value(InstallProgress::Stopping).unwrap(),
            serde_json::json!({"phase": "stopping"})
        );
    }

    #[derive(Default)]
    struct Recorder {
        calls: Vec<&'static str>,
        download_fails: bool,
        install_fails: bool,
    }

    impl InstallSteps for Recorder {
        async fn download(&mut self) -> Result<Vec<u8>, String> {
            self.calls.push("download");
            if self.download_fails {
                Err("offline".into())
            } else {
                Ok(vec![1, 2, 3])
            }
        }
        fn stop_backend(&mut self) {
            self.calls.push("stop");
        }
        fn install(&mut self, bytes: &[u8]) -> Result<(), String> {
            assert_eq!(bytes, [1, 2, 3]);
            self.calls.push("install");
            if self.install_fails {
                Err("no".into())
            } else {
                Ok(())
            }
        }
    }

    fn block_on<F: std::future::Future>(f: F) -> F::Output {
        tauri::async_runtime::block_on(f)
    }

    #[test]
    fn only_a_failed_installer_needs_a_restart() {
        let download = InstallError::from(InstallFailure::Download("offline".into()));
        assert!(!download.needs_restart);
        assert!(download.message.contains("offline"));
        let installer = InstallError::from(InstallFailure::Installer("denied".into()));
        assert!(installer.needs_restart);
        assert_eq!(
            serde_json::to_value(&installer).unwrap()["needsRestart"],
            serde_json::json!(true)
        );
    }

    #[test]
    fn the_backend_stops_after_the_download_and_before_the_installer() {
        let mut r = Recorder::default();
        assert_eq!(block_on(run_install(&mut r)), Ok(()));
        assert_eq!(r.calls, ["download", "stop", "install"]);
    }

    #[test]
    fn a_failed_download_stops_nothing() {
        let mut r = Recorder {
            download_fails: true,
            ..Default::default()
        };
        assert_eq!(
            block_on(run_install(&mut r)),
            Err(InstallFailure::Download("offline".into()))
        );
        assert_eq!(r.calls, ["download"]);
    }

    #[test]
    fn a_failed_installer_is_reported_after_the_stop() {
        let mut r = Recorder {
            install_fails: true,
            ..Default::default()
        };
        assert_eq!(
            block_on(run_install(&mut r)),
            Err(InstallFailure::Installer("no".into()))
        );
        assert_eq!(r.calls, ["download", "stop", "install"]);
    }
}
