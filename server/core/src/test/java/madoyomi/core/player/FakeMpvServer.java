package madoyomi.core.player;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A fake mpv on a real Windows named pipe (Phase 4 step 19), speaking mpv's JSON IPC: line-delimited JSON, replies by
 * {@code request_id}, {@code observe_property} with the current value sent at once, {@code property-change} events,
 * {@code seek}/{@code playback-restart}/{@code file-loaded}/{@code end-file}, {@code quit}, and {@code sub-add}/
 * {@code audio-add} adding to {@code track-list}. Several clients at once, as mpv allows.
 *
 * <p>The server side uses synchronous pipe handles without ever blocking in a read: each client's thread peeks
 * ({@code PeekNamedPipe}) and reads only what is there, so its writes never wait behind a read. Java can't create a
 * named pipe, hence JNA (a test dependency only).
 *
 * <p>In-process ({@link #FakeMpvServer(String, Options)}) for {@code MpvIpcClientTest}, and as a process ({@link
 * #main}) that stands in for mpv in {@code PlaybackApiTest}: it reads {@code --input-ipc-server} and {@code --start}
 * from its arguments, records them like {@code FakeMpv}, plays a fake file in real time and exits as mpv does (0 after
 * {@code quit} or the end of the file, 2 when the file fails to load).
 */
public final class FakeMpvServer implements AutoCloseable {
    public static final class Options {
        /** The fake file's length in seconds. */
        public double duration = 100;
        public double start = 0;
        /** Seconds of the file per second of wall time. */
        public double speed = 1;
        /** {@code play}: plays; {@code error}: the file fails to load; {@code idle}: nothing happens until {@link #play()}. */
        public String mode = "play";
        /** Milliseconds from the start to {@code file-loaded}. */
        public long loadMillis = 300;
        /** With {@code play}: the position at which the file fails ({@code end-file} error, exit 2); negative: never. */
        public double failAt = -1;
        /** When the file fails: the status of the {@code HTTP error} line written to stdout (mpv's log); 0: none. */
        public int httpStatus = 0;
        /** With {@code play}: the position at which the file ends early as mpv ends one whose URL stopped answering
         * ({@code end-file} eof, exit 0); negative: never. */
        public double eofAt = -1;
    }

    private static final int PIPE_ACCESS_DUPLEX = 3;
    private static final int ERROR_PIPE_CONNECTED = 535;
    private static final Kernel32 K = Kernel32.INSTANCE;

    private final String path;
    private final Options options;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> tracks = new CopyOnWriteArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile boolean loaded;
    private volatile boolean playing;
    private volatile boolean ended;
    private volatile boolean eof;
    private volatile boolean paused;
    private volatile boolean muted;
    private volatile double position;
    private volatile int exitCode = 0;
    private volatile int connections;

    public FakeMpvServer(String pipeName, Options options) {
        this.path = "\\\\.\\pipe\\" + pipeName;
        this.options = options;
        this.position = options.start;
        Thread accept = new Thread(this::acceptLoop, "fake-mpv-accept");
        accept.setDaemon(true);
        accept.start();
        Thread timeline = new Thread(this::timeline, "fake-mpv-timeline");
        timeline.setDaemon(true);
        timeline.start();
    }

    /** Every line a client sent, in order. */
    public List<String> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    public int connections() {
        return connections;
    }

    public double position() {
        return position;
    }

    public boolean paused() {
        return paused;
    }

    public boolean muted() {
        return muted;
    }

    public List<Map<String, Object>> tracks() {
        return new ArrayList<>(tracks);
    }

    /** Waits until mpv would exit (a quit, the end of the file, a failed load); returns its exit code, or -1. */
    public int awaitExit(long millis) throws InterruptedException {
        return done.await(millis, TimeUnit.MILLISECONDS) ? exitCode : -1;
    }

    /** Sends a raw line to every client (a test can split or merge messages this way). */
    public void sendRaw(String text) {
        for (Client c : clients) c.out.add(text.getBytes(StandardCharsets.UTF_8));
    }

    public void broadcast(Map<String, Object> message) {
        sendRaw(Json.write(message) + "\n");
    }

    /** Closes every client's pipe, as mpv does when it exits. */
    public void dropClients() {
        for (Client c : clients) c.open = false;
    }

    /** {@code idle} mode: loads and starts playing now. */
    public void play() {
        load();
        playing = true;
        event("playback-restart");
    }

    /** Seeks as mpv does: {@code seek}, the new position, {@code playback-restart}. */
    public void seekTo(double seconds) {
        event("seek");
        position = Math.max(0, Math.min(seconds, options.duration));
        event("playback-restart");
        property("time-pos", position);
    }

    @Override
    public void close() {
        closed = true;
        dropClients();
        // wake an accept loop blocked in ConnectNamedPipe
        try (RandomAccessFile wake = new RandomAccessFile(path, "rw")) {
            wake.length();
        } catch (Exception ignored) {
            // no instance was waiting
        }
        done.countDown();
    }

    private void acceptLoop() {
        while (!closed) {
            HANDLE h = K.CreateNamedPipe(path, PIPE_ACCESS_DUPLEX, 0, 255, 65536, 65536, 0, null);
            if (h == null || WinBase.INVALID_HANDLE_VALUE.equals(h)) {
                sleep(20);
                continue;
            }
            boolean ok = K.ConnectNamedPipe(h, null) || K.GetLastError() == ERROR_PIPE_CONNECTED;
            if (!ok || closed) {
                K.CloseHandle(h);
                continue;
            }
            connections++;
            Client c = new Client(h);
            clients.add(c);
            Thread t = new Thread(c::run, "fake-mpv-client");
            t.setDaemon(true);
            t.start();
        }
    }

    private void timeline() {
        if (options.mode.equals("idle")) {
            while (!closed && !playing) sleep(5);
        } else {
            sleep(options.loadMillis);
        }
        if (closed) return;
        if (options.mode.equals("error")) {
            logHttpError();
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("event", "end-file");
            end.put("reason", "error");
            end.put("file_error", "loading failed");
            broadcast(end);
            finish(2);
            return;
        }
        if (!playing) play();
        long last = System.nanoTime();
        while (!closed && !ended) {
            sleep(50);
            long now = System.nanoTime();
            double step = (now - last) / 1e9 * options.speed;
            last = now;
            if (!playing || paused) continue;
            position = Math.min(options.duration, position + step);
            property("time-pos", position);
            if (options.eofAt >= 0 && position >= options.eofAt) {
                logHttpError();
                eof = true;
                property("eof-reached", true);
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("event", "end-file");
                end.put("reason", "eof");
                broadcast(end);
                finish(0);
                return;
            }
            if (options.failAt >= 0 && position >= options.failAt) {
                logHttpError();
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("event", "end-file");
                end.put("reason", "error");
                end.put("file_error", "loading failed");
                broadcast(end);
                finish(2);
                return;
            }
            if (position >= options.duration) {
                eof = true;
                property("eof-reached", true);
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("event", "end-file");
                end.put("reason", "eof");
                broadcast(end);
                finish(0);
            }
        }
    }

    /** What mpv logs at warn level for a refused URL (FFmpeg's http protocol). */
    private void logHttpError() {
        if (options.httpStatus == 0) return;
        System.out.println("[ffmpeg] https: HTTP error " + options.httpStatus + " Forbidden");
        System.out.println("[stream] Failed to open the URL.");
        System.out.flush();
    }

    private synchronized void load() {
        if (loaded) return;
        loaded = true;
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("id", 1);
        video.put("type", "video");
        video.put("selected", true);
        tracks.add(0, video);
        event("file-loaded");
        property("duration", options.duration);
        property("track-list", tracks());
    }

    private void finish(int code) {
        if (ended) return;
        ended = true;
        exitCode = code;
        // let the clients' threads write what is queued, then close the pipes as an exiting mpv does
        sleep(150);
        dropClients();
        done.countDown();
    }

    private void event(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", name);
        broadcast(m);
    }

    private void property(String name, Object value) {
        for (Client c : clients) {
            for (Map.Entry<Long, String> o : c.observed.entrySet()) {
                if (!o.getValue().equals(name)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("event", "property-change");
                m.put("id", o.getKey());
                m.put("name", name);
                m.put("data", value);
                c.out.add((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private Object value(String name) {
        switch (name) {
            case "time-pos":
                return loaded ? position : null;
            case "duration":
                return loaded ? options.duration : null;
            case "pause":
                return paused;
            case "mute":
                return muted;
            case "eof-reached":
                return eof;
            case "track-list":
                return tracks();
            default:
                throw new IllegalArgumentException("property unavailable");
        }
    }

    private final class Client {
        final HANDLE handle;
        final ConcurrentLinkedQueue<byte[]> out = new ConcurrentLinkedQueue<>();
        final Map<Long, String> observed = new ConcurrentHashMap<>();
        volatile boolean open = true;

        Client(HANDLE handle) {
            this.handle = handle;
        }

        void run() {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            IntByReference avail = new IntByReference();
            IntByReference n = new IntByReference();
            try {
                while (open) {
                    byte[] chunk;
                    while ((chunk = out.poll()) != null) {
                        if (!K.WriteFile(handle, chunk, chunk.length, n, null)) {
                            open = false;
                            break;
                        }
                    }
                    if (!open) break;
                    if (!K.PeekNamedPipe(handle, null, 0, null, avail, null)) break; // the client went away
                    int count = avail.getValue();
                    if (count == 0) {
                        sleep(2);
                        continue;
                    }
                    byte[] buf = new byte[count];
                    if (!K.ReadFile(handle, buf, count, n, null)) break;
                    for (int i = 0; i < n.getValue(); i++) {
                        if (buf[i] == '\n') {
                            handle(line.toString(StandardCharsets.UTF_8));
                            line.reset();
                        } else {
                            line.write(buf[i]);
                        }
                    }
                }
            } finally {
                clients.remove(this);
                K.FlushFileBuffers(handle);
                K.DisconnectNamedPipe(handle);
                K.CloseHandle(handle);
            }
        }

        void reply(Object requestId, String error, Object data) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (data != null) m.put("data", data);
            m.put("request_id", requestId == null ? 0L : requestId);
            m.put("error", error);
            out.add((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        void handle(String text) {
            received.add(text);
            Map<String, Object> msg;
            try {
                msg = (Map<String, Object>) Json.parse(text);
            } catch (RuntimeException e) {
                reply(null, "invalid parameter", null);
                return;
            }
            Object id = msg.get("request_id");
            List<Object> cmd = (List<Object>) msg.get("command");
            if (cmd == null || cmd.isEmpty()) {
                reply(id, "invalid parameter", null);
                return;
            }
            String name = String.valueOf(cmd.get(0));
            try {
                switch (name) {
                    case "observe_property": {
                        long oid = ((Number) cmd.get(1)).longValue();
                        String prop = String.valueOf(cmd.get(2));
                        observed.put(oid, prop);
                        reply(id, "success", null);
                        Object current = value(prop);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("event", "property-change");
                        m.put("id", oid);
                        m.put("name", prop);
                        if (current != null) m.put("data", current);
                        out.add((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                    case "get_property":
                        reply(id, "success", value(String.valueOf(cmd.get(1))));
                        break;
                    case "set_property": {
                        String prop = String.valueOf(cmd.get(1));
                        Object v = cmd.get(2);
                        if (prop.equals("pause")) {
                            paused = Boolean.TRUE.equals(v);
                            reply(id, "success", null);
                            property("pause", paused);
                        } else if (prop.equals("mute")) {
                            muted = Boolean.TRUE.equals(v);
                            reply(id, "success", null);
                        } else if (prop.equals("time-pos")) {
                            reply(id, "success", null);
                            seekTo(((Number) v).doubleValue());
                        } else {
                            reply(id, "property not found", null);
                        }
                        break;
                    }
                    case "seek": {
                        double to = ((Number) cmd.get(1)).doubleValue();
                        boolean absolute = cmd.size() > 2 && String.valueOf(cmd.get(2)).startsWith("absolute");
                        reply(id, "success", null);
                        seekTo(absolute ? to : position + to);
                        break;
                    }
                    case "sub-add":
                    case "audio-add": {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("id", tracks.size() + 1);
                        t.put("type", name.equals("sub-add") ? "sub" : "audio");
                        t.put("external", true);
                        t.put("external-filename", String.valueOf(cmd.get(1)));
                        t.put("selected", cmd.size() > 2 && "select".equals(cmd.get(2)));
                        if (cmd.size() > 3) t.put("title", String.valueOf(cmd.get(3)));
                        tracks.add(t);
                        reply(id, "success", null);
                        property("track-list", tracks());
                        break;
                    }
                    case "quit": {
                        reply(id, "success", null);
                        Map<String, Object> end = new LinkedHashMap<>();
                        end.put("event", "end-file");
                        end.put("reason", "quit");
                        broadcast(end);
                        int code = cmd.size() > 1 ? ((Number) cmd.get(1)).intValue() : 0;
                        Thread t = new Thread(() -> finish(code), "fake-mpv-quit");
                        t.setDaemon(true);
                        t.start();
                        break;
                    }
                    default:
                        reply(id, "invalid parameter", null);
                }
            } catch (IllegalArgumentException e) {
                reply(id, e.getMessage(), null);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * As mpv: {@code --input-ipc-server=\\.\pipe\<name>}, {@code --start=<s>}; system properties {@code
     * fakempv.duration}, {@code fakempv.speed}, {@code fakempv.mode}, {@code fakempv.loadMillis}. Records its arguments
     * and include file to {@code <tmp>/madoyomi-fake-mpv/<pipe name>.txt} (as {@code FakeMpv}) and, at the exit, the
     * lines the clients sent to {@code <pipe name>.received}; exits as mpv would, after two minutes at the latest.
     *
     * <p>The URL (after {@code --}) can make the file fail, as a refused or expired URL does (Stage 4): {@code
     * fakempv-error=<status>} fails to load, and {@code fakempv-fail-at=<seconds>} fails once the position gets
     * there (with {@code fakempv-status=<status>}); {@code fakempv-eof-at=<seconds>} ends the file there as mpv
     * does when a URL stops answering mid-play. With a status other than 0, FFmpeg's {@code HTTP error <status>}
     * line goes to stdout (mpv's log) when it fails.
     */
    public static void main(String[] args) throws Exception {
        String pipe = null;
        String include = null;
        String url = "";
        Options o = new Options();
        StringBuilder record = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--") && i + 1 < args.length) url = args[i + 1];
            if (arg.startsWith("--input-ipc-server=")) pipe = arg.substring("--input-ipc-server=".length());
            if (arg.startsWith("--include=")) include = arg.substring("--include=".length());
            if (arg.startsWith("--start=")) o.start = Double.parseDouble(arg.substring("--start=".length()));
            record.append("ARG ").append(arg).append('\n');
        }
        o.duration = Double.parseDouble(System.getProperty("fakempv.duration", "100"));
        o.speed = Double.parseDouble(System.getProperty("fakempv.speed", "1"));
        o.mode = System.getProperty("fakempv.mode", "play");
        o.loadMillis = Long.parseLong(System.getProperty("fakempv.loadMillis", "300"));
        String status = urlParam(url, "fakempv-error");
        String failAt = urlParam(url, "fakempv-fail-at");
        if (status != null) o.mode = "error";
        if (failAt != null) o.failAt = Double.parseDouble(failAt);
        String eofAt = urlParam(url, "fakempv-eof-at");
        if (eofAt != null) o.eofAt = Double.parseDouble(eofAt);
        String httpStatus = status != null ? status : urlParam(url, "fakempv-status");
        if (httpStatus != null) o.httpStatus = Integer.parseInt(httpStatus);
        record.append("TOKEN ").append(System.getenv("MADOYOMI_TOKEN") != null).append('\n');
        record.append("INCLUDE\n").append(include == null ? "" : Files.readString(Path.of(include)));
        String name = pipe.substring(pipe.lastIndexOf('\\') + 1);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "madoyomi-fake-mpv");
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".tmp");
        Files.writeString(tmp, record);
        Files.move(tmp, dir.resolve(name + ".txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        FakeMpvServer server = new FakeMpvServer(name, o);
        int code = server.awaitExit(120_000);
        // what the clients sent, for the test to read after the exit
        Path sent = dir.resolve(name + ".received.tmp");
        Files.writeString(sent, String.join("\n", server.received()) + "\n");
        Files.move(sent, dir.resolve(name + ".received"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.exit(code < 0 ? 4 : code);
    }

    private static String urlParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String part : url.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) return part.substring(eq + 1);
        }
        return null;
    }

    /** Just enough JSON for mpv's IPC: objects, arrays, strings, numbers, true/false/null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json p = new Json(text);
            Object v = p.value();
            p.ws();
            if (p.i != text.length()) throw new IllegalArgumentException("trailing data");
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = (String) value();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') {
                i++;
                StringBuilder b = new StringBuilder();
                while (true) {
                    char ch = s.charAt(i++);
                    if (ch == '"') return b.toString();
                    if (ch == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n': b.append('\n'); break;
                            case 't': b.append('\t'); break;
                            case 'r': b.append('\r'); break;
                            case 'b': b.append('\b'); break;
                            case 'f': b.append('\f'); break;
                            case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                            default: b.append(e);
                        }
                    } else {
                        b.append(ch);
                    }
                }
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalArgumentException("bad JSON at " + start);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        @SuppressWarnings("unchecked")
        static String write(Object v) {
            if (v == null) return "null";
            if (v instanceof String) {
                StringBuilder b = new StringBuilder("\"");
                for (char c : ((String) v).toCharArray()) {
                    if (c == '"' || c == '\\') b.append('\\').append(c);
                    else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
                return b.append('"').toString();
            }
            if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                return String.format(Locale.ROOT, "%.6f", d);
            }
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
            if (v instanceof Map) {
                StringBuilder b = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append(write(e.getKey())).append(':').append(write(e.getValue()));
                }
                return b.append('}').toString();
            }
            if (v instanceof List) {
                StringBuilder b = new StringBuilder("[");
                boolean first = true;
                for (Object o : (List<Object>) v) {
                    if (!first) b.append(',');
                    first = false;
                    b.append(write(o));
                }
                return b.append(']').toString();
            }
            throw new IllegalArgumentException("can't write " + v.getClass());
        }
    }
}
