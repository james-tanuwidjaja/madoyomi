//! Settings → "Export logs" and the crash dialog's export, and the folder picker.
//!
//! The shell writes the zip, so it works while the backend is down. What goes in:
//! - `logs/`: every log in the log folder, the rolled ones too: `sidecar*.log` (the shell, the backend's output, the
//!   UI's errors), `madoyomi*.log` (the backend's own; the gzipped days unpacked), `mpv*.log` (one per playback
//!   session) and `hs_err_pid*.log` (a JVM crash);
//! - `about.json`: the app's and the backend's versions, the backend's state and its crash counts, the OS;
//! - `diagnostics.json`, from the backend when it runs (the UI asks it: the repos, the installed extensions, the
//!   settings without secrets, and the library's titles only if the user ticked the box); without the backend,
//!   `extensions.txt` lists the installed extensions' folders instead;
//! - `README.txt`: what is in it and what was taken out.
//!
//! Every text file is cleaned on the way in: the API token (this session's, and any `token=` value), and the user's
//! profile folder (`C:\Users\<name>` → `%USERPROFILE%`, in its three spellings) and `USERNAME=<name>`.

use crate::sidecar::Backend;
use serde::Serialize;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::time::SystemTime;
use tauri::{AppHandle, Manager};
use tauri_plugin_dialog::DialogExt;
use zip::write::SimpleFileOptions;

/// What an export wrote.
#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExportSummary {
    pub path: String,
    pub bytes: u64,
    /// The zip's entries, in order.
    pub files: Vec<String>,
}

/// What is taken out of every file of an export.
#[derive(Debug, Default, Clone)]
pub struct Redactions {
    /// Secrets replaced by `[token]` wherever they appear (the session's API token).
    pub secrets: Vec<String>,
    /// The user's profile folder (`C:\Users\<name>`), replaced by `%USERPROFILE%`.
    pub profile: Option<String>,
    /// The Windows user name, for `USERNAME=<name>`.
    pub user: Option<String>,
}

impl Redactions {
    /// This machine's: the token given, `USERPROFILE` and `USERNAME`.
    pub fn for_this_user(token: &str) -> Self {
        let env = |k: &str| std::env::var(k).ok().filter(|v| v.len() > 3);
        Self {
            secrets: if token.is_empty() {
                vec![]
            } else {
                vec![token.to_owned()]
            },
            profile: env("USERPROFILE"),
            user: std::env::var("USERNAME").ok().filter(|v| !v.is_empty()),
        }
    }

    /// `text` without the secrets, `token=` values, the profile folder and the user name.
    pub fn apply(&self, text: &str) -> String {
        let mut out = text.to_owned();
        for secret in self.secrets.iter().filter(|s| !s.is_empty()) {
            out = out.replace(secret.as_str(), "[token]");
        }
        out = redact_token_params(&out);
        if let Some(profile) = &self.profile {
            let profile = profile.trim_end_matches(['\\', '/']);
            for spelling in [
                profile.replace('\\', "\\\\"),
                profile.to_owned(),
                profile.replace('\\', "/"),
            ] {
                out = replace_ignore_ascii_case(&out, &spelling, "%USERPROFILE%");
            }
        }
        if let Some(user) = &self.user {
            out =
                replace_ignore_ascii_case(&out, &format!("USERNAME={user}"), "USERNAME=%USERNAME%");
        }
        out
    }
}

/// `token=<value>` → `token=[token]` (any case of `token`), for tokens in URLs of any session or site.
fn redact_token_params(text: &str) -> String {
    let lower = text.to_ascii_lowercase();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while let Some(pos) = lower[i..].find("token=") {
        let start = i + pos + "token=".len();
        out.push_str(&text[i..start]);
        let value_len = text[start..]
            .bytes()
            .take_while(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'%'))
            .count();
        if value_len > 0 {
            out.push_str("[token]");
        }
        i = start + value_len;
    }
    out.push_str(&text[i..]);
    out
}

