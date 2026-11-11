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

async function forward(req, res, upstream, headers) {
  const up = await fetch(upstream, { headers, redirect: "follow" });
  const out = {};
  for (const h of ["content-type", "content-length", "content-range", "accept-ranges"]) {
    const v = up.headers.get(h);
    if (v) out[h] = v;
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
  const token = url.pathname.slice("/expire/".length);
  const upstream = url.searchParams.get("u");
  const after = url.searchParams.has("after") ? Number(url.searchParams.get("after")) : Infinity;
  const limit = url.searchParams.has("requests") ? Number(url.searchParams.get("requests")) : Infinity;
  if (!token || !upstream) return res.writeHead(400).end("token and u= required");
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
    const status = await forward(req, res, upstream, headers);
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
