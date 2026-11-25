# Rust conventions

- Give mutable state one clear owner. Prefer `state + event → next state` for domain logic where it clarifies behavior.
- Pass small owned values across long-lived boundaries. Borrow locally; add shared mutability only when needed.
- Start with the standard library and synchronous I/O. Add dependencies or async when they solve a concrete problem.
- Measure idle memory, CPU, and end-to-end latency before optimizing or porting for performance.
- Run `cargo fmt --check`, `cargo clippy`, and `cargo test` for Rust changes.
