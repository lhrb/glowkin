import { lstatSync, mkdirSync, chmodSync } from "node:fs";
import { createConnection, createServer, type Socket } from "node:net";
import { join } from "node:path";
import { parseRequest, REQUEST_LIMIT, RESPONSE_LIMIT, type Reply, type Request } from "./chat.ts";

const UNAVAILABLE = '{"status":"unavailable"}\n';
const TIMEOUT = 1750;

export function prepareSocketDirectory(): string | undefined {
  const uid = process.geteuid?.();
  if (uid === undefined) return;
  const directory = `/tmp/glowkin-chat-${uid}`;
  try {
    try {
      mkdirSync(directory, { mode: 0o700 });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") return;
    }
    const info = lstatSync(directory);
    if (info.isDirectory() && info.uid === uid && (info.mode & 0o7777) === 0o700) return directory;
  } catch {
    // Untrusted or unavailable local endpoint.
  }
}

export function activeEndpointGone(directory: string, marker: string): Promise<boolean> | boolean {
  const match = /^active:([a-f0-9]{32})$/.exec(marker);
  if (!match) return marker === "" || /^exited:[a-f0-9]{32}$/.test(marker);
  const path = join(directory, `${match[1]}.sock`);
  return new Promise((resolve) => {
    const socket = createConnection(path);
    const timer = setTimeout(() => finish(false), 250);
    function finish(gone: boolean) {
      clearTimeout(timer);
      socket.destroy();
      resolve(gone);
    }
    socket.once("connect", () => finish(false));
    socket.once("error", (error: NodeJS.ErrnoException) => {
      finish(error.code === "ENOENT" || error.code === "ECONNREFUSED");
    });
  });
}

export async function openEndpoint(
  directory: string,
  instanceId: string,
  dispatch: (request: Request, connected: () => boolean) => Promise<Reply>,
): Promise<(() => void) | undefined> {
  const path = join(directory, `${instanceId}.sock`);
  const clients = new Set<Socket>();
  const server = createServer({ allowHalfOpen: true }, (socket) => {
    clients.add(socket);
    const timer = setTimeout(() => socket.destroy(), TIMEOUT);
    socket.once("close", () => {
      clients.delete(socket);
      clearTimeout(timer);
    });
    socket.on("error", () => socket.destroy());

    let chunks: Buffer[] = [];
    let bytes = 0;
    function receive(chunk: Buffer) {
      bytes += chunk.length;
      const newline = chunk.indexOf(10);
      if (bytes <= REQUEST_LIMIT && newline < 0) {
        chunks.push(chunk);
        return;
      }
      socket.off("data", receive);
      socket.pause();
      let request: Request | undefined;
      if (bytes <= REQUEST_LIMIT && newline === chunk.length - 1) {
        chunks.push(chunk.subarray(0, newline));
        request = parseRequest(Buffer.concat(chunks, bytes - 1));
      }
      chunks = [];
      if (!request) {
        socket.end(UNAVAILABLE);
        return;
      }
      void dispatch(request, () => !socket.destroyed).then((reply) => {
        if (socket.destroyed) return;
        const line = `${JSON.stringify(reply)}\n`;
        socket.end(Buffer.byteLength(line) <= RESPONSE_LIMIT ? line : UNAVAILABLE);
      }).catch(() => { if (!socket.destroyed) socket.end(UNAVAILABLE); });
    }
    socket.on("data", receive);
  });
  server.maxConnections = 8;
  const listening = await new Promise<boolean>((resolve) => {
    server.on("error", () => resolve(false));
    server.listen(path, () => resolve(true));
  });
  if (!listening) return;
  const close = () => {
    for (const client of clients) client.destroy();
    server.close();
  };
  try {
    chmodSync(path, 0o600);
    server.unref();
    return close;
  } catch {
    close();
  }
}
