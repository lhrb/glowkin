import { randomBytes } from "node:crypto";

export const REQUEST_LIMIT = 128 * 1024;
export const RESPONSE_LIMIT = 256 * 1024;
const DRAFT_LIMIT = 16 * 1024;
const HISTORY_LIMIT = 64;
const TEXT_LIMIT = 12 * 1024; // UTF-16 code units, before the wire byte limit.
const INVALID_DRAFT = /[\x00-\x08\x0b-\x1f\x7f-\x9f\u2028\u2029\p{Cs}]/u;

export interface Target {
  sessionId: string;
  generation: string;
  windowId: string;
  paneId: string;
}

export type Request = Target & { instanceId: string } & (
  | { op: "read" }
  | { op: "edit"; draftRevision: string; text: string }
);

export interface Message {
  id: string;
  role: "user" | "assistant" | "tool";
  text: string;
  tool?: string;
}

export type Activity = "idle" | "working" | "compacting" | "respond_on_pc";

export type Reply = { status: "ok" | "conflict" | "blocked" | "unavailable" } | {
  status: "ok";
  instanceId: string;
  draftRevision: string;
  draft: string | null;
  messages: Message[];
  subagents: string[] | null;
  activity: Activity;
  queued: boolean;
};

export const INSTANCE_ID = /^[a-f0-9]{32}$/;
export const SESSION_ID = /^\$[0-9]{1,10}$/;
export const WINDOW_ID = /^@[0-9]{1,10}$/;
export const PANE_ID = /^%[0-9]{1,10}$/;
export const GENERATION = /^[1-9][0-9]{0,9}-[1-9][0-9]{0,19}$/;

function matches(value: unknown, re: RegExp): value is string {
  return typeof value === "string" && re.test(value);
}

export function supportsDraft(value: unknown): value is string {
  return typeof value === "string" && value.length <= DRAFT_LIMIT &&
    Buffer.byteLength(value) <= DRAFT_LIMIT && !INVALID_DRAFT.test(value);
}

export function parseRequest(line: Uint8Array): Request | undefined {
  if (line.byteLength + 1 > REQUEST_LIMIT) return;
  try {
    const value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(line));
    if (!value || typeof value !== "object") return;
    const { sessionId, generation, windowId, paneId, instanceId } = value;
    if (!matches(sessionId, SESSION_ID) || !matches(generation, GENERATION) ||
      !matches(windowId, WINDOW_ID) || !matches(paneId, PANE_ID) || !matches(instanceId, INSTANCE_ID)) return;
    const target = { sessionId, generation, windowId, paneId, instanceId };
    if (value.op === "read") return { ...target, op: "read" };
    if (value.op === "edit" && matches(value.draftRevision, INSTANCE_ID) && supportsDraft(value.text)) {
      return { ...target, op: "edit", draftRevision: value.draftRevision, text: value.text };
    }
  } catch {
    // Don't report untrusted input in any diagnostics.
  }
}

export function sameTarget(a: Target, b: Target): boolean {
  return a.sessionId === b.sessionId && a.generation === b.generation &&
    a.windowId === b.windowId && a.paneId === b.paneId;
}

export function nonce(): string {
  return randomBytes(16).toString("hex");
}

export interface ChatPort {
  getDraft(): string | null;
  setDraft(text: string): void;
  activity(): Activity;
  queued(): boolean;
  history(): Iterable<Message>; // Newest first.
  subagents(): string[] | null;
}

export function createChat(instanceId: string, target: Target, pi: ChatPort) {
  let draftRevision = nonce();
  let lastDraft: string | null | undefined;
  function invalidate() {
    draftRevision = nonce();
  }

  function handle(request: Request): Reply {
    if (request.instanceId !== instanceId || !sameTarget(target, request)) return { status: "unavailable" };
    const activity = pi.activity();
    const candidate = pi.getDraft();
    const draft = supportsDraft(candidate) ? candidate : null;
    if (draft !== lastDraft) {
      lastDraft = draft;
      invalidate();
    }
    if (request.op === "edit") {
      if (activity === "compacting" || activity === "respond_on_pc" || draft === null || !supportsDraft(request.text)) {
        return { status: "blocked" };
      }
      if (request.draftRevision !== draftRevision) return { status: "conflict" };
      // Nothing asynchronous may run between reading the editor and setting it.
      // Invalidate first even if an editor implementation should throw.
      invalidate();
      if (request.text !== draft) pi.setDraft(request.text);
      lastDraft = request.text;
      return { status: "ok" };
    }

    const reply = {
      status: "ok" as const, instanceId, draftRevision, draft, activity,
      queued: pi.queued(), subagents: pi.subagents(), messages: [] as Message[],
    };
    let remaining = RESPONSE_LIMIT - Buffer.byteLength(JSON.stringify(reply)) - 1;
    for (const message of pi.history()) {
      remaining -= Buffer.byteLength(JSON.stringify(message)) + 1;
      if (remaining < 0) break;
      reply.messages.push(message);
      if (reply.messages.length === HISTORY_LIMIT) break;
    }
    reply.messages.reverse();
    return reply;
  }
  return { handle, invalidate };
}

export function boundedText(text: string, limit = TEXT_LIMIT): string {
  return text.slice(0, limit).replace(/\p{Cs}/gu, "\ufffd") + (text.length > limit ? "\n[Truncated]" : "");
}

export function toMessage(id: string, message: { role: string; content?: unknown; toolName?: string }): Message | undefined {
  const role = message.role === "toolResult" ? "tool" : message.role;
  if (role !== "user" && role !== "assistant" && role !== "tool") return;
  let text = "";
  if (typeof message.content === "string") {
    text = boundedText(message.content);
  } else if (Array.isArray(message.content)) {
    for (const block of message.content) {
      if (block?.type !== "text" || typeof block.text !== "string" || block.text.length === 0) continue;
      if (text) text += "\n";
      text += boundedText(block.text, Math.max(0, TEXT_LIMIT - text.length));
      if (text.length >= TEXT_LIMIT) break;
    }
  }
  if (!text && role !== "tool") return;
  return role === "tool"
    ? { id, role, text, tool: boundedText(message.toolName ?? "", 128) }
    : { id, role, text };
}
