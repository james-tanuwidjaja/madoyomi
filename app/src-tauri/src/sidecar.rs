//! JVM backend sidecar: launch, port handshake, health checks and restarts.
//!
//! The contract with the backend is in server/UPSTREAM.md, "Startup contract":
//! - `java -jar <jar> --data-dir <dir>` with env `MADOYOMI_TOKEN`; an unknown argument exits with code 2
//! - it binds `127.0.0.1:0` and prints one line `MADOYOMI_PORT=<port>` on stdout once listening
//! - `GET /health` (no token needed) answers `{"status":"ok","name","version","pid","uptimeMs"}`
//! - it exits when its stdin closes
//!
//! A supervisor thread runs the backend through these states, emitting each change as [`STATUS_EVENT`]:
//! - `starting`: the JVM is alive and has not answered `/health` yet. It stays here for up to [`STARTUP_LIMIT`],
//!   with a tick every second (and `slow` after [`SLOW_AFTER`]), because the server takes 7-10 s on an idle machine
//!   and about 60 s under load.
//! - `healthy`: `/health` answers. It is checked every [`HEALTH_INTERVAL`]. An exit, or [`MAX_HEALTH_MISSES`] misses
//!   in a row, ends the run.
//! - `restarting`: the run failed, and the next start follows after an exponential backoff ([`backoff_delay`]).
//! - `failed`: [`MAX_FAILURES`] starts in a row failed (or the failure can't be fixed by a restart, like a missing
//!   jar). The supervisor waits for [`restart_backend`].
//!
//! The token is generated once per app session and kept across restarts. Only the port changes, so the UI asks for
//! [`get_backend_info`] again after each `healthy`. The token is never logged and never sent in an event.
//!
//! The backend's stdout and stderr and the shell's own lifecycle lines go to `<dataDir>/logs/sidecar.log` (the
//! previous session's file is kept as `sidecar.1.log`). The server writes its own log to `<dataDir>/logs/madoyomi.log`.
//!
//! On Windows every child is put into one Job Object with `KILL_ON_JOB_CLOSE`, so the JVM is killed even when this
//! process crashes or is killed from Task Manager.

use serde::Serialize;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};

/// Tauri event with a [`Status`], sent on every state change and once a second while the backend starts.
pub const STATUS_EVENT: &str = "backend-status";

const PORT_PREFIX: &str = "MADOYOMI_PORT=";
/// After this long without a port, the status says `slow`, and the UI explains that a busy machine takes longer.
const SLOW_AFTER: Duration = Duration::from_secs(30);
/// A live backend that hasn't printed its port after this long is killed, and the start counts as failed.
const STARTUP_LIMIT: Duration = Duration::from_secs(300);
/// Time from the port line to the first good `/health`.
const FIRST_HEALTH_LIMIT: Duration = Duration::from_secs(60);
const FIRST_HEALTH_POLL: Duration = Duration::from_millis(500);
const HEALTH_INTERVAL: Duration = Duration::from_secs(5);
const HEALTH_TIMEOUT: Duration = Duration::from_secs(2);
/// `/health` misses in a row of a live backend before it counts as hung and is restarted.
const MAX_HEALTH_MISSES: u32 = 4;
/// A failure after the backend was healthy this long starts a new failure streak.
const STABLE_AFTER: Duration = Duration::from_secs(60);
/// Failed starts in a row before the supervisor stops restarting and waits for [`restart_backend`].
const MAX_FAILURES: u32 = 5;
const BACKOFF_BASE: Duration = Duration::from_secs(1);
const BACKOFF_MAX: Duration = Duration::from_secs(30);
/// Time the backend gets to shut down cleanly (H2 closes its database) after its stdin closes, before it is killed.
const STOP_GRACE: Duration = Duration::from_secs(5);
/// The server's exit code for a usage error. A restart would fail the same way.
const EXIT_USAGE: i32 = 2;
/// `sidecar.log` is rotated to `sidecar.1.log` when it grows past this.
const LOG_LIMIT: u64 = 10 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum State {
    Starting,
    Healthy,
    Restarting,
    Failed,
    Stopped,
}

/// The backend's state, as sent to the UI ([`STATUS_EVENT`], [`get_backend_status`]). It never holds the token.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    /// Grows with every change, so the UI can drop a snapshot older than an event it already applied.
    pub seq: u64,
    pub state: State,
    /// The start attempt in the current failure streak, from 1.
    pub attempt: u32,
    /// Time since this start attempt began (`starting`).
    pub elapsed_ms: u64,
    /// `starting` for longer than [`SLOW_AFTER`].
    pub slow: bool,
    /// `http://127.0.0.1:<port>` (`healthy`).
    pub base_url: Option<String>,
    pub pid: Option<u32>,
    pub server_version: Option<String>,
    /// Why the last run ended (`restarting`, `failed`).
    pub error: Option<String>,
    pub exit_code: Option<i32>,
    /// Delay before the next start (`restarting`).
    pub retry_in_ms: Option<u64>,
    pub data_dir: String,
    pub log_dir: String,
}

/// What the UI needs to call the API. Only [`get_backend_info`] returns the token.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendInfo {
    pub base_url: String,
    pub token: String,
}

/// The backend sidecar, managed as Tauri state.
pub struct Backend {
    shared: Arc<Shared>,
    token: String,
    _group: Arc<ProcessGroup>,
}

