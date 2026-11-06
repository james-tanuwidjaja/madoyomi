import type { SocketFactory, SocketLike } from "../api/ws";

/** A WebSocket stand-in: records what the client sends, and lets a test play the server. */
export class FakeSocket implements SocketLike {
  readyState = 0;
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: { code: number; reason: string }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  readonly sent: Record<string, unknown>[] = [];
  closedWith: { code?: number; reason?: string } | null = null;

  constructor(
    readonly url: string,
    /** Everything the factory was called with (the client must pass the URL only). */
    readonly args: unknown[],
  ) {}

  send(data: string) {
    this.sent.push(JSON.parse(data));
  }

  close(code?: number, reason?: string) {
    this.closedWith = { code, reason };
    this.readyState = 3;
  }

  // --- the server's side

  open() {
    this.readyState = 1;
    this.onopen?.({});
  }

  receive(message: Record<string, unknown>) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  ack() {
    this.receive({ type: "connection_ack" });
  }

  /** The connection drops (the backend died). */
  drop(code = 1006) {
    this.readyState = 3;
    this.onclose?.({ code, reason: "" });
  }

  sentOfType(type: string) {
    return this.sent.filter((m) => m.type === type);
  }
}

/** A factory that keeps every socket it made. */
export function fakeSocketFactory() {
  const sockets: FakeSocket[] = [];
  const create: SocketFactory = (...args: unknown[]) => {
    const s = new FakeSocket(String(args[0]), args);
    sockets.push(s);
    return s;
  };
  return {
    create,
    sockets,
    get last(): FakeSocket {
      const s = sockets[sockets.length - 1];
      if (!s) throw new Error("no socket was opened");
      return s;
    },
  };
}
