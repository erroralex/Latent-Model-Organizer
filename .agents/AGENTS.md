# Agent Instructions

## Project

- **Name:** Latent Model Organizer
- **Purpose:** A high-performance desktop utility designed to organize massive AI model libraries (.safetensors) by architecture using zero-copy header parsing and Civitai metadata fetching.
- **Stack:** Java 21 (raw HttpServer, virtual threads, SLF4J/Logback), Vue 3 + Vite, Electron.
- **Build:** `mvn clean package -DskipTests` (in `backend`) and `npm run build` (in `frontend`).
- **Test:** `mvn test` (in `backend`).
- **Run locally:** Run `java -jar target/backend.jar` (in `backend`), `npm run dev` (in `frontend`), and `npm start` (in `electron`).

## Workflow

- Before non-trivial changes: state your plan in 2–5 bullet points, then implement.
  If requirements are ambiguous, ask — don't guess.
- Work in small, verifiable steps. One logical change at a time.
- Write or update the test for a behavior change **before or with** the code,
  never "later".
- Before claiming anything works: run the test/build command and show the result.
  "Should work" is not done — verified is done.
- When a task touches unfamiliar code, read the surrounding files first and follow
  the patterns already there.
- Check `.agents/skills/` for an applicable skill before starting specialized work
  (framework setup, UI design, reviews); use it if its description matches the task.

## Engineering rules

- **YAGNI:** build what the task needs, nothing speculative. No extra config options,
  abstraction layers, or "flexibility" that wasn't asked for.
- **DRY, but not premature:** extract shared code on the third occurrence, not the
  second. Duplication is cheaper than the wrong abstraction.
- **Single responsibility:** one reason to change per class/module/function. If a file
  needs "and" to describe what it does, split it.
- **Depend on interfaces at boundaries** (service ↔ persistence, domain ↔ external
  APIs); don't interface-ify everything else.
- Keep functions short and files focused. A file approaching ~300 lines is a signal
  to split.
- Prefer boring, idiomatic solutions over clever ones. Optimize only with a
  measurement in hand.
- Fail fast: validate inputs at system boundaries, throw early with specific messages,
  never swallow exceptions silently.

## Testing

- Every bugfix gets a regression test that fails before the fix and passes after.
- Test behavior through public interfaces, not implementation details.
- Never delete, skip, or weaken a test to make a change pass. If a test seems wrong,
  say so and ask.
- Tests must be deterministic: no sleeps for synchronization, no order dependence,
  no shared mutable state between tests.

## Git

- Small commits, one logical change each. Imperative subject line ≤ 72 chars;
  body explains *why* when it isn't obvious.
- **No AI attribution anywhere in git:** no `Co-Authored-By` trailers naming an AI,
  no "Generated with ..." lines in commit messages, PR descriptions, or code
  comments. Commits carry the human author's identity only.
- Never commit secrets, credentials, or generated artifacts.
- Never force-push or rewrite history on shared branches.

## Security

- No secrets in code or config files — use environment variables or a secret manager.
- All user input is untrusted: validate at the boundary, use parameterized
  queries/bound parameters, escape output in templates.
- Don't add dependencies for trivial tasks; when adding one, prefer well-maintained,
  widely-used libraries.

## Frontend core (HTML / CSS / JavaScript)

> Skills: `.agents/skills/frontend-design/` for building distinctive UI;
> `.agents/skills/web-design-guidelines/` for auditing existing UI.

- Semantic HTML first: native elements (`button`, `nav`, `dialog`, `details`) before
  div+JS reimplementations. Heading levels in order; one `h1` per page.
- Accessibility is not optional: every input has a `label`; interactive elements are
  keyboard-reachable with visible focus; images have meaningful `alt` (or empty for
  decorative); color contrast ≥ WCAG AA; ARIA only when no native element fits.
- Responsive = mobile-first: base styles for small screens, `min-width` media queries
  upward; relative units (`rem`, `%`) over fixed px for layout; test at 320px,
  768px, 1280px.
- CSS: design tokens as custom properties (colors, spacing, type scale) defined once;
  prefer flexbox/grid over floats and absolute positioning; avoid `!important` and
  deep selector chains; co-locate component styles.
- JavaScript: ES modules; `const`/`let`, never `var`; `async/await` with explicit
  error handling over raw promise chains; `fetch` with status checks (a 404 does not
  reject); no jQuery in new code.
- Forms: validate client-side for UX, **always** revalidate server-side; disable the
  submit button while a request is in flight; show field-level errors.
- Escape user content rendered into the DOM — `textContent` over `innerHTML`;
  if HTML insertion is unavoidable, sanitize.
- Performance basics: `defer` scripts; set width/height (or aspect-ratio) on images;
  lazy-load below-the-fold images; don't ship a framework for a static page.

## Vue (3.x)

> Skill: `.agents/skills/vue/` (Anthony Fu, generated from the official Vue docs)
> has detailed references for script-setup macros, reactivity, and built-in
> components — consult it for non-trivial Vue work.

- Composition API with `<script setup>` in Single-File Components. No Options API in
  new code; don't mix the two styles in one component.
- Don't apply Vue 2 idioms: no `this`, no mixins (use composables), no event bus,
  no `Vue.set` (reactivity is proxy-based now), filters are gone.
- State: component-local with `ref`/`reactive`; shared app state in Pinia stores —
  not prop-drilled five levels, not a global reactive object.
- Data flow: props down, `emit` up. Never mutate a prop; declare props/emits with
  types (`defineProps<...>()` / `defineEmits<...>()`).
- Derived state is `computed`, not a method, not a watcher copying values. Watchers
  are a last resort for side effects (e.g. fetch on param change).
- `v-for` always with a stable `:key` (not the index when the list reorders).
  Don't pair `v-if` with `v-for` on the same element.
- Extract reuse into composables (`useThing()`), one concern per composable.
- Router: lazy-load route components; guard auth in navigation guards, not in
  components.
- Tests: Vitest + Vue Test Utils; test rendered behavior and emitted events, not
  internal refs.
- Never use `v-html` with user-provided content.