impl Backend {
    /// Starts the supervisor. Never panics: failures show up as a `failed` status.
    pub fn start(app: &AppHandle) -> Self {
        let launch = resolve_launch(app);
        let log = Arc::new(SidecarLog::open(&launch.log_dir));
        let shared = Arc::new(Shared {
            app: app.clone(),
            status: Mutex::new(Status {
                seq: 0,
                state: State::Starting,
                attempt: 1,
                elapsed_ms: 0,
                slow: false,
                base_url: None,
                pid: None,
                server_version: None,
                error: None,
                exit_code: None,
                retry_in_ms: None,
                data_dir: launch.data_dir.display().to_string(),
                log_dir: launch.log_dir.display().to_string(),
            }),
            status_changed: Condvar::new(),
            signal: Mutex::new(Signal::default()),
            signal_changed: Condvar::new(),
            running: Mutex::new(None),
            log,
        });
        let group = Arc::new(ProcessGroup::new(&shared.log));

        let token = match new_token() {
            Ok(token) => token,
            Err(e) => {
                let error = format!("could not generate the API token: {e}");
                shared.log.shell(&error);
                shared.transition(State::Failed, |s| s.error = Some(error));
                return Self {
                    shared,
                    token: String::new(),
                    _group: group,
                };
            }
        };

        let (sup_shared, sup_token, sup_group) = (shared.clone(), token.clone(), group.clone());
        let spawned = std::thread::Builder::new()
            .name("sidecar-supervisor".into())
            .spawn(move || supervise(&sup_shared, &launch, &sup_token, &sup_group));
        if let Err(e) = spawned {
            let error = format!("could not start the supervisor thread: {e}");
            shared.log.shell(&error);
            shared.transition(State::Failed, |s| s.error = Some(error));
        }
        Self {
            shared,
            token,
            _group: group,
        }
    }

    /// Stops the backend: closes its stdin so it shuts down cleanly, and kills it after [`STOP_GRACE`].
    /// Safe to call more than once.
    pub fn stop(&self) {
        self.shared.signal.lock().unwrap().shutdown = true;
        self.shared.signal_changed.notify_all();
        let running = self.shared.running.lock().unwrap().take();
        if let Some(mut running) = running {
            self.shared.log.shell(&format!(
                "stopping the backend (pid {})",
                running.child.id()
            ));
            drop(running.stdin.take());
            let deadline = Instant::now() + STOP_GRACE;
            loop {
                match running.child.try_wait() {
                    Ok(Some(status)) => {
                        self.shared
                            .log
                            .shell(&format!("backend stopped{}", exit_suffix(status.code())));
                        break;
                    }
                    Ok(None) if Instant::now() < deadline => {
                        std::thread::sleep(Duration::from_millis(50))
                    }
                    _ => {
                        self.shared
                            .log
                            .shell("backend did not stop in time, killing it");
                        let _ = running.child.kill();
                        let _ = running.child.wait();
                        break;
                    }
                }
            }
        }
        // No event: the window is gone. get_backend_info waiters are released.
        let mut status = self.shared.status.lock().unwrap();
        status.state = State::Stopped;
        status.seq += 1;
        self.shared.status_changed.notify_all();
    }
}

impl Drop for Backend {
    fn drop(&mut self) {
        self.stop();
    }
}

/// The current status (without the token).
#[tauri::command]
pub fn get_backend_status(backend: tauri::State<'_, Backend>) -> Status {
    backend.shared.status.lock().unwrap().clone()
}

/// `{ baseUrl, token }` once the backend is healthy. Waits while it starts or restarts; fails once it is `failed`.
#[tauri::command]
pub async fn get_backend_info(backend: tauri::State<'_, Backend>) -> Result<BackendInfo, String> {
    let shared = backend.shared.clone();
    let token = backend.token.clone();
    // The wait blocks on a condvar, so run it on the blocking pool, not on an async worker.
    tauri::async_runtime::spawn_blocking(move || {
        wait_for_healthy(&shared).map(|base_url| BackendInfo { base_url, token })
    })
    .await
    .map_err(|e| format!("backend info task failed: {e}"))?
}

/// Starts the backend again now: from `failed`, or skipping the backoff while `restarting`. A no-op otherwise.
#[tauri::command]
pub fn restart_backend(backend: tauri::State<'_, Backend>) {
    let state = backend.shared.status.lock().unwrap().state;
    if matches!(state, State::Restarting | State::Failed) {
        backend.shared.log.shell("restart requested from the UI");
        backend.shared.signal.lock().unwrap().retry = true;
        backend.shared.signal_changed.notify_all();
    }
}

/// Shows `sidecar.log` (or the log folder) in Explorer.
#[tauri::command]
pub fn open_backend_logs(app: AppHandle, backend: tauri::State<'_, Backend>) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    let file = &backend.shared.log.path;
    let target = if file.is_file() {
        file.clone()
    } else {
        file.parent().map(Path::to_path_buf).unwrap_or_default()
    };
    app.opener()
        .reveal_item_in_dir(&target)
        .map_err(|e| format!("could not open {}: {e}", target.display()))
}

// --- supervisor ---

#[derive(Default)]
struct Signal {
    shutdown: bool,
    retry: bool,
}

struct Running {
    child: Child,
    /// Held so the pipe stays open: the backend exits when it closes.
    stdin: Option<ChildStdin>,
}

struct Shared {
    app: AppHandle,
    status: Mutex<Status>,
    status_changed: Condvar,
    signal: Mutex<Signal>,
    signal_changed: Condvar,
    running: Mutex<Option<Running>>,
    log: Arc<SidecarLog>,
}

enum Wake {
    Timeout,
    Retry,
    Shutdown,
}

impl Shared {
    /// Changes the status and emits it.
    fn update(&self, f: impl FnOnce(&mut Status)) {
        let snapshot = {
            let mut status = self.status.lock().unwrap();
            f(&mut status);
            status.seq += 1;
            status.clone()
        };
        self.status_changed.notify_all();
        let _ = self.app.emit(STATUS_EVENT, snapshot);
    }

    /// Moves to `state`, clearing the fields of the previous state first.
    fn transition(&self, state: State, f: impl FnOnce(&mut Status)) {
        self.update(|s| {
            s.state = state;
            s.elapsed_ms = 0;
            s.slow = false;
            s.base_url = None;
            s.pid = None;
            s.server_version = None;
            s.error = None;
            s.exit_code = None;
            s.retry_in_ms = None;
            f(s);
        });
    }

