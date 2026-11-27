# Glowkin Android design guide

Design for the task, not the component catalog. Make the PC workspace easy to scan, keep routine status quiet, and make blockers and recovery easy to find.

## Design and handoff

Before picking components, write the screen’s job in one sentence and sketch its reading order in a few lines. Name the focal point: what should people notice first, what gets the most space, and what should they do next? Give each state one dominant interaction; it can be a tappable list rather than a button. Group occasional controls behind one predictable entry point.

For a new or substantially changed screen, hand off **goal → sketch/reading order and focal point → primary interaction → secondary actions → state overrides → component picks**. Include only states that change what people see or do. Skip alternate layouts and research notes.

There is no approved visual theme or screenshot reference. The current UI is not a visual standard; consult code only for behavior, state, and safety. Start implementation with stock Compose Material 3 and `GlowkinTheme`; neither decides the layout. Defer custom colors and mascot work. No palette decision is needed to proceed.

## Worked example: Workspace

**Goal:** Find a tmux window and view its pane. Treat Workspace as a window switcher, not a dashboard.

**Reading order:** `Workspace title + secondary actions → inline notice when needed → window list`. The list is the focal point and gets most of the space. Start with names like `Editor`, `Tests`, and `Logs`. Keep session name, window count, and routine connection status out of the main reading path. Don’t repeat “Terminal output” on every row.

**Interactions:** Tap anywhere on an eligible row to open its pane. Give an ineligible row a short reason and no tap action. Group routine **Refresh** and **Open shell** in one clearly labeled actions menu. Surface the relevant recovery action beside a blocker instead of making people look in the menu.

**State overrides:**

- Put “Stale snapshot — not current PC state” directly above the retained list. Keep the names visible but don’t let stale rows open panes. Offer Refresh there when eligible, or direct users to Connection when reconnecting is needed.
- Replace an absent or empty list with the reason and next step. Distinguish no snapshot (use Connection), an unsupported bridge (update it), no session (start one on the PC), multiple sessions (manage them on the PC), unreadable tmux (check it on the PC), and a session with no reported windows (check the session on the PC).
- Preserve `paneTarget` and `shellTarget` eligibility and pending-action guards. Keep errors and recovery visible. Distinguish a stale snapshot from an uncertain action outcome: if Open shell may have succeeded, tell users to check the PC windows before retrying.

**Component picks:** Use a title bar, a quiet actions menu, inline notice text, and a scrolling list with one row per window.

## Implementation defaults

- **Spacing, not component dimensions:** Start with 4 dp for tight pairs, 8 dp between related controls, 16 dp for compact gutters and within-section gaps, and 24 dp between sections. Align visible text and controls on common edges. Count built-in padding once. Prefer whitespace to dividers or cards.
- **Type and color:** Use `headlineSmall` for standalone titles, `titleMedium` for section headings, `bodyMedium` for supporting text, and `bodySmall` for nonessential metadata. Keep `TopAppBar` title and button label defaults. Use semantic color roles, not screen-specific hex values.
- **Adaptation and access:** Wrap or stack controls at narrow widths or large text sizes. Don’t fix text-region heights. Scroll long content and account for system and keyboard insets. Use nonoverlapping touch targets of at least 48 × 48 dp. Label icon-only actions. Don’t rely on color alone for status, selection, or errors.

Use this table as a fallback **after** sketching, not as a screen recipe. Compose stock components if one doesn’t fit. Keep new patterns local until another screen needs them. Don’t add a library to restyle controls.

| Intent | Default |
| --- | --- |
| Structure and navigation | `Scaffold` and `TopAppBar` for title, back, or contextual actions. Reserve `NavigationBar` for peer destinations, not commands. |
| Scan and open items | `LazyColumn` and tappable `ListItem`s. Use supporting text for useful context or ineligibility, not a separate Open button per row. |
| Action emphasis | A filled `Button` for an explicit primary action. Use `OutlinedButton` for visible alternatives, `TextButton` for quiet actions, and a labeled `IconButton` for familiar toolbar actions. Use text for unfamiliar or consequential actions. |
| Form input | `OutlinedTextField` with persistent labels and inline validation via `isError` and `supportingText`. |
| Feedback and recovery | `Snackbar` for brief nonblocking feedback, inline text for persistent warnings and recovery, and a progress indicator while pending. Use `AlertDialog` when proceeding requires a decision; don’t add confirmations in layout-only changes. |
| Content grouping | Start with whitespace on the normal surface. Use `Card` or `Surface` only if a boundary matters. |

## Behavior references

Open only the matching reference. Preserve the behavior, not the layout.

- [Workspace overview][overview] and [connection state][state]: snapshot states, recovery, and row/action eligibility.
- [Pane detail][pane]: scrollable monospace output, read-only or paused status, and separate **Insert text** and **Enter** actions. Inserting never submits.
- [Connection screen and uncertain-outcome messages][activity]: label host and port, distinguish saving or replacing an endpoint from retrying the saved one, and tell users to check the PC before retrying an action that may have succeeded.

## Rendered acceptance and handoff

Inspect the rendered screen with synthetic data. Check that:

- The intended focal point reads first and gets the most space in the normal state. In a blocked state, the reason and recovery are easy to find.
- Visible edges line up and related items sit closer together than separate groups. Built-in padding doesn’t create doubled gutters.
- Routine status and secondary actions don’t compete with the task. Remove repeated labels or containers that don’t clarify grouping.
- A compact phone, large text, and light and dark modes preserve reading order, legibility, and access to controls. Check affected states for correct notices, recovery, and eligibility. On input screens, open the keyboard and verify the focused field and required actions stay reachable.

Report what was checked and name any unrun checks. Screenshots are optional handoff outputs once a rendered design is worth reusing, not prerequisites. Use synthetic data in screenshots too.

[Material 3 components](https://developer.android.com/develop/ui/compose/components) · [Android content structure](https://developer.android.com/design/ui/mobile/guides/layout-and-content/content-structure) · [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

[overview]: app/src/main/java/dev/lhrb/app/glowkin/SessionOverview.kt
[state]: app/src/main/java/dev/lhrb/app/glowkin/features/connection/domain/ConnectionUseCase.kt
[activity]: app/src/main/java/dev/lhrb/app/glowkin/MainActivity.kt
[pane]: app/src/main/java/dev/lhrb/app/glowkin/PaneDetail.kt
