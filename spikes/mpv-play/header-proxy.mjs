// Phase 0 / S4 helper: a local reverse proxy that refuses requests without the expected headers.
// It stands in for a source that 403s without Referer/User-Agent, so we can check that mpv sends
// the headers on every request: the MP4 range requests, the HLS playlists, every HLS segment and
// the subtitle file.
//
//   node header-proxy.mjs [port]
//
//   /mp4            -> public 10 s Big Buck Bunny MP4 (range requests forwarded)
//   /hls/<path>     -> https://test-streams.mux.dev/x36xhzz/<path> (HLS master + variants + segments)
//   /subs.srt       -> a small generated SubRip file
//
// Required headers: Referer: https://madoyomi.test/  and  User-Agent containing "MadoyomiSpike".
//
// Expiring mode (Phase 4 Stage 4), for the player's retry on an expired URL. No header check: the request's headers
// are forwarded to the upstream URL (minus host/connection), and logged once per token:
//
//   /expire/<token>?u=<upstream URL>&after=<seconds>&requests=<n>
//
// Each <token> answers like the upstream (status, body, ranges) until `after` seconds have passed since its first
// request, or it has served `requests` requests; then 403 for good. `after=0` (or `requests=0`) is expired from the
// start; without either it never expires. A new token starts fresh, as a refetched URL would.
//
// An HLS playlist (Phase 4 Stage 6) is rewritten so that every URI in it (variant playlists, segments, keys, media
// renditions) goes through the same token, absolute: the stream's segments then expire with it, mid-play. The
// rewritten URL keeps the upstream file name (/expire/<token>/<name>?u=...), and an `image/*` type is not passed on:
// some hosts serve their TS segments as .jpg/.png, which FFmpeg then takes for images when it has no extension.
import http from "node:http";

const port = Number(process.argv[2] ?? 18080);
const REFERER = "https://madoyomi.test/";
const UA_MARK = "MadoyomiSpike";
const MP4 = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4";
const HLS_BASE = "https://test-streams.mux.dev/x36xhzz/";
const SRT = `1
00:00:00,000 --> 00:00:05,000
Madoyomi spike subtitle (HTTP)

2
00:00:05,000 --> 00:00:30,000
Headers reached the subtitle request too
`;

const stats = { ok: 0, rejected: 0 };

/** token -> { first: ms of its first request, served: requests answered from upstream, expired: logged once } */
const tokens = new Map();
const HOP_BY_HOP = new Set(["host", "connection", "keep-alive", "transfer-encoding", "upgrade", "proxy-connection", "accept-encoding"]);

/** An HLS playlist's URIs, each made absolute and sent through `proxied(url)`. */
function rewritePlaylist(text, base, proxied) {
  const abs = (u) => proxied(new URL(u, base).href);
  return text
    .split(/\r?\n/)
    .map((line) => {
      if (line.startsWith("#")) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${abs(u)}"`);
      return line.trim() ? abs(line.trim()) : line;
    })
    .join("\n");
}