    fn shutting_down(&self) -> bool {
        self.signal.lock().unwrap().shutdown
    }

    /// Waits for `timeout` (forever when `None`), a retry request or the shutdown. Consumes the retry.
    fn wait_for_retry(&self, timeout: Option<Duration>) -> Wake {
        let guard = self.signal.lock().unwrap();
        let waiting = |s: &mut Signal| !s.shutdown && !s.retry;
        let mut guard = match timeout {
            Some(t) => {
                self.signal_changed
                    .wait_timeout_while(guard, t, waiting)
                    .unwrap()
                    .0
            }
            None => self.signal_changed.wait_while(guard, waiting).unwrap(),
        };
        if guard.shutdown {
            Wake::Shutdown
        } else if std::mem::take(&mut guard.retry) {
            Wake::Retry
        } else {
            Wake::Timeout
        }
    }

    /// The backend's exit code once it has exited (it is then forgotten). `Some(None)` when there is no process or
    /// its code is unknown, `None` while it runs.
    fn try_reap(&self) -> Option<Option<i32>> {
        let mut running = self.running.lock().unwrap();
        let result = match running.as_mut() {
            None => return Some(None),
            Some(r) => r.child.try_wait(),
        };
        match result {
            Ok(None) => None,
            Ok(Some(status)) => {
                *running = None;
                Some(status.code())
            }
            Err(_) => {
                *running = None;
                Some(None)
            }
        }
    }

