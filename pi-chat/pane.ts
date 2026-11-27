import { execFile } from "node:child_process";
import { fstatSync, readFileSync, statSync } from "node:fs";
import { promisify } from "node:util";
import { GENERATION, PANE_ID, SESSION_ID, sameTarget, type Target, WINDOW_ID } from "./chat.ts";

const exec = promisify(execFile);
const OPTION = "@glowkin-chat";
const MARKER = /^(?:active|exited):[0-9a-f]{32}$/;
const TTY = /^\/dev\/pts\/[0-9]+$/;

export function isForegroundProcStat(stat: string, ttyDevice: bigint): boolean {
  // The comm field is parenthesized and can itself include spaces or parentheses.
  const end = stat.lastIndexOf(") ");
  if (end < 0) return false;
  const [, , group, , tty, foreground] = stat.slice(end + 2).split(" ");
  return /^[1-9][0-9]*$/.test(group ?? "") && group === foreground && /^-?[0-9]+$/.test(tty ?? "") &&
    ttyDevice !== 0n && BigInt.asUintN(32, BigInt(tty!)) === ttyDevice;
}

function ownsForeground(tty: string, requireRaw: boolean): boolean {
  if (!TTY.test(tty) || !process.stdin.isTTY || !process.stdout.isTTY || (requireRaw && !process.stdin.isRaw)) return false;
  try {
    const pane = statSync(tty, { bigint: true });
    const input = fstatSync(0, { bigint: true });
    const output = fstatSync(1, { bigint: true });
    return pane.isCharacterDevice() && pane.dev === input.dev && pane.ino === input.ino &&
      input.dev === output.dev && input.ino === output.ino &&
      isForegroundProcStat(readFileSync("/proc/self/stat", "utf8"), input.rdev);
  } catch {
    return false;
  }
}

type Snapshot = Target & { tty: string; marker: string; editable: boolean };

export function parsePaneList(output: string, pid: string, pane: string): Snapshot | undefined {
  for (const row of output.trimEnd().split("\n")) {
    const [generation, sessions, sessionId, windowId, panes, paneId, tty, dead, mode, inputOff, marker] = row.split("\t");
    if (paneId !== pane) continue;
    if (!generation || !GENERATION.test(generation) || !generation.startsWith(`${pid}-`) || sessions !== "1" ||
      !sessionId || !SESSION_ID.test(sessionId) || !windowId || !WINDOW_ID.test(windowId) || panes !== "1" ||
      !tty || !TTY.test(tty) || dead !== "0") return;
    return { sessionId, generation, windowId, paneId, tty, marker: marker ?? "", editable: mode === "0" && inputOff === "0" };
  }
}

export function tmuxPane(env: NodeJS.ProcessEnv) {
  const paneId = env.TMUX_PANE;
  const match = /^(\/[^\0\n]+),([1-9][0-9]{0,9}),[0-9]+$/.exec(env.TMUX ?? "");
  if (process.platform !== "linux" || !paneId || !PANE_ID.test(paneId) || !match) return;
  const [, path, pid] = match;
  if (!path || !pid) return;

  async function run(args: string[]): Promise<string | undefined> {
    try {
      const { stdout } = await exec("tmux", ["-N", "-u", "-S", path, ...args], {
        timeout: 750, killSignal: "SIGKILL", maxBuffer: 64 * 1024,
      });
      return stdout;
    } catch {
      return undefined;
    }
  }

  async function inspect(): Promise<Snapshot | undefined> {
    const out = await run(["list-panes", "-a", "-F", [
      "#{pid}-#{start_time}", "#{server_sessions}", "#{session_id}", "#{window_id}", "#{window_panes}",
      "#{pane_id}", "#{pane_tty}", "#{pane_dead}", "#{pane_in_mode}", "#{pane_input_off}", `#{${OPTION}}`,
    ].join("\t")]);
    return out === undefined ? undefined : parsePaneList(out, pid!, paneId!);
  }

  async function marker(target: Snapshot, old: string, next: string, eligible: boolean): Promise<boolean> {
    if ((old && !MARKER.test(old)) || (next && !MARKER.test(next))) return false;
    // Guard and mutation run in the same tmux command queue. When removing our
    // own marker, tolerate a topology change but never a replaced server/pane.
    const checks = [
      `#{==:#{pane_id},${target.paneId}}`,
      `#{==:#{pid}-#{start_time},${target.generation}}`,
      `#{==:#{pane_tty},${target.tty}}`,
      `#{==:#{${OPTION}},${old}}`,
    ];
    if (eligible) checks.push("#{==:#{server_sessions},1}", `#{==:#{session_id},${target.sessionId}}`,
      `#{==:#{window_id},${target.windowId}}`, "#{==:#{window_panes},1}", "#{==:#{pane_dead},0}");
    const guard = checks.reduce((left, right) => `#{&&:${left},${right}}`);
    const set = next ? `set-option -p -t '${target.paneId}' ${OPTION} ${next}` :
      `set-option -pu -t '${target.paneId}' ${OPTION}`;
    const out = await run(["if-shell", "-F", "-t", eligible ? `${target.sessionId}:${target.windowId}` : target.paneId,
      guard, `${set} ; list-commands -F glowkin-chat-marker set-option`]);
    return out === "glowkin-chat-marker\n";
  }

  return {
    inspect,
    claim: (target: Snapshot, active: string) => ownsForeground(target.tty, true) && marker(target, target.marker, active, true),
    async validate(target: Snapshot, active: string) {
      const current = await inspect();
      return current !== undefined && current.marker === active && current.tty === target.tty &&
        sameTarget(current, target) && current.editable;
    },
    async release(target: Snapshot, active: string, exited: string | undefined) {
      if (exited && ownsForeground(target.tty, false) && await marker(target, active, exited, true)) return;
      await marker(target, active, "", false);
    },
    foreground: (target: Snapshot) => ownsForeground(target.tty, true),
  };
}
