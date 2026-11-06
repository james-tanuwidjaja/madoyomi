import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSubscriptionClient, type SubscriptionClient, type SubscriptionState } from "../ws";
import { fakeSocketFactory } from "../../test/fakeSocket";

const URL_A = "ws://127.0.0.1:50266/api/graphql?token=t";
const URL_B = "ws://127.0.0.1:61000/api/graphql?token=t";
const QUERY = "subscription { extensionStoreRefreshed { indexUrl } }";

let sockets: ReturnType<typeof fakeSocketFactory>;
let client: SubscriptionClient;
let states: SubscriptionState[];
let connected: { first: boolean; url: string }[];

function make(overrides: Parameters<typeof createSubscriptionClient>[0] = {}) {
  client = createSubscriptionClient({
    createSocket: sockets.create,
    backoffMs: [500, 1000, 2000],
    ackTimeoutMs: 3000,
    pingIntervalMs: 0,
    onStateChange: (s) => states.push(s),
    onConnected: (c) => connected.push(c),
    ...overrides,
  });
  return client;
}

/** Opens and acks the newest socket. */
function handshake() {
  sockets.last.open();
  sockets.last.ack();
}

beforeEach(() => {
  vi.useFakeTimers();
  sockets = fakeSocketFactory();
  states = [];
  connected = [];
});

afterEach(() => {
  client?.dispose();
  vi.useRealTimers();
});