    /// Waits up to [`STOP_GRACE`] for the backend to exit (its stdout has closed), then kills it.
    fn reap(&self) -> Option<i32> {
        let deadline = Instant::now() + STOP_GRACE;
        loop {
            if let Some(code) = self.try_reap() {
                return code;
            }
            if Instant::now() >= deadline {
                return self.kill();
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    /// Kills the backend, if one runs, and returns its exit code.
    fn kill(&self) -> Option<i32> {
        let mut running = self.running.lock().unwrap().take()?;
        drop(running.stdin.take());
        let _ = running.child.kill();
        running.child.wait().ok().and_then(|s| s.code())
    }
}

/// Why a run ended.
struct Failure {
    error: String,
    exit_code: Option<i32>,
    /// How long the backend had been healthy, if it got there.
    healthy_for: Option<Duration>,
    /// A restart can't fix it (missing jar or java, usage error).
    fatal: bool,
}

impl Failure {
    fn new(error: String) -> Self {
        Self {
            error,
            exit_code: None,
            healthy_for: None,
            fatal: false,
        }
    }
}

enum Outcome {
    Shutdown,
    Failed(Failure),
}

#[derive(Debug, PartialEq, Eq)]
enum Decision {
    Restart(Duration),
    GiveUp,
}

fn supervise(shared: &Shared, launch: &Launch, token: &str, group: &ProcessGroup) {
    let mut failures = 0u32;
    loop {
        let failure = match run_once(shared, launch, token, group, failures + 1) {
            Outcome::Shutdown => return,
            Outcome::Failed(failure) => failure,
        };
        if shared.shutting_down() {
            return;
        }
        shared.log.shell(&failure.error);
        let decision = if failure.fatal {
            failures += 1;
            Decision::GiveUp
        } else {
            let (n, decision) = next_after_failure(failures, failure.healthy_for);
            failures = n;
            decision
        };
        let wake = match decision {
            Decision::Restart(delay) => {
                shared.log.shell(&format!(
                    "restarting in {} ms (failed start {failures} of {MAX_FAILURES})",
                    delay.as_millis()
                ));
                shared.transition(State::Restarting, |s| {
                    s.attempt = failures;
                    s.error = Some(failure.error);
                    s.exit_code = failure.exit_code;
                    s.retry_in_ms = Some(millis(delay));
                });
                shared.wait_for_retry(Some(delay))
            }
            Decision::GiveUp => {
                shared
                    .log
                    .shell("not restarting again; waiting for a retry from the UI");
                shared.transition(State::Failed, |s| {
                    s.attempt = failures;
                    s.error = Some(failure.error);
                    s.exit_code = failure.exit_code;
                });
                shared.wait_for_retry(None)
            }
        };
        match wake {
            Wake::Shutdown => return,
            Wake::Retry => failures = 0,
            Wake::Timeout => {}
        }
    }
}

enum StdoutEvent {
    Port(u16),
    BadPortLine(String),
    Closed,
}

/// One start of the backend, until it fails or the app shuts down.
fn run_once(
    shared: &Shared,
    launch: &Launch,
    token: &str,
    group: &ProcessGroup,
    attempt: u32,
) -> Outcome {
    shared.signal.lock().unwrap().retry = false;
    let started = Instant::now();
    shared.transition(State::Starting, |s| s.attempt = attempt);

    // Resolved for each start, not once: a missing jar is fatal (no restart can fix it), but Retry looks again.
    let java = resolve_java(launch.resources.as_deref());
    let jar = match resolve_jar(launch.resources.as_deref()) {
        Ok(jar) => jar,
        Err(e) => {
            return Outcome::Failed(Failure {
                fatal: true,
                ..Failure::new(e)
            });
        }
    };
    if let Err(e) = fs::create_dir_all(&launch.log_dir) {
        shared.log.shell(&format!(
            "could not create {}: {e}",
            launch.log_dir.display()
        ));
    }

    let mut cmd = Command::new(&java);
    cmd.arg("-jar")
        .arg(&jar)
        .arg("--data-dir")
        .arg(&launch.data_dir)
        .env("MADOYOMI_TOKEN", token)
        // A JVM crash writes hs_err_pid*.log into the working directory.
        .current_dir(if launch.log_dir.is_dir() {
            &launch.log_dir
        } else {
            &launch.data_dir
        })
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    shared.log.shell(&format!(
        "start attempt {attempt}: {} -jar {} --data-dir {}",
        java.display(),
        jar.display(),
        launch.data_dir.display()
    ));

    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(e) => {
            return Outcome::Failed(Failure {
                fatal: e.kind() == io::ErrorKind::NotFound,
                ..Failure::new(format!("could not start {}: {e}", java.display()))
            });
        }
    };
    group.assign(&child, &shared.log);
    let pid = child.id();
    let stdout = child.stdout.take().expect("stdout is piped");
    let stderr = child.stderr.take().expect("stderr is piped");
    let stdin = child.stdin.take();

    let (tx, rx) = mpsc::channel();
    let log = shared.log.clone();
    let reader = std::thread::Builder::new()
        .name("sidecar-stdout".into())
        .spawn(move || {
            let mut port_seen = false;
            for_each_line(stdout, |line| match parse_port_line(line) {
                Some(Ok(port)) if !port_seen => {
                    port_seen = true;
                    let _ = tx.send(StdoutEvent::Port(port));
                }
                Some(Err(e)) if !port_seen => {
                    let _ = tx.send(StdoutEvent::BadPortLine(e));
                }
                _ => log.backend(line),
            });
            let _ = tx.send(StdoutEvent::Closed);
        });
    let log = shared.log.clone();
    let err_reader = std::thread::Builder::new()
        .name("sidecar-stderr".into())
        .spawn(move || for_each_line(stderr, |line| log.backend(line)));

    {
        let mut running = shared.running.lock().unwrap();
        // stop() sets the flag before it takes the process, so a child started meanwhile is not left behind.
        if shared.shutting_down() {
            drop(running);
            let _ = child.kill();
            let _ = child.wait();
            return Outcome::Shutdown;
        }
        *running = Some(Running { child, stdin });
    }
    if let Err(e) = reader.and(err_reader) {
        shared.kill();
        return Outcome::Failed(Failure::new(format!(
            "could not start the output readers: {e}"
        )));
    }

    // 1. The port line. Keep waiting while the JVM lives: a busy machine can take a minute.
    let port = loop {
        match rx.recv_timeout(Duration::from_secs(1)) {
            Ok(StdoutEvent::Port(port)) => break port,
            Ok(StdoutEvent::BadPortLine(e)) => {
                shared.kill();
                return Outcome::Failed(Failure::new(e));
            }
            Ok(StdoutEvent::Closed) | Err(RecvTimeoutError::Disconnected) => {
                return exited(shared, shared.reap(), "before reporting its port", None);
            }
            Err(RecvTimeoutError::Timeout) => {
                if shared.shutting_down() {
                    return Outcome::Shutdown;
                }
                if let Some(code) = shared.try_reap() {
                    return exited(shared, code, "before reporting its port", None);
                }
                let elapsed = started.elapsed();
                if elapsed >= STARTUP_LIMIT {
                    shared.kill();
                    return Outcome::Failed(Failure::new(format!(
                        "backend did not report a port within {} s",
                        STARTUP_LIMIT.as_secs()
                    )));
                }
                shared.update(|s| {
                    s.elapsed_ms = millis(elapsed);
                    s.slow = elapsed >= SLOW_AFTER;
                });
            }
        }
    };
    shared.log.shell(&format!(
        "pid {pid} listening on 127.0.0.1:{port} after {} ms",
        started.elapsed().as_millis()
    ));

    // 2. The first good /health.
    let health_deadline = Instant::now() + FIRST_HEALTH_LIMIT;
    let health = loop {
        match check_health(port, HEALTH_TIMEOUT) {
            Ok(health) => break health,
            Err(e) if Instant::now() >= health_deadline => {
                shared.kill();
                return Outcome::Failed(Failure::new(format!(
                    "backend never answered /health: {e}"
                )));
            }
            Err(_) => {}
        }
        if let Some(outcome) = wait_on_stdout(shared, &rx, FIRST_HEALTH_POLL, None) {
            return outcome;
        }
    };
    let healthy_at = Instant::now();
    shared.log.shell(&format!(
        "healthy after {} ms (Madoyomi-Server {})",
        started.elapsed().as_millis(),
        health.version.as_deref().unwrap_or("?")
    ));
    shared.transition(State::Healthy, |s| {
        s.attempt = attempt;
        s.base_url = Some(format!("http://127.0.0.1:{port}"));
        s.pid = health.pid.or(Some(pid));
        s.server_version = health.version;
    });

    // 3. Watch it: an exit ends the run at once (stdout closes), a hang after MAX_HEALTH_MISSES checks.
    let mut misses = 0;
    loop {
        if let Some(outcome) = wait_on_stdout(shared, &rx, HEALTH_INTERVAL, Some(healthy_at)) {
            return outcome;
        }
        match check_health(port, HEALTH_TIMEOUT) {
            Ok(_) => misses = 0,
            Err(e) => {
                misses += 1;
                shared.log.shell(&format!(
                    "/health miss {misses} of {MAX_HEALTH_MISSES}: {e}"
                ));
                if misses >= MAX_HEALTH_MISSES {
                    let code = shared.kill();
                    return Outcome::Failed(Failure {
                        exit_code: code,
                        healthy_for: Some(healthy_at.elapsed()),
                        ..Failure::new(format!("backend stopped answering /health ({e})"))
                    });
                }
            }
        }
    }
}

/// Waits up to `timeout` for the backend's stdout to close. Returns how the run ends if it exited (or the app is
/// shutting down), `None` if it still runs.
fn wait_on_stdout(
    shared: &Shared,
    rx: &Receiver<StdoutEvent>,
    timeout: Duration,
    healthy_at: Option<Instant>,
) -> Option<Outcome> {
    let phase = if healthy_at.is_some() {
        ""
    } else {
        "before answering /health"
    };
    let code = match rx.recv_timeout(timeout) {
        Ok(StdoutEvent::Closed) | Err(RecvTimeoutError::Disconnected) => shared.reap(),
        Ok(_) | Err(RecvTimeoutError::Timeout) => {
            if shared.shutting_down() {
                return Some(Outcome::Shutdown);
            }
            shared.try_reap()?
        }
    };
    Some(exited(shared, code, phase, healthy_at.map(|t| t.elapsed())))
}

fn exited(
    shared: &Shared,
    code: Option<i32>,
    phase: &str,
    healthy_for: Option<Duration>,
) -> Outcome {
    if shared.shutting_down() {
        return Outcome::Shutdown;
    }
    let phase = if phase.is_empty() {
        String::new()
    } else {
        format!(" {phase}")
    };
    Outcome::Failed(Failure {
        error: format!("backend exited{phase}{}", exit_suffix(code)),
        exit_code: code,
        healthy_for,
        fatal: code == Some(EXIT_USAGE),
    })
}

fn exit_suffix(code: Option<i32>) -> String {
    match code {
        Some(code) => format!(" (exit code {code})"),
        None => String::new(),
    }
}

fn millis(d: Duration) -> u64 {
    u64::try_from(d.as_millis()).unwrap_or(u64::MAX)
}

/// Calls `f` for each line, decoding it leniently: the JVM writes stdout in the Windows code page, and a reader that
/// stopped at the first non-UTF-8 byte would leave the pipe full and block the backend.
fn for_each_line(reader: impl Read, mut f: impl FnMut(&str)) {
    let mut reader = BufReader::new(reader);
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf) {
            Ok(0) | Err(_) => return,
            Ok(_) => {
                let line = String::from_utf8_lossy(&buf);
                f(line.trim_end_matches(['\r', '\n']));
            }
        }
    }
}

