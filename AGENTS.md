# Project instructions

- Read [architecture.md](architecture.md) before designing or changing code.
- Read [android/design.md](android/design.md) before designing or changing Android UI.
- Make the smallest working change. Preserve guarantees; avoid scaffolding and unrelated changes.
- Choose routine design and UX defaults. Escalate consequential product, security, privacy, data-lifecycle, or compatibility decisions.
- Verify relevant behavior, report gaps, and stop at scope.
- Be concise and use active voice. Give the answer, reason, then next step. Keep one idea per sentence.

## Languages

- `android/`: Kotlin. Pi extensions: JS/TS.
- New Linux code: Rust, including the standalone Pi signal receiver. Use GPUI for desktop UI only. For Rust work, read [rust-conventions.md](rust-conventions.md).
- `server/`: Standalone Rust bridge. Preserve the Android-facing contract.
