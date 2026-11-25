# Glowkin

Glowkin is a collection of tools and extensions for working with Pi in tmux. The goal is to control agent sessions from your phone, use it for input (including keyboard dictation), and add small conveniences like seeing which panes are idle or waiting for a reply and hearing a tone when an agent needs permission.

## Repository layout

- [`android/`](android/) — Android app for mobile input and session control.
- [`server/`](server/) — Standalone Rust bridge for Android. See [deployment](docs/features/android-agent-control/connection-deployment.md).
- [`pi-tmux-signals/`](pi-tmux-signals/) — Local Pi state inspection by tmux pane.