// --- pure helpers (unit-tested) ---

/// `None` for an ordinary log line, the port for `MADOYOMI_PORT=<port>`, an error for a malformed port line.
fn parse_port_line(line: &str) -> Option<Result<u16, String>> {
    let value = line.trim().strip_prefix(PORT_PREFIX)?;
    Some(match value.trim().parse::<u16>() {
        Ok(0) => Err(format!("bad port line {line:?}: port 0")),
        Ok(port) => Ok(port),
        Err(e) => Err(format!("bad port line {line:?}: {e}")),
    })
}

/// The delay before the next start after `failures` failed starts in a row (from 1): 1 s, 2 s, 4 s, … up to 30 s.
fn backoff_delay(failures: u32) -> Duration {
    let exponent = failures.saturating_sub(1).min(16);
    BACKOFF_BASE.saturating_mul(1 << exponent).min(BACKOFF_MAX)
}

/// The failure count after one more failure, and what to do. A backend that was healthy for [`STABLE_AFTER`] starts
/// a new streak; [`MAX_FAILURES`] in a row give up.
fn next_after_failure(failures: u32, healthy_for: Option<Duration>) -> (u32, Decision) {
    let n = if healthy_for.is_some_and(|d| d >= STABLE_AFTER) {
        1
    } else {
        failures + 1
    };
    let decision = if n >= MAX_FAILURES {
        Decision::GiveUp
    } else {
        Decision::Restart(backoff_delay(n))
    };
    (n, decision)
}

#[derive(Debug, Default, PartialEq, Eq)]
struct Health {
    version: Option<String>,
    pid: Option<u32>,
}

/// `GET /health` over plain HTTP/1.0, which makes the server close the connection after the response.
fn check_health(port: u16, timeout: Duration) -> Result<Health, String> {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let mut stream =
        TcpStream::connect_timeout(&addr, timeout).map_err(|e| format!("connect: {e}"))?;
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));
    stream
        .write_all(b"GET /health HTTP/1.0\r\nHost: 127.0.0.1\r\nAccept: application/json\r\n\r\n")
        .map_err(|e| format!("send: {e}"))?;
    let raw = read_response(&mut stream).map_err(|e| format!("read: {e}"))?;
    parse_health_response(&raw)
}

/// Reads until EOF, or until a body of `Content-Length` bytes is complete.
fn read_response(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        let n = reader.read(&mut chunk)?;
        if n == 0 {
            return Ok(buf);
        }
        buf.extend_from_slice(&chunk[..n]);
        if buf.len() > 64 * 1024 {
            return Err(io::Error::other("response too large"));
        }
        if let Some(end) = buf.windows(4).position(|w| w == b"\r\n\r\n")
            && let Some(len) = content_length(&buf[..end])
            && buf.len() >= end + 4 + len
        {
            return Ok(buf);
        }
    }
}

fn content_length(head: &[u8]) -> Option<usize> {
    String::from_utf8_lossy(head).lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        name.trim()
            .eq_ignore_ascii_case("content-length")
            .then(|| value.trim().parse().ok())?
    })
}

fn parse_health_response(raw: &[u8]) -> Result<Health, String> {
    let text = String::from_utf8_lossy(raw);
    let (head, body) = text
        .split_once("\r\n\r\n")
        .ok_or_else(|| "incomplete HTTP response".to_string())?;
    let code = head
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .ok_or_else(|| "no HTTP status line".to_string())?;
    if code != "200" {
        return Err(format!("HTTP {code}"));
    }
    let json: serde_json::Value =
        serde_json::from_str(body.trim()).map_err(|e| format!("bad /health body: {e}"))?;
    match json.get("status").and_then(|v| v.as_str()) {
        Some("ok") => {}
        other => return Err(format!("unexpected /health status {other:?}")),
    }
    Ok(Health {
        version: json
            .get("version")
            .and_then(|v| v.as_str())
            .map(String::from),
        pid: json
            .get("pid")
            .and_then(|v| v.as_u64())
            .and_then(|p| u32::try_from(p).ok()),
    })
}

fn new_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

