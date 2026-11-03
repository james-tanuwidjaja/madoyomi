//! JVM backend sidecar: spawn, port handshake, and lifetime.
//!
//! Phase 0 / S5 spike, kept small on purpose. Phase 3 turns it into the real lifecycle manager
//! (restart, bundled JRE, auth token).
//!
//! Contract with the backend:
//! - the backend binds `127.0.0.1:0` and prints one line `MADOYOMI_PORT=<port>` on stdout
//! - `GET /health` answers once it is ready
//! - the backend exits when its stdin closes
//!
//! On Windows the child is put into a Job Object with `KILL_ON_JOB_CLOSE`, so the JVM is killed
//! even when this process crashes or is killed from Task Manager.

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

const PORT_PREFIX: &str = "MADOYOMI_PORT=";
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Default)]
struct Handshake {
    port: Option<u16>,
    /// Set when the backend exits or fails before reporting a port.
    error: Option<String>,
}

pub struct Sidecar {
    child: Mutex<Option<Child>>,
    /// Held only so that its pipe closes when we go away (see the backend's stdin watchdog).
    _stdin: Mutex<Option<ChildStdin>>,
    handshake: Arc<(Mutex<Handshake>, Condvar)>,
    #[cfg(windows)]
    _job: Option<job::Job>,
}

impl Sidecar {
    /// Starts the backend. Never panics: failures are reported through [`get_backend_url`].
    pub fn spawn() -> Self {
        let handshake = Arc::new((Mutex::new(Handshake::default()), Condvar::new()));
        let fail = |msg: String| {
            eprintln!("[sidecar] {msg}");
            handshake.0.lock().unwrap().error = Some(msg);
        };

        let java = resolve_java();
        let jar = match resolve_jar() {
            Some(jar) => jar,
            None => {
                fail(
                    "sidecar jar not found (set MADOYOMI_SIDECAR_JAR or build spikes/sidecar)"
                        .into(),
                );
                return Self::empty(handshake);
            }
        };
        eprintln!(
            "[sidecar] starting {} -jar {}",
            java.display(),
            jar.display()
        );

        let mut cmd = Command::new(&java);
        cmd.arg("-jar")
            .arg(&jar)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = match cmd.spawn() {
            Ok(child) => child,
            Err(e) => {
                fail(format!("failed to start {}: {e}", java.display()));
                return Self::empty(handshake);
            }
        };

        #[cfg(windows)]
        let job = match job::Job::kill_on_close().and_then(|j| j.assign(&child).map(|()| j)) {
            Ok(job) => Some(job),
            Err(e) => {
                eprintln!("[sidecar] job object setup failed ({e}); the JVM may outlive a crash");
                None
            }
        };

        let stdout = child.stdout.take().expect("stdout is piped");
        let stderr = child.stderr.take().expect("stderr is piped");
        let stdin = child.stdin.take();

        let hs = handshake.clone();
        std::thread::Builder::new()
            .name("sidecar-stdout".into())
            .spawn(move || {
                for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                    if let Some(port) = line.strip_prefix(PORT_PREFIX) {
                        let mut state = hs.0.lock().unwrap();
                        match port.trim().parse::<u16>() {
                            Ok(port) if state.port.is_none() => {
                                eprintln!("[sidecar] backend listening on 127.0.0.1:{port}");
                                state.port = Some(port);
                            }
                            Ok(_) => {}
                            Err(e) => state.error = Some(format!("bad port line {line:?}: {e}")),
                        }
                        hs.1.notify_all();
                    } else {
                        eprintln!("[backend] {line}");
                    }
                }
                // stdout closed: the backend exited.
                let mut state = hs.0.lock().unwrap();
                if state.port.is_none() && state.error.is_none() {
                    state.error = Some("backend exited before reporting its port".into());
                }
                hs.1.notify_all();
            })
            .expect("spawn stdout reader");

        std::thread::Builder::new()
            .name("sidecar-stderr".into())
            .spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    eprintln!("[backend] {line}");
                }
            })
            .expect("spawn stderr reader");

        Self {
            child: Mutex::new(Some(child)),
            _stdin: Mutex::new(stdin),
            handshake,
            #[cfg(windows)]
            _job: job,
        }
    }

    fn empty(handshake: Arc<(Mutex<Handshake>, Condvar)>) -> Self {
        Self {
            child: Mutex::new(None),
            _stdin: Mutex::new(None),
            handshake,
            #[cfg(windows)]
            _job: None,
        }
    }

    /// Kills the backend. Safe to call more than once.
    pub fn kill(&self) {
        if let Some(mut child) = self.child.lock().unwrap().take() {
            eprintln!("[sidecar] stopping backend (pid {})", child.id());
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

impl Drop for Sidecar {
    fn drop(&mut self) {
        self.kill();
    }
}

/// Returns the backend's base URL once it is up, e.g. `http://127.0.0.1:50266`.
#[tauri::command]
pub async fn get_backend_url(state: tauri::State<'_, Sidecar>) -> Result<String, String> {
    // The wait blocks on a condvar, so run it on the blocking pool, not on an async worker.
    let handshake = state.handshake.clone();
    let port =
        tauri::async_runtime::spawn_blocking(move || wait_for_port(&handshake, HANDSHAKE_TIMEOUT))
            .await
            .map_err(|e| format!("handshake task failed: {e}"))??;
    Ok(format!("http://127.0.0.1:{port}"))
}

/// Blocks until the backend reports its port, it fails, or the timeout passes.
fn wait_for_port(
    handshake: &(Mutex<Handshake>, Condvar),
    timeout: Duration,
) -> Result<u16, String> {
    let (lock, cvar) = handshake;
    let guard = lock.lock().unwrap();
    let (state, _) = cvar
        .wait_timeout_while(guard, timeout, |s| s.port.is_none() && s.error.is_none())
        .unwrap();
    match (state.port, &state.error) {
        (Some(port), _) => Ok(port),
        (None, Some(err)) => Err(err.clone()),
        (None, None) => Err(format!("backend did not report a port within {timeout:?}")),
    }
}

/// Repo root in debug builds (`app/src-tauri/../..`). Release builds will bundle the JRE and jar.
fn dev_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
}

fn resolve_java() -> PathBuf {
    if let Some(java) = std::env::var_os("MADOYOMI_JAVA") {
        return PathBuf::from(java);
    }
    let exe = if cfg!(windows) { "java.exe" } else { "java" };
    let bundled_dev = dev_root()
        .join("tools")
        .join("bin")
        .join("jdk-21")
        .join("bin")
        .join(exe);
    if bundled_dev.is_file() {
        return bundled_dev;
    }
    PathBuf::from(exe) // fall back to PATH
}

fn resolve_jar() -> Option<PathBuf> {
    if let Some(jar) = std::env::var_os("MADOYOMI_SIDECAR_JAR") {
        return Some(PathBuf::from(jar));
    }
    let jar = dev_root()
        .join("spikes")
        .join("sidecar")
        .join("build")
        .join("libs")
        .join("sidecar-all.jar");
    jar.is_file().then_some(jar)
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