/// `text` with every `needle` replaced, ignoring the case of ASCII letters (Windows paths).
fn replace_ignore_ascii_case(text: &str, needle: &str, with: &str) -> String {
    if needle.is_empty() {
        return text.to_owned();
    }
    // ASCII lowercasing keeps every byte offset, so matches found in the lowercase copy cut the original right
    let (lower, needle) = (text.to_ascii_lowercase(), needle.to_ascii_lowercase());
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while let Some(pos) = lower[i..].find(&needle) {
        out.push_str(&text[i..i + pos]);
        out.push_str(with);
        i += pos + needle.len();
    }
    out.push_str(&text[i..]);
    out
}

/// What goes into one export.
pub struct ExportSpec<'a> {
    pub dest: &'a Path,
    pub log_dir: &'a Path,
    pub data_dir: &'a Path,
    pub about: serde_json::Value,
    /// The backend's report (`diagnostics.json`), when it runs.
    pub diagnostics: Option<&'a str>,
    pub redactions: &'a Redactions,
}

/// Whether a file of the log folder is a log to export.
fn is_log(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".log") || lower.ends_with(".log.gz")
}

/// The logs of `dir`, by name.
fn log_files(dir: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = fs::read_dir(dir)
        .map(|entries| {
            entries
                .filter_map(Result::ok)
                .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
                .filter(|e| is_log(&e.file_name().to_string_lossy()))
                .map(|e| e.path())
                .collect()
        })
        .unwrap_or_default();
    files.sort();
    files
}

