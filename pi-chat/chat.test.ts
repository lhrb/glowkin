import { strict as assert } from "node:assert";
import { mkdtemp, rm, stat } from "node:fs/promises";
import { createConnection } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { createChat, nonce, parseRequest, REQUEST_LIMIT, RESPONSE_LIMIT, supportsDraft, toMessage, type Activity, type Message } from "./chat.ts";
import { isForegroundProcStat, parsePaneList } from "./pane.ts";
import { activeEndpointGone, openEndpoint } from "./socket.ts";

const target = { sessionId: "$1", generation: "123-456", windowId: "@2", paneId: "%3" };
const instanceId = "a".repeat(32);
const request = { ...target, instanceId, op: "read" as const };

function fixture() {
  let draft: string | null = "PC draft";
  let activity: Activity = "idle";
  let writes = 0;
  let messages: Message[] = [];
  const chat = createChat(instanceId, target, {
    getDraft: () => draft,
    setDraft: (text) => { draft = text; writes++; },
    activity: () => activity,
    queued: () => true,
    history: () => messages,
    subagents: () => null,
  });
  const read = () => {
    const reply = chat.handle(request);
    assert.ok(reply.status === "ok" && "draftRevision" in reply);
    return reply;
  };
  return {
    chat, read,
    edit: (draftRevision: string, text = "phone draft") => chat.handle({ ...request, op: "edit", text, draftRevision }),
    setDraft: (value: string | null) => { draft = value; },
    setActivity: (value: Activity) => { activity = value; chat.invalidate(); },
    setMessages: (value: Message[]) => { messages = value; },
    writes: () => writes,
  };
}

test("PC wins text changes and typing followed by undo; successful revisions cannot be replayed", () => {
  const f = fixture();
  const initial = f.read().draftRevision;
  f.setDraft("newer PC draft");
  assert.deepEqual(f.edit(initial), { status: "conflict" });
  const previous = f.read().draftRevision;
  f.chat.invalidate();
  assert.deepEqual(f.edit(previous), { status: "conflict" });
  const fresh = f.read().draftRevision;
  assert.deepEqual(f.edit(fresh), { status: "ok" });
  assert.deepEqual(f.edit(fresh), { status: "conflict" });
  assert.equal(f.read().draft, "phone draft");
  assert.equal(f.writes(), 1);
});

test("blocks during prompts/compaction and requires a fresh revision afterward", () => {
  const f = fixture();
  const initial = f.read().draftRevision;
  for (const activity of ["respond_on_pc", "compacting"] as const) {
    f.setActivity(activity);
    const snapshot = f.read();
    assert.equal(snapshot.activity, activity);
    assert.equal(snapshot.queued, true);
    assert.deepEqual(f.edit(snapshot.draftRevision), { status: "blocked" });
  }
  f.setActivity("working");
  assert.deepEqual(f.edit(initial), { status: "conflict" });
  assert.equal(f.writes(), 0);
});

test("exact target binding and unavailable draft", () => {
  const f = fixture();
  const snapshot = f.read();
  for (const change of [{ sessionId: "$5" }, { generation: "123-457" }, { windowId: "@5" }, { paneId: "%5" }, { instanceId: nonce() }]) {
    assert.deepEqual(f.chat.handle({ ...request, op: "edit", ...change, text: "bad", draftRevision: snapshot.draftRevision }),
      { status: "unavailable" });
  }
  for (const draft of [null, "😀".repeat(4097), "\x1b"]) {
    f.setDraft(draft);
    const current = f.read();
    assert.equal(current.draft, null);
    assert.deepEqual(f.edit(current.draftRevision), { status: "blocked" });
  }
  assert.equal(f.writes(), 0);
});

