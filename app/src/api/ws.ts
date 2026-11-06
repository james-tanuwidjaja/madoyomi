import type { GraphQLErrorJson } from "./types";
import { ApiError } from "./client";

/**
 * A graphql-transport-ws client (Phase 3 step 7; the protocol of
 * https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md), written here rather than taken from the
 * `graphql-ws` library: that library always asks for the `graphql-transport-ws` subprotocol, and the backend
 * negotiates none, so a browser would fail the handshake (server/UPSTREAM.md "Startup contract"). The token goes
 * in the URL (`?token=`), because a browser WebSocket can't send headers.
 *
 * - One socket for all subscriptions, opened while there is at least one and an endpoint is set.
 * - `connection_init`, then every active subscription is (re)sent once `connection_ack` arrives.
 * - A lost socket reconnects with backoff; `setEndpoint` with a new URL (a backend restart's new port) reconnects at
 *   once, and `setEndpoint(null)` (the backend is down) closes it and waits.
 * - The server's `ping` is answered; the client pings too, and a missing `pong` counts as a dead socket.
 * - The server replays nothing, so `onConnected` tells the caller after each reconnect that events may have been
 *   missed.
 */

/** The subset of the browser `WebSocket` this client uses (a fake one in tests). */
export interface SocketLike {
  readonly readyState: number;
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: { code: number; reason: string }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

/** Constructed with the URL only: never with a protocols argument. */
export type SocketFactory = (url: string) => SocketLike;

export interface SubscriptionSink<T> {
  next(data: T): void;
  /** The operation failed (GraphQL errors, or the server's `error` message). It is not resent. */
  error?(error: ApiError): void;
  /** The server ended the operation. */
  complete?(): void;
}

export type SubscriptionState = "idle" | "connecting" | "connected";

export interface SubscriptionClientOptions {
  createSocket?: SocketFactory;
  /** Delays before reconnect attempts 1, 2, …; the last one repeats. */
  backoffMs?: number[];
  ackTimeoutMs?: number;
  pingIntervalMs?: number;
  pongTimeoutMs?: number;
  onStateChange?(state: SubscriptionState): void;
  /** After every `connection_ack`; `first` is false for a reconnect (events may have been missed in between). */
  onConnected?(info: { first: boolean; url: string }): void;
}

export interface SubscriptionClient {
  setEndpoint(url: string | null): void;
  subscribe<T>(
    query: string,
    variables: Record<string, unknown> | undefined,
    sink: SubscriptionSink<T>,
  ): () => void;
  readonly state: SubscriptionState;
  /** Closes the socket and forgets every subscription. */
  dispose(): void;
}

const OPEN = 1;
/** Close codes this client uses for itself (4xxx is the application range). */
const CLOSE_NORMAL = 1000;
const CLOSE_ACK_TIMEOUT = 4408;
const CLOSE_NO_PONG = 4504;

interface Operation {
  id: string;
  payload: { query: string; variables: Record<string, unknown> };
  sink: SubscriptionSink<unknown>;
}

const defaultSocket: SocketFactory = (url) => new WebSocket(url) as unknown as SocketLike;

export function createSubscriptionClient(
  options: SubscriptionClientOptions = {},
): SubscriptionClient {
  const createSocket = options.createSocket ?? defaultSocket;
  const backoff = options.backoffMs ?? [500, 1000, 2000, 4000, 8000, 10000];
  const ackTimeoutMs = options.ackTimeoutMs ?? 10_000;
  const pingIntervalMs = options.pingIntervalMs ?? 20_000;
  const pongTimeoutMs = options.pongTimeoutMs ?? 10_000;

  const operations = new Map<string, Operation>();
  let nextId = 1;
  let endpoint: string | null = null;
  let socket: SocketLike | null = null;
  let acked = false;
  let everConnected = false;
  let attempt = 0;
  let state: SubscriptionState = "idle";
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let ackTimer: ReturnType<typeof setTimeout> | null = null;
  let pingTimer: ReturnType<typeof setTimeout> | null = null;
  let pongTimer: ReturnType<typeof setTimeout> | null = null;

  function setState(next: SubscriptionState) {
    if (state === next) return;
    state = next;
    options.onStateChange?.(next);
  }

  function clearTimers() {
    for (const t of [reconnectTimer, ackTimer, pingTimer, pongTimer]) if (t) clearTimeout(t);
    reconnectTimer = ackTimer = pingTimer = pongTimer = null;
  }

  function send(message: Record<string, unknown>) {
    if (socket && socket.readyState === OPEN) socket.send(JSON.stringify(message));
  }

  /** Detaches and closes the current socket; its late events are ignored. */
  function drop(code = CLOSE_NORMAL, reason = "") {
    clearTimers();
    const s = socket;
    socket = null;
    acked = false;
    if (s) {
      s.onopen = s.onmessage = s.onclose = s.onerror = null;
      try {
        s.close(code, reason);
      } catch {
        // already closed
      }
    }
  }

  function wanted() {
    return endpoint !== null && operations.size > 0;
  }

  function connect() {
    if (socket || !wanted() || endpoint === null) return;
    const url = endpoint;
    setState("connecting");
    const s = createSocket(url);
    socket = s;
    s.onopen = () => {
      send({ type: "connection_init", payload: {} });
      ackTimer = setTimeout(() => lost(CLOSE_ACK_TIMEOUT, "connection_ack timeout"), ackTimeoutMs);
    };
    s.onmessage = (ev) => onMessage(ev.data, url);
    s.onclose = () => lost();
    s.onerror = () => {
      // A close event follows.
    };
  }

  /** The socket died (or we gave up on it): reconnect after the next backoff delay. */
  function lost(code?: number, reason?: string) {
    drop(code ?? CLOSE_NORMAL, reason ?? "");
    if (!wanted()) {
      setState("idle");
      return;
    }
    setState("connecting");
    const delay = backoff[Math.min(attempt, backoff.length - 1)];
    attempt++;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function schedulePing() {
    if (pingIntervalMs <= 0) return;
    pingTimer = setTimeout(() => {
      pingTimer = null;
      send({ type: "ping" });
      pongTimer = setTimeout(() => lost(CLOSE_NO_PONG, "no pong"), pongTimeoutMs);
    }, pingIntervalMs);
  }

  function onMessage(raw: unknown, url: string) {
    let msg: { type?: string; id?: string; payload?: unknown };
    try {
      msg = JSON.parse(String(raw));
    } catch {
      return;
    }
    switch (msg.type) {
      case "connection_ack": {
        if (ackTimer) clearTimeout(ackTimer);
        ackTimer = null;
        acked = true;
        attempt = 0;
        setState("connected");
        for (const op of operations.values())
          send({ id: op.id, type: "subscribe", payload: op.payload });
        const first = !everConnected;
        everConnected = true;
        schedulePing();
        options.onConnected?.({ first, url });
        break;
      }
      case "ping":
        send({ type: "pong" });
        break;
      case "pong":
        if (pongTimer) clearTimeout(pongTimer);
        pongTimer = null;
        schedulePing();
        break;
      case "next": {
        const op = msg.id ? operations.get(msg.id) : undefined;
        if (!op) return;
        const payload = msg.payload as { data?: unknown; errors?: GraphQLErrorJson[] } | undefined;
        if (payload?.errors?.length) {
          failOperation(op, payload.errors);
        } else if (payload?.data !== undefined) {
          op.sink.next(payload.data);
        }
        break;
      }
      case "error": {
        const op = msg.id ? operations.get(msg.id) : undefined;
        if (op)
          failOperation(op, Array.isArray(msg.payload) ? (msg.payload as GraphQLErrorJson[]) : []);
        break;
      }
      case "complete": {
        const op = msg.id ? operations.get(msg.id) : undefined;
        if (!op) return;
        operations.delete(op.id);
        op.sink.complete?.();
        if (operations.size === 0) {
          drop();
          setState("idle");
        }
        break;
      }
    }
  }

  function failOperation(op: Operation, errors: GraphQLErrorJson[]) {
    operations.delete(op.id);
    const first = errors[0];
    op.sink.error?.(
      new ApiError({
        kind: "graphql",
        code: typeof first?.extensions?.code === "string" ? first.extensions.code : "BAD_REQUEST",
        message: first?.message ?? "Subscription failed",
        details: first?.extensions?.details ?? null,
        path: first?.path ?? null,
      }),
    );
  }

  return {
    setEndpoint(url) {
      if (url === endpoint) return;
      endpoint = url;
      attempt = 0;
      drop();
      if (wanted()) connect();
      else setState("idle");
    },

    subscribe<T>(
      query: string,
      variables: Record<string, unknown> | undefined,
      sink: SubscriptionSink<T>,
    ) {
      const op: Operation = {
        id: String(nextId++),
        payload: { query, variables: variables ?? {} },
        sink: sink as SubscriptionSink<unknown>,
      };
      operations.set(op.id, op);
      if (acked) send({ id: op.id, type: "subscribe", payload: op.payload });
      else if (!socket && !reconnectTimer) connect();
      return () => {
        if (!operations.delete(op.id)) return;
        if (acked) send({ id: op.id, type: "complete" });
        if (operations.size === 0) {
          drop();
          setState("idle");
        }
      };
    },

    get state() {
      return state;
    },

    dispose() {
      operations.clear();
      endpoint = null;
      drop();
      setState("idle");
    },
  };
}
