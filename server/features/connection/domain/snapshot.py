"""Connection-only bridge identity; no agent or workspace inventory yet."""
from dataclasses import dataclass
from uuid import uuid4


@dataclass(frozen=True)
class Snapshot:
    revision: str


class SnapshotUseCase:
    def __init__(self):
        # Stable for this bridge lifetime, unrelated to previous process revisions.
        self._snapshot = Snapshot(revision=uuid4().hex)

    def read(self) -> Snapshot:
        return self._snapshot