/// `2026-11-06T21:55:15.123Z`
fn utc_timestamp(t: SystemTime) -> String {
    let d = t.duration_since(UNIX_EPOCH).unwrap_or_default();
    let secs = d.as_secs();
    let (y, m, day) = civil_from_days(i64::try_from(secs / 86_400).unwrap_or(0));
    let sod = secs % 86_400;
    format!(
        "{y:04}-{m:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        sod / 3600,
        sod % 3600 / 60,
        sod % 60,
        d.subsec_millis()
    )
}

/// Days since 1970-01-01 → (year, month, day), from Howard Hinnant's `civil_from_days`.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, month, day)
}

// --- launch configuration ---

struct Launch {
    /// Where release builds bundle the JRE and the jar; `None` in debug builds, which use the repo. Java and the jar
    /// are resolved again for every start (see [`run_once`]), so a Retry finds a jar that was missing at app start,
    /// and a debug build picks up a jar rebuilt since.
    resources: Option<PathBuf>,
    data_dir: PathBuf,
    log_dir: PathBuf,
}

fn resolve_launch(app: &AppHandle) -> Launch {
    // Release builds will bundle the JRE and the jar as resources (Phase 7). Debug builds use the repo.
    let resources = if cfg!(debug_assertions) {
        None
    } else {
        app.path().resource_dir().ok()
    };
    let data_dir = resolve_data_dir(app);
    Launch {
        resources,
        log_dir: data_dir.join("logs"),
        data_dir,
    }
}

fn env_path(name: &str) -> Option<PathBuf> {
    let value = std::env::var_os(name).filter(|v| !v.is_empty())?;
    let path = PathBuf::from(value);
    Some(std::path::absolute(&path).unwrap_or(path))
}

/// `MADOYOMI_DATA_DIR`, else `%APPDATA%\Madoyomi` (the server's own default), else Tauri's app data dir.
fn resolve_data_dir(app: &AppHandle) -> PathBuf {
    if let Some(dir) = env_path("MADOYOMI_DATA_DIR") {
        return dir;
    }
    if let Some(appdata) = std::env::var_os("APPDATA").filter(|v| !v.is_empty()) {
        return PathBuf::from(appdata).join("Madoyomi");
    }
    app.path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("Madoyomi"))
}

/// The repo root in debug builds (`app/src-tauri/../..`).
fn dev_root() -> PathBuf {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("..").join("..");
    std::path::absolute(&root).unwrap_or(root)
}

/// `MADOYOMI_JAVA`, else the bundled JRE (release: `<resources>/jre`), else the repo's `tools/bin/jdk-21` (debug),
/// else `java` from PATH.
fn resolve_java(resources: Option<&Path>) -> PathBuf {
    if let Some(java) = env_path("MADOYOMI_JAVA") {
        return java;
    }
    let exe = if cfg!(windows) { "java.exe" } else { "java" };
    let candidate = match resources {
        Some(dir) => dir.join("jre").join("bin").join(exe),
        None => dev_root()
            .join("tools")
            .join("bin")
            .join("jdk-21")
            .join("bin")
            .join(exe),
    };
    if candidate.is_file() {
        candidate
    } else {
        PathBuf::from(exe)
    }
}

/// `MADOYOMI_SIDECAR_JAR`, else the bundled jar (release: `<resources>/server/Madoyomi-Server.jar`), else the newest
/// `server/api/build/Madoyomi-Server-*.jar` (debug).
fn resolve_jar(resources: Option<&Path>) -> Result<PathBuf, String> {
    let dev_build = dev_root().join("server").join("api").join("build");
    jar_from(env_path("MADOYOMI_SIDECAR_JAR"), resources, &dev_build)
}

/// [`resolve_jar`] with its inputs given: the override, the resource dir, and the dev build dir.
fn jar_from(
    override_jar: Option<PathBuf>,
    resources: Option<&Path>,
    dev_build: &Path,
) -> Result<PathBuf, String> {
    if let Some(jar) = override_jar {
        return if jar.is_file() {
            Ok(jar)
        } else {
            Err(format!(
                "server jar not found: MADOYOMI_SIDECAR_JAR is {}",
                jar.display()
            ))
        };
    }
    match resources {
        Some(dir) => {
            let jar = dir.join("server").join("Madoyomi-Server.jar");
            if jar.is_file() {
                Ok(jar)
            } else {
                Err(format!(
                    "server jar not found: {} is missing (reinstall Madoyomi)",
                    jar.display()
                ))
            }
        }
        None => newest_server_jar(dev_build).ok_or_else(|| {
            format!(
                "server jar not found in {}: build it with .\\gradlew.bat :api:shadowJar in \
                 server/, or set MADOYOMI_SIDECAR_JAR",
                dev_build.display()
            )
        }),
    }
}

fn newest_server_jar(dir: &Path) -> Option<PathBuf> {
    fs::read_dir(dir)
        .ok()?
        .filter_map(Result::ok)
        .filter(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            name.starts_with("Madoyomi-Server-") && name.ends_with(".jar")
        })
        .max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok())
        .map(|e| e.path())
}

// --- sidecar.log ---

struct SidecarLog {
    path: PathBuf,
    file: Mutex<Option<(File, u64)>>,
}

impl SidecarLog {
    /// Opens `<dir>/sidecar.log`, keeping the previous one as `sidecar.1.log`. Logging is best effort.
    fn open(dir: &Path) -> Self {
        let path = dir.join("sidecar.log");
        let file = fs::create_dir_all(dir)
            .and_then(|()| Self::rotate_and_open(&path))
            .map_err(|e| eprintln!("[sidecar] cannot write {}: {e}", path.display()))
            .ok()
            .map(|f| (f, 0));
        Self {
            path,
            file: Mutex::new(file),
        }
    }