/// A log's text: a `.gz` unpacked, invalid UTF-8 replaced.
fn read_log(path: &Path) -> io::Result<String> {
    let mut bytes = Vec::new();
    let file = File::open(path)?;
    if path
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case("gz"))
    {
        flate2::read::GzDecoder::new(file).read_to_end(&mut bytes)?;
    } else {
        io::BufReader::new(file).read_to_end(&mut bytes)?;
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// The installed extensions' folders (`<data dir>/extensions/<kind>/<package>`), for an export without the backend.
fn extension_folders(data_dir: &Path) -> String {
    let mut lines = Vec::new();
    for kind in ["anime", "manga"] {
        let mut names: Vec<String> = fs::read_dir(data_dir.join("extensions").join(kind))
            .map(|entries| {
                entries
                    .filter_map(Result::ok)
                    .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
                    .map(|e| e.file_name().to_string_lossy().into_owned())
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        lines.push(format!("{kind} ({}):", names.len()));
        lines.extend(names.into_iter().map(|n| format!("  {n}")));
    }
    lines.join("\n") + "\n"
}

const README: &str = "Madoyomi logs, exported from Settings or the crash dialog.

logs/             the shell's sidecar.log (with the backend's output and the UI's errors), the backend's
                  madoyomi.log (older days unpacked from .gz), mpv's log of each playback session, and a JVM
                  crash report (hs_err_pid*.log) if there was one
about.json        the app's and backend's versions, the backend's state and crashes
diagnostics.json  from the backend: repos, installed extensions, settings without secrets (and the library,
                  only if that box was ticked)
extensions.txt    instead of diagnostics.json when the backend wasn't running: the installed extensions

Taken out of every file: the app's API token (and any token= value), your user folder
(shown as %USERPROFILE%) and your Windows user name. Titles you opened can still appear in log lines.
";

/// Writes the export zip (to `<dest>.part`, then moved over `dest`).
pub fn write_export(spec: &ExportSpec<'_>) -> io::Result<ExportSummary> {
    let part = spec.dest.with_extension("zip.part");
    let result = (|| {
        let mut zip = zip::ZipWriter::new(io::BufWriter::new(File::create(&part)?));
        let options =
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        let mut files = Vec::new();
        let mut add = |zip: &mut zip::ZipWriter<_>, name: String, text: &str| -> io::Result<()> {
            zip.start_file(name.as_str(), options)
                .map_err(io::Error::other)?;
            zip.write_all(spec.redactions.apply(text).as_bytes())?;
            files.push(name);
            Ok(())
        };
        add(&mut zip, "README.txt".into(), README)?;
        let about = serde_json::to_string_pretty(&spec.about).map_err(io::Error::other)?;
        add(&mut zip, "about.json".into(), &about)?;
        match spec.diagnostics {
            Some(report) => add(&mut zip, "diagnostics.json".into(), report)?,
            None => add(
                &mut zip,
                "extensions.txt".into(),
                &extension_folders(spec.data_dir),
            )?,
        }
        for path in log_files(spec.log_dir) {
            let name = path.file_name().unwrap_or_default().to_string_lossy();
            let entry = format!("logs/{}", name.strip_suffix(".gz").unwrap_or(&name));
            let text = read_log(&path).unwrap_or_else(|e| format!("(could not be read: {e})\n"));
            add(&mut zip, entry, &text)?;
        }
        zip.finish().map_err(io::Error::other)?.flush()?;
        Ok(files)
    })();
    match result {
        Ok(files) => {
            fs::rename(&part, spec.dest)?;
            Ok(ExportSummary {
                path: spec.dest.display().to_string(),
                bytes: fs::metadata(spec.dest).map(|m| m.len()).unwrap_or(0),
                files,
            })
        }
        Err(e) => {
            let _ = fs::remove_file(&part);
            Err(e)
        }
    }
}

/// `Madoyomi-logs-2026-11-20-1448.zip` (UTC).
fn export_file_name(now: SystemTime) -> String {
    let stamp = crate::sidecar::utc_timestamp(now);
    // 2026-11-20T14:48:44.123Z
    format!(
        "Madoyomi-logs-{}-{}{}.zip",
        &stamp[..10],
        &stamp[11..13],
        &stamp[14..16]
    )
}

/// Asks where to save the logs (a save dialog in Downloads), writes the zip there and returns what it wrote, or
/// `None` when the dialog was cancelled. `diagnostics` is the backend's report, when the UI could get it.
#[tauri::command]
pub async fn export_logs(
    app: AppHandle,
    backend: tauri::State<'_, Backend>,
    diagnostics: Option<String>,
) -> Result<Option<ExportSummary>, String> {
    let status = backend.status();
    let log_dir = backend.log_dir();
    let data_dir = PathBuf::from(&status.data_dir);
    let redactions = Redactions::for_this_user(backend.token());
    let about = serde_json::json!({
        "app": {
            "name": app.package_info().name,
            "version": app.package_info().version.to_string(),
        },
        "exportedAt": crate::sidecar::utc_timestamp(SystemTime::now()),
        "os": format!("{} {}", std::env::consts::OS, std::env::consts::ARCH),
        "backend": {
            "state": status.state,
            "serverVersion": status.server_version,
            "attempt": status.attempt,
            "error": status.error,
            "exitCode": status.exit_code,
            "recentCrashes": status.recent_crashes,
            "crashCount": status.crash_count,
        },
        "dataDir": status.data_dir,
        "logDir": status.log_dir,
        "diagnostics": if diagnostics.is_some() { "from the backend" } else { "the backend wasn't reachable" },
    });
    let mut dialog = app
        .dialog()
        .file()
        .set_title("Export logs")
        .set_file_name(export_file_name(SystemTime::now()))
        .add_filter("Zip archive", &["zip"]);
    if let Ok(dir) = app.path().download_dir() {
        dialog = dialog.set_directory(dir);
    }
    if let Some(window) = app.get_webview_window("main") {
        dialog = dialog.set_parent(&window);
    }
    tauri::async_runtime::spawn_blocking(move || {
        let Some(picked) = dialog.blocking_save_file() else {
            return Ok(None);
        };
        let mut dest = picked.into_path().map_err(|e| e.to_string())?;
        if dest.extension().is_none() {
            dest.set_extension("zip");
        }
        write_export(&ExportSpec {
            dest: &dest,
            log_dir: &log_dir,
            data_dir: &data_dir,
            about,
            diagnostics: diagnostics.as_deref(),
            redactions: &redactions,
        })
        .map(Some)
        .map_err(|e| format!("could not write {}: {e}", dest.display()))
    })
    .await
    .map_err(|e| format!("the export failed: {e}"))?
}

/// A folder picker (Settings → Downloads → Change…): the chosen folder, or `None` when cancelled.
#[tauri::command]
pub async fn pick_folder(
    app: AppHandle,
    title: String,
    start: Option<String>,
) -> Result<Option<String>, String> {
    let mut dialog = app.dialog().file().set_title(title);
    if let Some(dir) = start.map(PathBuf::from).filter(|d| d.is_dir()) {
        dialog = dialog.set_directory(dir);
    }
    if let Some(window) = app.get_webview_window("main") {
        dialog = dialog.set_parent(&window);
    }
    tauri::async_runtime::spawn_blocking(move || {
        dialog
            .blocking_pick_folder()
            .map(|p| p.into_path().map(|p| p.display().to_string()))
            .transpose()
            .map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| format!("the folder picker failed: {e}"))?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::UNIX_EPOCH;

    fn temp_dir(name: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("madoyomi-{name}-{nanos}"));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn redactions() -> Redactions {
        Redactions {
            secrets: vec!["0123456789abcdef0123456789abcdef".into()],
            profile: Some(r"C:\Users\James".into()),
            user: Some("James".into()),
        }
    }

    #[test]
    fn the_token_and_the_user_folder_are_taken_out() {
        let r = redactions();
        let text = concat!(
            "GET /api/v1/manga/1/thumbnail?token=0123456789abcdef0123456789abcdef 200\n",
            "Bearer 0123456789abcdef0123456789abcdef\n",
            "cdn.example.org/v.m3u8?TOKEN=x-y_z.1%2F&e=2\n",
            r"java C:\Users\james\AppData\Local\Madoyomi\jre\bin\java.exe",
            "\n",
            r#"{"ffmpegPath": "C:\\Users\\JAMES\\tools\\ffmpeg.exe"}"#,
            "\n",
            "file:///C:/Users/James/AppData/Roaming/Madoyomi/logs\n",
            "USERNAME=james\n",
            r"D:\Projects\madoyomi stays",
        );
        let out = r.apply(text);
        assert!(!out.to_lowercase().contains("0123456789abcdef"), "{out}");
        assert!(!out.to_lowercase().contains("james"), "{out}");
        assert!(out.contains("thumbnail?token=[token] 200"));
        assert!(out.contains("Bearer [token]"));
        assert!(out.contains("v.m3u8?TOKEN=[token]&e=2"));
        assert!(out.contains(r"java %USERPROFILE%\AppData\Local\Madoyomi\jre\bin\java.exe"));
        assert!(out.contains(r#""%USERPROFILE%\\tools\\ffmpeg.exe""#));
        assert!(out.contains("file:///%USERPROFILE%/AppData/Roaming"));
        assert!(out.contains("USERNAME=%USERNAME%"));
        assert!(out.contains(r"D:\Projects\madoyomi stays"));
        // nothing to take out: unchanged, multibyte text included
        assert_eq!(
            Redactions::default().apply("ラブ token= ok"),
            "ラブ token= ok"
        );
    }

    #[test]
    fn an_export_holds_every_log_cleaned_and_nothing_else() {
        let root = temp_dir("export");
        let (logs, data) = (root.join("logs"), root.join("data"));
        fs::create_dir_all(&logs).unwrap();
        fs::create_dir_all(
            data.join("extensions/anime/eu.kanade.tachiyomi.animeextension.en.animegg"),
        )
        .unwrap();
        fs::create_dir_all(
            data.join("extensions/manga/eu.kanade.tachiyomi.extension.en.mangapill"),
        )
        .unwrap();
        let token = "0123456789abcdef0123456789abcdef";
        fs::write(
            logs.join("sidecar.log"),
            format!("[shell] start\n[ui] error: x?token={token}\n"),
        )
        .unwrap();
        fs::write(logs.join("sidecar.1.log"), "[shell] earlier\n").unwrap();
        fs::write(
            logs.join("madoyomi.log"),
            r"INFO data C:\Users\James\AppData\Roaming\Madoyomi",
        )
        .unwrap();
        let mut gz = flate2::write::GzEncoder::new(
            File::create(logs.join("madoyomi_2026-11-19_0.log.gz")).unwrap(),
            flate2::Compression::default(),
        );
        gz.write_all(format!("an older day {token}\n").as_bytes())
            .unwrap();
        gz.finish().unwrap();
        fs::write(logs.join("mpv.log"), "# Madoyomi playback a\n").unwrap();
        fs::write(
            logs.join("mpv.1.log"),
            "# Madoyomi playback b\nHTTP error 403\n",
        )
        .unwrap();
        fs::write(logs.join("hs_err_pid42.log"), "USERNAME=James\n").unwrap();
        fs::write(logs.join("notes.txt"), "not a log").unwrap();
        fs::create_dir_all(logs.join("sub.log")).unwrap();

        let dest = root.join("out").join("export.zip");
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        let spec = ExportSpec {
            dest: &dest,
            log_dir: &logs,
            data_dir: &data,
            about: serde_json::json!({ "app": { "version": "0.1.0" }, "dataDir": r"C:\Users\James\AppData\Roaming\Madoyomi" }),
            diagnostics: None,
            redactions: &redactions(),
        };
        let summary = write_export(&spec).unwrap();
        assert_eq!(
            summary.files,
            [
                "README.txt",
                "about.json",
                "extensions.txt",
                "logs/hs_err_pid42.log",
                "logs/madoyomi.log",
                "logs/madoyomi_2026-11-19_0.log",
                "logs/mpv.1.log",
                "logs/mpv.log",
                "logs/sidecar.1.log",
                "logs/sidecar.log",
            ]
        );
        assert!(summary.bytes > 0);
        assert!(!dest.with_extension("zip.part").exists());

        let mut zip = zip::ZipArchive::new(File::open(&dest).unwrap()).unwrap();
        let mut all = String::new();
        for i in 0..zip.len() {
            let mut f = zip.by_index(i).unwrap();
            let mut text = String::new();
            f.read_to_string(&mut text).unwrap();
            all.push_str(&text);
        }
        assert!(!all.contains(token), "no token in the zip");
        assert!(
            !all.to_lowercase().contains("james"),
            "no user name in the zip"
        );
        assert!(
            all.contains("an older day [token]"),
            "the gzipped day is unpacked"
        );
        assert!(all.contains("HTTP error 403"));
        assert!(all.contains("eu.kanade.tachiyomi.animeextension.en.animegg"));
        assert!(all.contains("manga (1):"));
        assert!(!all.contains("not a log"));

        // with the backend's report, it replaces extensions.txt; an existing file is replaced
        let with_report = ExportSpec {
            diagnostics: Some(r#"{"extensions": [], "library": "not included"}"#),
            ..spec
        };
        let summary = write_export(&with_report).unwrap();
        assert!(summary.files.contains(&"diagnostics.json".to_owned()));
        assert!(!summary.files.contains(&"extensions.txt".to_owned()));
        fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn a_failed_export_leaves_nothing_behind() {
        let root = temp_dir("export-fail");
        let dest = root.join("missing-folder").join("x.zip");
        let spec = ExportSpec {
            dest: &dest,
            log_dir: &root,
            data_dir: &root,
            about: serde_json::json!({}),
            diagnostics: None,
            redactions: &Redactions::default(),
        };
        assert!(write_export(&spec).is_err());
        assert!(!dest.exists());
        fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn export_names_carry_the_utc_date_and_time() {
        let t = UNIX_EPOCH + std::time::Duration::from_millis(1_700_000_000_123);
        assert_eq!(export_file_name(t), "Madoyomi-logs-2023-11-14-2213.zip");
    }
}