async function forward(req, res, upstream, headers, proxied) {
  const up = await fetch(upstream, { headers, redirect: "follow" });
  const out = {};
  for (const h of ["content-type", "content-length", "content-range", "accept-ranges"]) {
    const v = up.headers.get(h);
    if (v) out[h] = v;
  }
  const type = up.headers.get("content-type") ?? "";
  if (proxied && /^image\//i.test(type)) delete out["content-type"];
  // a playlist by its type, its name, or (a small text-like body) its first line
  const small = Number(up.headers.get("content-length") ?? 0) < 4_000_000 && !/^(video|audio|image)\//i.test(type);
  let text = null;
  if (proxied && up.ok && (/mpegurl/i.test(type) || /\.m3u8($|\?)/i.test(new URL(up.url).pathname) || small)) {
    const raw = Buffer.from(await up.arrayBuffer());
    if (!raw.subarray(0, 16).toString("utf8").replace(/^\uFEFF/, "").startsWith("#EXTM3U")) {
      res.writeHead(up.status, out);
      res.end(req.method === "HEAD" ? undefined : raw);
      return up.status;
    }
    text = raw.toString("utf8");
  }
  if (text !== null) {
    const body = Buffer.from(rewritePlaylist(text, up.url, proxied), "utf8");
    if (process.env.PROXY_DEBUG) console.log(`PLAYLIST ${up.url}\n${text.slice(0, 600)}\n-> ${body.toString("utf8").slice(0, 600)}`);
    delete out["content-range"];
    out["content-length"] = String(body.length);
    res.writeHead(up.status, out);
    res.end(req.method === "HEAD" ? undefined : body);
    return up.status;
  }
  res.writeHead(up.status, out);
  if (req.method === "HEAD" || !up.body) return res.end();
  try {
    for await (const chunk of up.body) {
      if (!res.write(chunk)) await new Promise((r) => res.once("drain", r));
    }
  } catch {
    // the client went away (mpv seeks by closing the connection)
  }
  res.end();
  return up.status;
}

async function expiring(req, res) {
  const url = new URL(req.url, "http://127.0.0.1");
  if (process.env.PROXY_DEBUG) console.log(`REQ ${req.method} ${url.pathname} range=${req.headers["range"] ?? "-"}`);
  const token = url.pathname.slice("/expire/".length).split("/")[0];
  const upstream = url.searchParams.get("u");
  const after = url.searchParams.has("after") ? Number(url.searchParams.get("after")) : Infinity;
  const limit = url.searchParams.has("requests") ? Number(url.searchParams.get("requests")) : Infinity;
  if (!token || !upstream) {
    console.log(`400 ${req.method} ${req.url.slice(0, 120)}`);
    return res.writeHead(400).end("token and u= required");
  }
  let t = tokens.get(token);
  if (!t) {
    t = { first: Date.now(), served: 0, expired: false };
    tokens.set(token, t);
    // what reached us, once per token: the header measurement (values of cookies and authorization left out)
    const seen = Object.entries(req.headers).map(([k, v]) =>
      k === "cookie" || k === "authorization" ? `${k}: <${String(v).length} chars>` : `${k}: ${v}`,
    );
    console.log(`NEW ${token} after=${after} requests=${limit} host=${new URL(upstream).host}\n  ${seen.join("\n  ")}`);
  }
  const age = (Date.now() - t.first) / 1000;
  if (age >= after || t.served >= limit) {
    stats.rejected++;
    if (!t.expired) console.log(`EXP ${token} after ${age.toFixed(1)} s and ${t.served} requests`);
    t.expired = true;
    console.log(`403 ${req.method} ${token} range=${req.headers["range"] ?? "-"}`);
    return res.writeHead(403).end("expired");
  }
  t.served++;
  stats.ok++;
  const headers = {};
  for (const [k, v] of Object.entries(req.headers)) if (!HOP_BY_HOP.has(k)) headers[k] = v;
  try {
    const extra = ["after", "requests"].filter((k) => url.searchParams.has(k)).map((k) => `&${k}=${url.searchParams.get(k)}`).join("");
    const proxied = (u) => {
      const name = encodeURIComponent(new URL(u).pathname.split("/").pop() || "x");
      return `http://127.0.0.1:${port}/expire/${token}/${name}?u=${encodeURIComponent(u)}${extra}`;
    };
    const status = await forward(req, res, upstream, headers, proxied);
    console.log(`${status} ${req.method} ${token} range=${req.headers["range"] ?? "-"} (#${t.served}, ${age.toFixed(1)} s)`);
  } catch (e) {
    console.log(`ERR ${token} ${e}`);
    if (!res.headersSent) res.writeHead(502);
    res.end();
  }
}

const server = http.createServer(async (req, res) => {
  if (req.url.startsWith("/expire/")) return expiring(req, res);
  const referer = req.headers["referer"] ?? "";
  const ua = req.headers["user-agent"] ?? "";
  const pass = referer === REFERER && ua.includes(UA_MARK);
  const tag = pass ? "OK " : "403";
  const custom = Object.entries(req.headers)
    .filter(([k]) => k.startsWith("x-"))
    .map(([k, v]) => `${k}=${JSON.stringify(v)}`)
    .join(" ");
  console.log(
    `${tag} ${req.method} ${req.url} referer=${JSON.stringify(referer)} ua=${JSON.stringify(ua)} range=${req.headers["range"] ?? "-"} ${custom}`,
  );
  if (!pass) {
    stats.rejected++;
    res.writeHead(403).end("missing headers");
    return;
  }
  stats.ok++;

  if (req.url === "/subs.srt") {
    res.writeHead(200, { "content-type": "application/x-subrip" }).end(SRT);
    return;
  }
  if (req.url === "/stats") {
    res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(stats));
    return;
  }

  let upstream;
  if (req.url === "/mp4") upstream = MP4;
  else if (req.url.startsWith("/hls/")) upstream = HLS_BASE + req.url.slice("/hls/".length);
  else {
    res.writeHead(404).end();
    return;
  }

  try {
    const headers = {};
    if (req.headers["range"]) headers["range"] = req.headers["range"];
    const up = await fetch(upstream, { headers });
    const out = {};
    for (const h of ["content-type", "content-length", "content-range", "accept-ranges"]) {
      const v = up.headers.get(h);
      if (v) out[h] = v;
    }
    res.writeHead(up.status, out);
    if (req.method === "HEAD" || !up.body) return res.end();
    for await (const chunk of up.body) {
      if (!res.write(chunk)) await new Promise((r) => res.once("drain", r));
    }
    res.end();
  } catch (e) {
    console.log(`ERR ${req.url} ${e}`);
    if (!res.headersSent) res.writeHead(502);
    res.end();
  }
});

server.listen(port, "127.0.0.1", () => console.log(`header-proxy listening on http://127.0.0.1:${port}`));