    fn rotate_and_open(path: &Path) -> io::Result<File> {
        if path.exists() {
            let old = path.with_file_name("sidecar.1.log");
            let _ = fs::remove_file(&old);
            fs::rename(path, &old)?;
        }
        OpenOptions::new().create(true).append(true).open(path)
    }

    fn shell(&self, msg: &str) {
        self.write("shell", msg);
    }

    fn backend(&self, msg: &str) {
        self.write("backend", msg);
    }

    fn write(&self, tag: &str, msg: &str) {
        let line = format!("{} [{tag}] {msg}\n", utc_timestamp(SystemTime::now()));
        if cfg!(debug_assertions) {
            eprint!("{line}");
        }
        let mut guard = self.file.lock().unwrap();
        let Some((file, written)) = guard.as_mut() else {
            return;
        };
        if file.write_all(line.as_bytes()).is_ok() {
            *written += line.len() as u64;
        }
        if *written > LOG_LIMIT {
            *guard = Self::rotate_and_open(&self.path).ok().map(|f| (f, 0));
        }
    }
}

// --- process group ---

/// Ties every backend process to this process's lifetime (a Job Object on Windows).
struct ProcessGroup {
    #[cfg(windows)]
    job: Option<job::Job>,
}

impl ProcessGroup {
    fn new(log: &SidecarLog) -> Self {
        #[cfg(windows)]
        {
            let job = job::Job::kill_on_close()
                .map_err(|e| {
                    log.shell(&format!(
                        "job object setup failed ({e}); the JVM may outlive a crash"
                    ))
                })
                .ok();
            Self { job }
        }
        #[cfg(not(windows))]
        {
            let _ = log;
            Self {}
        }
    }

    fn assign(&self, child: &Child, log: &SidecarLog) {
        #[cfg(windows)]
        if let Some(job) = &self.job
            && let Err(e) = job.assign(child)
        {
            log.shell(&format!(
                "could not add pid {} to the job object ({e}); it may outlive a crash",
                child.id()
            ));
        }
        #[cfg(not(windows))]
        let _ = (child, log);
    }
}

#[cfg(windows)]
mod job {
    use std::os::windows::io::AsRawHandle;
    use std::process::Child;
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JobObjectExtendedLimitInformation,
        SetInformationJobObject,
    };

    /// A Job Object whose processes are killed when the last handle to it closes, which happens
    /// when this process exits for any reason, including a crash.
    pub struct Job(HANDLE);

    // The handle is only used for Assign and Close, which are thread-safe.
    unsafe impl Send for Job {}
    unsafe impl Sync for Job {}

    impl Job {
        pub fn kill_on_close() -> std::io::Result<Self> {
            unsafe {
                let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if handle.is_null() {
                    return Err(std::io::Error::last_os_error());
                }
                let job = Job(handle);
                let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                let ok = SetInformationJobObject(
                    job.0,
                    JobObjectExtendedLimitInformation,
                    &info as *const _ as *const _,
                    std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                );
                if ok == 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(job)
            }
        }

        pub fn assign(&self, child: &Child) -> std::io::Result<()> {
            let ok = unsafe { AssignProcessToJobObject(self.0, child.as_raw_handle() as HANDLE) };
            if ok == 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        }
    }

    impl Drop for Job {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
}