describe("graphql-transport-ws client", () => {
  it("opens the socket with the URL only, no subprotocol", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });

    expect(sockets.sockets).toHaveLength(1);
    expect(sockets.last.url).toBe(URL_A);
    expect(sockets.last.args).toEqual([URL_A]);
  });

  it("stays closed without an endpoint or without a subscription", () => {
    make();
    client.subscribe(QUERY, {}, { next: () => {} });
    expect(sockets.sockets).toHaveLength(0);

    const other = createSubscriptionClient({ createSocket: sockets.create });
    other.setEndpoint(URL_A);
    expect(sockets.sockets).toHaveLength(0);
    other.dispose();
  });

  it("sends connection_init, and the subscriptions once acked", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, { type: "ANIME" }, { next: () => {} });
    sockets.last.open();

    expect(sockets.last.sent).toEqual([{ type: "connection_init", payload: {} }]);

    sockets.last.ack();
    expect(sockets.last.sentOfType("subscribe")).toEqual([
      { id: "1", type: "subscribe", payload: { query: QUERY, variables: { type: "ANIME" } } },
    ]);
    expect(client.state).toBe("connected");
    expect(states).toEqual(["connecting", "connected"]);
    expect(connected).toEqual([{ first: true, url: URL_A }]);
  });

  it("routes next, error and complete to their own subscription", () => {
    make().setEndpoint(URL_A);
    const a = { next: vi.fn(), error: vi.fn(), complete: vi.fn() };
    const b = { next: vi.fn(), error: vi.fn(), complete: vi.fn() };
    client.subscribe(QUERY, {}, a);
    client.subscribe(QUERY, {}, b);
    handshake();

    sockets.last.receive({ id: "1", type: "next", payload: { data: { n: 1 } } });
    sockets.last.receive({
      id: "2",
      type: "error",
      payload: [{ message: "denied", extensions: { code: "FORBIDDEN" } }],
    });
    sockets.last.receive({ id: "1", type: "complete" });

    expect(a.next).toHaveBeenCalledWith({ n: 1 });
    expect(a.complete).toHaveBeenCalledOnce();
    expect(b.next).not.toHaveBeenCalled();
    expect(b.error).toHaveBeenCalledOnce();
    expect(b.error.mock.calls[0][0]).toMatchObject({ code: "FORBIDDEN", message: "denied" });
  });

  it("reports GraphQL errors inside a next payload as an error", () => {
    make().setEndpoint(URL_A);
    const sink = { next: vi.fn(), error: vi.fn() };
    client.subscribe(QUERY, {}, sink);
    handshake();

    sockets.last.receive({
      id: "1",
      type: "next",
      payload: { errors: [{ message: "boom", extensions: { code: "INTERNAL" } }] },
    });

    expect(sink.next).not.toHaveBeenCalled();
    expect(sink.error.mock.calls[0][0]).toMatchObject({ code: "INTERNAL" });
  });

  it("answers the server's ping with a pong", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });
    handshake();

    sockets.last.receive({ type: "ping" });

    expect(sockets.last.sentOfType("pong")).toEqual([{ type: "pong" }]);
  });

  it("gives up on a socket that never acks, and tries again", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });
    sockets.last.open();

    vi.advanceTimersByTime(3000);
    expect(sockets.sockets[0].closedWith?.code).toBe(4408);

    vi.advanceTimersByTime(500);
    expect(sockets.sockets).toHaveLength(2);
  });

  it("reconnects with growing delays and subscribes again after a drop", () => {
    make().setEndpoint(URL_A);
    const sink = { next: vi.fn() };
    client.subscribe(QUERY, {}, sink);
    handshake();

    sockets.last.drop();
    expect(client.state).toBe("connecting");
    vi.advanceTimersByTime(499);
    expect(sockets.sockets).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(sockets.sockets).toHaveLength(2);

    sockets.last.drop(); // refused: the backend is still down
    vi.advanceTimersByTime(999);
    expect(sockets.sockets).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(sockets.sockets).toHaveLength(3);

    handshake();
    expect(sockets.last.sentOfType("subscribe")).toHaveLength(1);
    expect(connected.map((c) => c.first)).toEqual([true, false]);

    sockets.last.receive({ id: "1", type: "next", payload: { data: { n: 2 } } });
    expect(sink.next).toHaveBeenCalledWith({ n: 2 });

    // a successful ack starts the backoff over
    sockets.last.drop();
    vi.advanceTimersByTime(500);
    expect(sockets.sockets).toHaveLength(4);
  });

  it("moves to a new endpoint at once (a restart's new port)", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });
    handshake();
    const first = sockets.last;

    client.setEndpoint(URL_B);

    expect(first.closedWith?.code).toBe(1000);
    expect(sockets.sockets).toHaveLength(2);
    expect(sockets.last.url).toBe(URL_B);
    handshake();
    expect(sockets.last.sentOfType("subscribe")).toHaveLength(1);
  });

  it("closes and waits while there is no endpoint (the backend is down)", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });
    handshake();

    client.setEndpoint(null);
    expect(sockets.last.closedWith).not.toBeNull();
    expect(client.state).toBe("idle");
    vi.advanceTimersByTime(60_000);
    expect(sockets.sockets).toHaveLength(1);

    client.setEndpoint(URL_B);
    expect(sockets.sockets).toHaveLength(2);
  });

  it("ignores a late close of a replaced socket", () => {
    make().setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });
    handshake();
    const old = sockets.last;
    client.setEndpoint(URL_B);

    old.drop();
    vi.advanceTimersByTime(10_000);

    expect(sockets.sockets).toHaveLength(2);
  });

  it("sends complete on unsubscribe, and closes when nothing is left", () => {
    make().setEndpoint(URL_A);
    const stopA = client.subscribe(QUERY, {}, { next: () => {} });
    const stopB = client.subscribe(QUERY, {}, { next: () => {} });
    handshake();

    stopA();
    expect(sockets.last.sentOfType("complete")).toEqual([{ id: "1", type: "complete" }]);
    expect(sockets.last.closedWith).toBeNull();

    stopB();
    expect(sockets.last.closedWith?.code).toBe(1000);
    expect(client.state).toBe("idle");
  });

  it("pings, and treats a missing pong as a dead socket", () => {
    make({ pingIntervalMs: 1000, pongTimeoutMs: 500 }).setEndpoint(URL_A);
    client.subscribe(QUERY, {}, { next: () => {} });
    handshake();

    vi.advanceTimersByTime(1000);
    expect(sockets.last.sentOfType("ping")).toHaveLength(1);
    sockets.last.receive({ type: "pong" });

    vi.advanceTimersByTime(1000);
    expect(sockets.last.sentOfType("ping")).toHaveLength(2);
    vi.advanceTimersByTime(500);
    expect(sockets.sockets[0].closedWith?.code).toBe(4504);

    vi.advanceTimersByTime(500);
    expect(sockets.sockets).toHaveLength(2);
  });
});