test("draft and framing limits use UTF-8 bytes and reject invalid controls", () => {
  const payload = { ...request, op: "edit", draftRevision: nonce(), text: "hello\t\n😀" };
  assert.deepEqual(parseRequest(Buffer.from(JSON.stringify(payload))), payload);
  assert.equal(parseRequest(Buffer.alloc(REQUEST_LIMIT)), undefined);
  assert.equal(parseRequest(Buffer.from([0xc3])), undefined);
  assert.equal(parseRequest(Buffer.from(JSON.stringify({ ...payload, op: "send" }))), undefined);
  assert.ok(supportsDraft("😀".repeat(4096)));
  assert.ok(!supportsDraft("😀".repeat(4096) + "a"));
  for (const char of ["\u0000", "\r", "\u0085", "\u2028", "\u2029", "\ud800"]) {
    assert.ok(!supportsDraft(char));
  }
});

test("bounds encoded history without truncating the draft or leaking non-text blocks", () => {
  const f = fixture();
  const draft = '"'.repeat(16 * 1024);
  f.setDraft(draft);
  assert.equal(toMessage("private", { role: "system", content: "system prompt" }), undefined);
  const tool = toMessage("result", { role: "toolResult", toolName: "bash", content: [{ type: "image", data: "private" }, { type: "text", text: "\tindent" }] });
  assert.deepEqual(tool, { id: "result", role: "tool", tool: "bash", text: "\tindent" });
  f.setMessages([tool!, ...Array.from({ length: 100 }, (_, i): Message => ({ id: `${i}`, role: "user", text: "\0".repeat(12 * 1024) }))]);
  const reply = f.read();
  assert.ok(reply.messages.length < 20);
  assert.deepEqual(reply.messages.at(-1), tool);
  assert.equal(reply.draft, draft);
  assert.equal(reply.subagents, null);
  assert.ok(Buffer.byteLength(JSON.stringify(reply)) + 1 <= RESPONSE_LIMIT);
});

test("pane probe rejects stale generations, split panes, multiple sessions and wrong foreground", () => {
  const row = ["123-456", "1", "$1", "@2", "1", "%3", "/dev/pts/4", "0", "0", "0", ""];
  const parse = (row: string[]) => parsePaneList(`${row.join("\t")}\n`, "123", "%3");
  assert.deepEqual(parse(row), { ...target, tty: "/dev/pts/4", marker: "", editable: true });
  for (const [column, value] of [[0, "101-456"], [1, "2"], [4, "2"], [5, "%9"], [7, "1"]] as const) {
    const changed = [...row];
    changed[column] = value;
    assert.equal(parse(changed), undefined);
  }
  assert.ok(isForegroundProcStat("123 (app wi)th spaces) S 1 123 456 34820 123 0 0", 34820n));
  assert.ok(!isForegroundProcStat("123 (app) S 1 123 456 34820 999 0 0", 34820n));
  assert.ok(!isForegroundProcStat("123 (app) S 1 123 456 34820 123 0 0", 34821n));
});

test("Unix endpoint is private and dispatches at most once per connection", async () => {
  const directory = await mkdtemp(join(tmpdir(), "glowkin-chat-test-"));
  const path = join(directory, `${instanceId}.sock`);
  let calls = 0;
  const close = await openEndpoint(directory, instanceId, async () => {
    calls++;
    return { status: "ok" };
  });
  assert.ok(close);
  try {
    assert.equal((await stat(path)).mode & 0o777, 0o600);
    assert.equal(await activeEndpointGone(directory, `active:${instanceId}`), false);
    assert.equal(await activeEndpointGone(directory, `active:${nonce()}`), true);
    function exchange(data: Buffer | string) {
      return new Promise<string>((resolve, reject) => {
        const client = createConnection(path, () => { client.end(data); });
        let reply = "";
        client.on("data", (chunk) => { reply += chunk; });
        client.on("end", () => resolve(reply));
        client.on("error", reject);
      });
    }
    const line = `${JSON.stringify(request)}\n`;
    assert.equal(await exchange(line), '{"status":"ok"}\n');
    assert.equal(await exchange(line + line), '{"status":"unavailable"}\n');
    assert.equal(await exchange(Buffer.concat([Buffer.alloc(REQUEST_LIMIT, 32), Buffer.from("\n")])), '{"status":"unavailable"}\n');
    assert.equal(calls, 1);
  } finally {
    close();
    await rm(directory, { recursive: true, force: true });
  }
});