/// Blocks until the backend is healthy (its base URL), failed (the error) or stopped.
fn wait_for_healthy(shared: &Shared) -> Result<String, String> {
    let guard = shared.status.lock().unwrap();
    let status = shared
        .status_changed
        .wait_while(guard, |s| {
            matches!(s.state, State::Starting | State::Restarting)
        })
        .unwrap();
    match status.state {
        State::Healthy => status
            .base_url
            .clone()
            .ok_or_else(|| "healthy backend without a URL".into()),
        State::Failed => Err(status
            .error
            .clone()
            .unwrap_or_else(|| "the backend failed to start".into())),
        _ => Err("the backend is stopped".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn port_line_is_parsed() {
        assert_eq!(parse_port_line("MADOYOMI_PORT=50266"), Some(Ok(50266)));
        assert_eq!(parse_port_line("MADOYOMI_PORT=50266\r"), Some(Ok(50266)));
        assert_eq!(parse_port_line("  MADOYOMI_PORT= 1420 "), Some(Ok(1420)));
    }

    #[test]
    fn other_lines_are_not_port_lines() {
        assert_eq!(parse_port_line(""), None);
        assert_eq!(
            parse_port_line("21:55:15.123 [main] INFO  Javalin started"),
            None
        );
        assert_eq!(parse_port_line("PORT=50266"), None);
        assert_eq!(parse_port_line("madoyomi_port=50266"), None);
    }

    #[test]
    fn malformed_port_lines_are_errors() {
        for line in [
            "MADOYOMI_PORT=",
            "MADOYOMI_PORT=abc",
            "MADOYOMI_PORT=0",
            "MADOYOMI_PORT=65536",
            "MADOYOMI_PORT=-1",
        ] {
            let result = parse_port_line(line);
            assert!(matches!(result, Some(Err(_))), "{line:?} gave {result:?}");
        }
    }

    #[test]
    fn backoff_doubles_up_to_the_cap() {
        let secs: Vec<u64> = (1..=8).map(|n| backoff_delay(n).as_secs()).collect();
        assert_eq!(secs, [1, 2, 4, 8, 16, 30, 30, 30]);
        assert_eq!(backoff_delay(0), Duration::from_secs(1));
        assert_eq!(backoff_delay(u32::MAX), BACKOFF_MAX);
    }

    #[test]
    fn failures_in_a_row_give_up() {
        let mut failures = 0;
        let mut delays = Vec::new();
        loop {
            let (n, decision) = next_after_failure(failures, None);
            failures = n;
            match decision {
                Decision::Restart(d) => delays.push(d.as_secs()),
                Decision::GiveUp => break,
            }
        }
        assert_eq!(failures, MAX_FAILURES);
        assert_eq!(delays, [1, 2, 4, 8]);
    }

    #[test]
    fn a_stable_run_starts_a_new_streak() {
        // Healthy for a minute, then it crashed: a fresh streak, the shortest delay.
        assert_eq!(
            next_after_failure(3, Some(STABLE_AFTER)),
            (1, Decision::Restart(Duration::from_secs(1)))
        );
        // Healthy only briefly: the streak goes on.
        assert_eq!(
            next_after_failure(3, Some(Duration::from_secs(5))),
            (4, Decision::Restart(Duration::from_secs(8)))
        );
        assert_eq!(
            next_after_failure(4, Some(Duration::from_secs(5))).1,
            Decision::GiveUp
        );
    }

    const HEALTH_BODY: &str =
        r#"{"status":"ok","name":"Madoyomi-Server","version":"0.1.0","pid":4242,"uptimeMs":9001}"#;

    fn response(status: &str, body: &str) -> String {
        format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
            body.len()
        )
    }

    #[test]
    fn health_response_is_parsed() {
        let health = parse_health_response(response("200 OK", HEALTH_BODY).as_bytes()).unwrap();
        assert_eq!(
            health,
            Health {
                version: Some("0.1.0".into()),
                pid: Some(4242)
            }
        );
    }

    #[test]
    fn bad_health_responses_are_errors() {
        assert_eq!(
            parse_health_response(response("503 Service Unavailable", "{}").as_bytes()),
            Err("HTTP 503".into())
        );
        assert!(
            parse_health_response(response("200 OK", r#"{"status":"starting"}"#).as_bytes())
                .is_err()
        );
        assert!(parse_health_response(response("200 OK", "not json").as_bytes()).is_err());
        assert!(parse_health_response(b"HTTP/1.1 200 OK\r\nContent-Length: 10").is_err());
        assert!(parse_health_response(b"").is_err());
    }

    #[test]
    fn health_check_talks_to_a_local_server() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            let mut byte = [0u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            stream
                .write_all(response("200 OK", HEALTH_BODY).as_bytes())
                .unwrap();
            // Keep the connection open: the client must stop at Content-Length, not wait for EOF.
            std::thread::sleep(Duration::from_millis(1500));
            String::from_utf8(request).unwrap()
        });
        let started = Instant::now();
        let health = check_health(port, Duration::from_secs(5)).unwrap();
        assert!(started.elapsed() < Duration::from_secs(1));
        assert_eq!(health.pid, Some(4242));
        let request = server.join().unwrap();
        assert!(request.starts_with("GET /health HTTP/1.0\r\n"), "{request}");
        assert!(!request.to_ascii_lowercase().contains("authorization"));
    }

    #[test]
    fn health_check_fails_when_nothing_listens() {
        let port = {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            listener.local_addr().unwrap().port()
        };
        assert!(check_health(port, Duration::from_millis(500)).is_err());
    }

    #[test]
    fn lines_are_split_leniently() {
        let mut lines = Vec::new();
        for_each_line(&b"one\r\ntwo\n\xffthree"[..], |l| lines.push(l.to_string()));
        assert_eq!(lines, ["one", "two", "\u{fffd}three"]);
    }

    #[test]
    fn tokens_are_random_hex() {
        let (a, b) = (new_token().unwrap(), new_token().unwrap());
        assert_eq!(a.len(), 64);
        assert!(a.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(a, b);
    }

    #[test]
    fn timestamps_are_utc_iso() {
        assert_eq!(utc_timestamp(UNIX_EPOCH), "1970-01-01T00:00:00.000Z");
        let t = UNIX_EPOCH + Duration::from_millis(1_700_000_000_123);
        assert_eq!(utc_timestamp(t), "2023-11-14T22:13:20.123Z");
        let leap = UNIX_EPOCH + Duration::from_secs(951_782_400); // 2000-02-29
        assert_eq!(utc_timestamp(leap), "2000-02-29T00:00:00.000Z");
    }

    #[test]
    fn newest_jar_wins() {
        let dir = std::env::temp_dir().join(format!("madoyomi-jar-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let old = dir.join("Madoyomi-Server-v0.0.9.jar");
        let new = dir.join("Madoyomi-Server-v0.1.0.jar");
        for (path, age) in [(&old, 100), (&new, 10)] {
            let f = File::create(path).unwrap();
            f.set_modified(SystemTime::now() - Duration::from_secs(age))
                .unwrap();
        }
        File::create(dir.join("other.jar")).unwrap();
        assert_eq!(newest_server_jar(&dir), Some(new));
        assert_eq!(newest_server_jar(&dir.join("missing")), None);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_jar_that_appears_later_is_found_on_the_next_resolve() {
        // Retry resolves the jar again (Phase 3 §10, Stage 7): a jar built after a failed start is used.
        let dir = std::env::temp_dir().join(format!("madoyomi-jar-later-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("server.jar");

        let missing = jar_from(Some(jar.clone()), None, &dir).unwrap_err();
        assert!(missing.contains("MADOYOMI_SIDECAR_JAR"), "{missing}");
        File::create(&jar).unwrap();
        assert_eq!(jar_from(Some(jar.clone()), None, &dir), Ok(jar.clone()));

        // The dev build dir: nothing, then a built jar.
        let build = dir.join("build");
        fs::create_dir_all(&build).unwrap();
        assert!(
            jar_from(None, None, &build)
                .unwrap_err()
                .contains("shadowJar")
        );
        let built = build.join("Madoyomi-Server-v0.1.0.jar");
        File::create(&built).unwrap();
        assert_eq!(jar_from(None, None, &build), Ok(built));

        // A release build without its bundled jar.
        assert!(
            jar_from(None, Some(&dir), &build)
                .unwrap_err()
                .contains("reinstall")
        );
        fs::remove_dir_all(&dir).unwrap();
    }
}
