# Library Management

A small library system: members register with a cash deposit, borrow books with a due date, extend or return them (late returns cost a fee), and join wait lists. An admin (librarian) manages the catalogue and watches loans on a dashboard.

It is built as **Spring Boot microservices** behind a **Spring Cloud Gateway**, with a **React micro-frontend** UI. Resilience patterns (circuit breakers, retries, timeouts, fallbacks and rate limiting) are applied at every hop.

**Contents:** [1. Architecture](#1-architecture) · [2. Features](#2-features-at-a-glance) · [3. Quick start](#3-quick-start) · [4. Instruction manual](#4-instruction-manual) · [5. Tests](#5-tests) · [6. Project layout](#6-project-layout) · [7. Configuration](#7-configuration) · [8. Assumptions](#8-assumptions-and-decisions) · [9. Troubleshooting](#9-troubleshooting)

---

## 1. Architecture

### 1.1 System overview

```
                         ┌─ catalog remote :3001  (everyone: list, add, borrow, wait list, return)
Browser ── shell :3000 ──┼─ account remote :3002  (members only: status, extend, return, deposit)
           (host app)    ├─ admin remote   :3003  (admin only: library dashboard)
                         └─ /api ─► gateway :8080 ──┬─► user-service    :8081  (login, sessions)
                                                    └─► library-service :8082  (books, loans, wait lists,
                                                                 │               deposits, dashboard)
                                                                 └─► user-service (token check)
```

The system has two halves that meet at one HTTP contract:

* **Backend:** two Spring Boot services behind a gateway. Each service is built **hexagonally** (business rules in the middle, technology at the edges).
* **Frontend:** a **shell** (host) application that loads three independently built **micro-frontends** ("remotes") at runtime and gives them shared services: session, API client, event bus.
* **Contract:** the JSON API, written down once as TypeScript types in `frontend/packages/contracts` and as Java records in each service's DTO classes.

How one request travels (e.g. a member clicks **Borrow**):

1. The catalogue remote asks the shell's **API client** to `POST /api/books/borrow`. The client wraps the call in a circuit breaker and a timeout.
2. The browser sends it to the **shell server** on the same origin (no CORS). The shell server proxies it to the **gateway**.
3. The gateway assigns or keeps an `X-Request-Id`, applies the **rate limit**, and routes to library-service through a **circuit breaker** with a JSON fallback.
4. library-service asks **user-service** who owns the bearer token (**token introspection**, with its own retry and circuit breaker), then runs the use case in `LibraryService`.
5. The answer returns the same way. The remote shows a toast and publishes `library:changed` on the shell's **event bus**, so every other panel refreshes itself.

Step-by-step sequence diagrams with the real class names are in [docs/DESIGN.md §7](docs/DESIGN.md#7-key-flows).

### 1.2 Backend architecture

#### Why two services and a gateway

| Option considered | Verdict |
|---|---|
| Modular monolith (one Spring Boot app) | Simplest, but there would be no network boundary for circuit breakers and retries to protect. Microservices were a requirement. |
| **Gateway + user-service + library-service** | **Chosen.** There are real service-to-service calls, each service owns its data, and the lending rules stay in one place. |
| Gateway + separate catalogue, loan, wait-list and notification services | Rejected. Borrow, return and wait list must change a book's availability **and** its queue together, and a return also charges the deposit. Splitting these would turn every return into a distributed transaction (sagas or distributed locks) with no business gain. |
| Full Spring Cloud (Eureka, Config Server) | Rejected for a local demo: 5–6 JVMs and a slow start without adding behaviour. Listed as future work. |

The services are split at the one boundary that has no shared rule: **identity** (who you are, your session) versus **lending** (books, loans, wait lists, deposits).

| Component | Owns | Main API (behind `/api`) |
|---|---|---|
| **gateway** (Spring Cloud Gateway) | Edge concerns only, no business logic: routing, request ids, rate limiting, circuit breakers, fallbacks | Routes `/api/sessions/**` to user-service and `/api/books/**`, `/api/me/**`, `/api/admin/**` to library-service |
| **user-service** | Users and sessions (random 256-bit tokens, 30-minute idle timeout) | `POST /sessions` (login), `GET /sessions/current` (token check), `DELETE /sessions/current` (logout) |
| **library-service** | Books, loans and due dates, FIFO wait lists, deposit accounts and late fees, notifications, admin dashboard | `GET/POST /books`, `POST /books/borrow`, `/return`, `/extend`, `/waitlist`, `GET /me/status`, `POST /me/deposit`, `GET /admin/dashboard`, demo clock `/admin/clock` |
| **service-commons** | A small shared "chassis" library, not business code: the JSON error format and the request-id filter, wired in by Spring Boot auto-configuration | — |

Book commands are **task-based and addressed by title** (`POST /books/borrow {"title": "1984"}`), one endpoint per command of the brief, rather than generic CRUD.

#### Inside each service: hexagonal architecture (ports and adapters)

```
com.library.books
├── domain/            pure Java, no Spring: Book (aggregate), Loan, MemberAccount, Money, BookTitle, errors
├── application/       use cases (LibraryService), read models (LibraryViews)
│   └── port/          interfaces the use cases need: BookRepository, AccountRepository,
│                      NotificationInbox, MemberDirectory
├── adapter/
│   ├── in/web/        REST controllers, DTOs, error mapping, CurrentMemberResolver
│   └── out/           in-memory repositories; UserServiceClient (HTTP + Resilience4j)
└── config/            composition root: the only place that connects adapters to ports
```

* **Dependencies point inwards.** The domain and application layers import no framework code. They are unit-tested without Spring.
* **Business rules live in the domain.** For example, `Book` enforces "an available book has no wait list; on return it goes to the first in line", `Loan` computes due dates and late fees, and `MemberAccount` records deposits and fees.
* **`LibraryService` has one method per use case** (`borrow`, `returnBook`, `extendLoan`, `dashboard`, …). It checks who may do what (admin vs member) and coordinates the domain objects and ports.
* **Swapping technology means writing an adapter.** Moving to PostgreSQL means new implementations of `BookRepository` and `AccountRepository`; the use cases do not change.
* **Authentication is impossible to forget.** Any controller method that takes a `Member` parameter gets it from `CurrentMemberResolver`, which checks the token with user-service, or the request fails with 401.
* **Authorisation is enforced in the application layer** (`ForbiddenException` → 403), not only by hiding buttons in the UI.

#### Data and concurrency

* All state is **in memory**, so every run is a clean start, as the brief asks. Repositories store **immutable snapshots**, so state changes only through `save`, just as it would with a database.
* Tomcat serves requests on many threads. `LibraryService` protects every read-modify-write with a **read-write lock**. A test fires 32 concurrent borrows of the same book and expects exactly one to succeed. With a database, this would become a transaction plus optimistic locking.
* **Errors** use one JSON format everywhere, `{"error": {"code", "message", "requestId"}}`, with HTTP codes that mean something: 400 validation, 401 not logged in, 403 not allowed, 404 not registered, 409 rule broken (e.g. not available, borrowing blocked), 429 rate-limited, 503 a service is down.

### 1.3 Frontend architecture

#### Micro-frontends with runtime composition

The UI is split into a **shell** and three **remotes**. Each is built separately and served from its own origin (port), as it would be from separate CDNs.

| App | Port | Shown to | Responsibility |
|---|---|---|---|
| **shell** (host) | 3000 | everyone | Login page, session, layout, welcome panel, toasts, service-status banner, loading the remotes, and the shared API client and event bus |
| **mfe-catalog** | 3001 | everyone | Catalogue list; admin: add books, demo clock; members: borrow, wait list, return, act-by-title form |
| **mfe-account** | 3002 | members only (`memberOnly`) | *My account*: borrowed books with due dates, extend, return, wait lists, deposit and history |
| **mfe-admin** | 3003 | admin only (`adminOnly`) | *Library dashboard*: members, books out, overdue and due-soon loans, deposits |

How composition works:

1. The shell's `index.html` has an **import map** that points `react` and `react-dom` to one copy served by the shell. Remotes are built with React as an *external*, so **all apps share one React instance**, downloaded once.
2. At start-up the shell reads `/mfe-manifest.json`, the list of remotes and their URLs. It is generated at run time, so a remote can be redeployed or moved without rebuilding the shell.
3. The shell `import()`s each remote. Every remote implements the same small contract: `contractVersion`, `mount(container, props)` and `unmount(container)`. A remote built for a different contract version is refused.
4. `mount` receives `{ user, api, bus }` from the shell (dependency injection), and the remote renders into **its own React root**.

| Option considered | Verdict |
|---|---|
| Build-time composition (npm packages) | Rejected: the parts are not independently deployable |
| iframes | Rejected: poor UX (scrolling, focus, styling) and awkward communication |
| Webpack / Rspack Module Federation | Good alternative, but ties every app to one bundler and its runtime |
| **Native ES modules + import map** | **Chosen.** Web standards only, works with any bundler (esbuild here), remotes loaded by URL at run time. Trade-off: shared library versions are pinned centrally in the shell. |

#### What the shell owns (and remotes reuse)

* **Session**: the token is kept in `sessionStorage` (per browser tab). An expired session (401) returns the user to the login page.
* **One API client** for everyone, layered as `circuit breaker → retry (GET only) → timeout → fetch`. It adds the bearer token and `X-Request-Id`. Only infrastructure failures (network, timeout, 5xx) count towards the breaker; business answers such as 404 or 409 do not. POSTs are never retried, because a "borrow" that timed out may already have happened.
* **Event bus** (publish/subscribe), so remotes never import each other:

  | Event | Sent by | Effect |
  |---|---|---|
  | `library:changed` | any remote after an action | every remote reloads its data |
  | `toast:show` | any remote | the shell shows a notification |
  | `account:status` | account remote | the shell updates the welcome panel with the latest status |
  | `session:ended` | shell | remotes clean up on logout or expiry |

* **Design tokens**: colours, fonts and spacing are CSS variables defined by the shell. Each remote prefixes its own CSS classes (`cat-`, `acc-`, `adm-`), so styles cannot collide.

#### Fault isolation

* Each remote has its own React root and **error boundary**. A remote that is down, too slow (5-second load timeout), the wrong contract version, or crashing breaks **only its own panel**, which shows an error with a *Try again* button. The rest of the page keeps working.
* When the API client's circuit breaker opens, the shell shows a **service banner** and recovers automatically.

#### Shared packages, build and security

* `packages/contracts`: the API and micro-frontend types, plus date and money formatting, so every app shows "08 Dec 2026" and "Rs. 500" the same way.
* `packages/resilience`: a dependency-free circuit breaker, retry with backoff and jitter, and timeout.
* **esbuild** builds each app separately (as separate CI pipelines would) in about 100 ms.
* The shell server proxies `/api` to the gateway, so the browser only ever talks to its own origin. It sends a strict **Content-Security-Policy**: scripts only from the known remote origins.
* A **mock gateway** (`frontend/scripts/mock-gateway.mjs`) implements the same API in memory, so the UI and its Playwright tests can run without Java (`./start.sh --mock`). library-service remains the owner of the rules.

### 1.4 Resilience at every layer

| Layer | Circuit breaker | Retry | Timeout | Fallback / other |
|---|---|---|---|---|
| Browser (shell API client) | Opens after 3 infrastructure failures, half-opens after 10 s | GET only, backoff + jitter | 8 s | Service banner; per-remote error + *Try again* |
| Gateway | One per downstream service (Resilience4j) | GET only | 4 s | JSON 503 + `Retry-After`; token-bucket **rate limit** (429); 16 KB request body limit |
| library-service → user-service | `@CircuitBreaker` on `UserServiceClient` | `@Retry` (a token check is safe to repeat) | connect 1 s / read 2 s | Fast 503 when user-service is down; an invalid token (401) is a normal answer, not a failure |

Every request carries an **`X-Request-Id`** from the browser through the gateway to both services. It appears on every log line and in every error response, so one id finds a request in all logs. All services shut down gracefully and expose Actuator health, readiness and circuit-breaker endpoints.

### 1.5 Key decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Split only at the identity boundary | Keeps the lending rules (book, queue, loan, fee) in one consistent place | library-service is the biggest service |
| Hexagonal services | Framework-free business rules; storage can change without touching use cases | More interfaces and packages than a plain layered app |
| ES modules + import map instead of Module Federation | Standards-based and bundler-agnostic | Shared library versions are pinned in the shell |
| The shell owns cross-cutting concerns | One session, one circuit breaker, one HTTP client for all remotes | Remotes depend on the shell's injected services (by design) |
| Token introspection instead of JWT | Logout takes effect immediately; tokens carry no data | One extra call per request, protected by a breaker and retry |
| Retry only idempotent (GET) requests | A retried POST could borrow twice or charge twice | A failed POST must be repeated by the user |
| In-memory storage | Clean start on every run, no setup | Data is lost on restart; a single instance per service |

The full reasoning, class diagrams, design patterns, sequence diagrams and non-functional requirements are in **[docs/DESIGN.md](docs/DESIGN.md)**. All diagrams are in `docs/diagrams/` as SVG with Mermaid sources.

---

## 2. Features at a glance

| Area | What you can do |
|---|---|
| **Accounts** | `login` with just a name: new names are registered with a **Rs. 500 cash deposit** ("Rs. 500 received as deposit in cash."). Names are case-insensitive. `logout`. |
| **Catalogue (admin)** | `admin` is the librarian: **add** books, **list** them with who borrowed each, its **due date** and **days overdue**, and how many members are waiting. Admin cannot borrow. |
| **Borrowing (members)** | **borrow** a book: it is due in **14 days**. **return** it. **waitlist** a borrowed book: when it is returned it goes straight to the first member in line, who is told at their next login. |
| **Return date** | **Extend** a loan by **7 days, at most twice** per book (not once it is overdue). |
| **Overdue books** | Highlighted as **OVERDUE** with the fee so far. **Rs. 10 per day** is deducted from the deposit on return. While a member has an overdue book, or a deposit balance of Rs. 0 or less, they **cannot borrow or join wait lists**. |
| **Deposit** | See the balance and history in *My account*, and **add a cash deposit** (minimum Rs. 100). |
| **status** | *My account* shows borrowed books with due dates, wait-list positions and deposit balance. |
| **Dashboard (admin)** | Above the catalogue the admin sees a **library dashboard**: how many members are registered and how many have books out, books out vs. available, **overdue** loans (with late fees so far) and loans **due within 3 days**, wait-list requests and deposits held. A *Books out* table (filter: needs attention / overdue / due soon / all) shows each book, who has it, its due date and status; a *Members* table shows each member's books, overdue and due-soon counts, wait lists, deposit and whether they are blocked. It refreshes after every change and every 30 s. |
| **Demo clock** | As admin, move the library date **+1 / +7 / +15 days** to see due dates, overdue blocks and late fees without waiting. |
| **Resilience** | Circuit breakers, retries, timeouts, fallbacks and rate limiting; if one part fails, the rest of the UI keeps working. |

Details: [Instruction manual](#4-instruction-manual) · [Lending rules](#lending-rules) · [Assumptions](#8-assumptions-and-decisions).

How it is built: see [1. Architecture](#1-architecture) above and [docs/DESIGN.md](docs/DESIGN.md).

---

## 3. Quick start

```bash
./start.sh
```

Then open **http://localhost:3000**. Press `Ctrl+C` to stop.

* **First run:** downloads only what is missing (npm packages and Maven libraries; Java, Node.js and Maven themselves only if they are not installed) and builds everything. This takes a few minutes, longer if a JDK has to be downloaded.
* **Later runs:** nothing is downloaded again. Tools are cached in `.tools/`, npm packages in `frontend/node_modules`, Maven libraries in `~/.m2`. The backend is rebuilt only if its sources changed, so start-up is mostly JVM boot time (about 20–30 s).
* **Every run is a clean start for the application**: no users, books or sessions are kept between runs (all data is in memory). Cached tools and libraries are not application state.
* `./start.sh --mock` runs only the UI, against an in-memory mock gateway. No Java is needed, which is handy for UI work.
* **Windows:** run it from **Git Bash** (comes with Git for Windows) or WSL, not CMD or PowerShell.

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21+ | optional: uses `JAVA_HOME` or `java` on `PATH`; if neither is 21+, `start.sh` downloads Eclipse Temurin 21 into `.tools/` (SHA-256 verified, about 200 MB) |
| Node.js | 20.11+ | optional: if it is missing or too old, `start.sh` downloads Node.js 22 LTS into `.tools/` (SHA-256 verified against nodejs.org) |
| Maven | 3.6.3+ | optional: if it is missing, `start.sh` downloads Maven 3.9.9 into `.tools/` (SHA-512 verified) |
| Internet | first run | Maven Central and the npm registry, plus adoptium.net, nodejs.org and apache.org if those tools are downloaded |

So on a machine with nothing installed except Git Bash (Windows) or a normal shell (macOS/Linux), `./start.sh` is enough.

Free ports needed: 3000–3003 and 8080–8082. Each can be changed with an environment variable (see §7).

---

## 4. Instruction manual

Each command from the brief maps to the UI as follows.

| Brief command | In the UI |
|---|---|
| `login [name]` | Type your name on the start page and press **Log in**. Unknown names are registered automatically. Names are case-insensitive. A greeting panel shows the same text the CLI would print (borrowed books, wait lists, and *"You got the book … from your waitlist"*). |
| `add [book_name]` | Log in as **`admin`**. An **Add a book to the library** form appears at the top of the *Catalogue*. Admin is a librarian account: it manages the catalogue (and sees how many members are waiting for each book) but cannot borrow, return or join wait lists. |
| `list` | The **Catalogue** panel, e.g. `1. The Great Gatsby (available)`. It refreshes automatically after every change. |
| `borrow [book_name]` | **Borrow** on the book's row, or type the title in **Know the title? Act on it directly** and press **Borrow**. |
| `return [book_name]` | **Return** in *My account*, on the catalogue row, or via the by-title form. |
| `waitlist [book_name]` | **Join wait list** on a borrowed book's row, or **Wait list** in the by-title form. The row then shows your position. |
| `status` | The **My account** panel: *Your borrowed books* (with **due date**, extensions left, overdue warning and late fee so far), *Your wait lists* with positions, and your **deposit balance** (↻ refreshes). |
| *extend* (new) | **Extend** next to a borrowed book in *My account*: moves the return date by 7 days, at most twice per loan. |
| *deposit* (new) | **Add deposit (cash)** in *My account* (minimum Rs. 100). *Deposit history* lists deposits and late fees. |
| `logout` | **Log out** in the top-right corner ("Goodbye, *name*!"). |

Every action answers with a notification (toast) that uses the brief's wording, for example `You borrowed "1984". Please return it by 08 Dec 2026.` or `Sorry, "1985" is not registered.`

### Lending rules

* **Registration:** a member's first login records a **Rs. 500 cash deposit** ("Rs. 500 received as deposit in cash."). No password.
* **Due date:** 14 days after borrowing. **Extend** moves it by 7 days, **at most twice** per loan (allowed even if others are waiting, not once the book is overdue).
* **Not returned on time:** **Rs. 10 per day** late fee, deducted from the deposit on return. While a member has an overdue book, or the balance is Rs. 0 or less, they **cannot borrow or join wait lists** until they return it or top up.
* **Admin (librarian)** sees who has each book, the due date and how many days it is overdue.
* **See it without waiting 14 days:** logged in as admin, use **Library date: +1 / +7 / +15 days** (demo clock) to move time forward. Disable it with `LIBRARY_DEMO_TIME_TRAVEL=false ./start.sh`.

### Replaying the example session

1. Log in as `admin`. The catalogue says *No books are registered*. Add `The Great Gatsby` and `1984`, then log out.
2. Log in as `Alice` and borrow **1984**. Log out.
3. Log in as `Bob`. In the by-title form, try `1985` (not registered) and `1984` (not available). Join the wait list for **1984** (position 1) and borrow **The Great Gatsby**. Log out.
4. Log in as `Carol` and join the wait list for **1984** (position 2). Log out.
5. Log in as `Alice` and return **1984**. Both books now show *(borrowed)*, because 1984 went straight to Bob. Log out.
6. Log in as `Bob`. The greeting shows *You got the book "1984" from your waitlist*.
7. Log in as `Carol`. She is now position 1. Returning **The Great Gatsby** by title gives *Sorry, you didn't borrow "The Great Gatsby".*

This exact scenario is automated twice: over HTTP with REST Assured and in the browser with Playwright.

---

## 5. Tests

### Backend: JUnit 5, REST Assured, Spring Boot Test

```bash
mvn -f backend/pom.xml verify          # or: "$(scripts/ensure-maven.sh)" -f backend/pom.xml verify
```

| Module | Highlights |
|---|---|
| `library-service` | `ExampleSessionRestAssuredTest` replays the brief over HTTP. `LendingRulesRestAssuredTest` covers due dates, extensions, overdue blocking, late fees and deposits (with the demo clock). `BookControllerRestAssuredTest` covers 400/401/403/404/409/415/503 mapping. `UserServiceClientResilienceTest` runs the real retry and circuit breaker against a fake user-service. Also domain and use-case unit tests, including a 32-thread concurrent-borrow test. |
| `user-service` | `SessionControllerRestAssuredTest` (login/current/logout, validation, request-id propagation); session expiry; value objects |
| `gateway` | `GatewayRestAssuredTest`: routing with prefix stripping, request-id generation and propagation, **circuit breaker fallback** (5xx and unreachable service), **429 rate limiting**, health |
| `service-commons` | Correlation-id filter, including log-injection protection |

### Frontend: unit tests (`node:test`) and Playwright end-to-end

```bash
cd frontend
npm install                     # once
npm test                        # circuit breaker, retry, timeout, API client, remote loader, event bus

npx playwright install chromium # once, downloads the browser
npm run e2e                     # full stack: starts ../start.sh (or reuses a stack already on :3000)
npm run e2e:mock                # same UI suite against the mock gateway (no JVM)
```

Playwright specs (`frontend/e2e/tests`):

* `01-example-session` runs the whole example from the brief through the UI. It expects a **freshly started** stack.
* `05-admin-dashboard` covers the librarian dashboard: members never see it, loans and members appear, and an overdue loan is flagged with its fee.
* `04-lending-rules` covers due dates, two extensions then blocked, overdue blocking, the Rs. 10/day fee, and deposit top-up (uses the admin demo clock).
* `02-ui-behaviour` covers login validation, case-insensitive names, reload persistence, a stale session after a backend restart, admin duplicate handling, catalogue/account sync, and **micro-frontend failure isolation + retry**.
* `03-resilience` checks that the **client circuit breaker** opens on repeated 503s, shows a banner and recovers (uses the mock's outage switch).

Reports go to `frontend/playwright-report/`; traces, videos and screenshots of failures go to `frontend/test-results/`.

---

## 6. Project layout

```
.
├── start.sh                        one-command build & run
├── scripts/
│   ├── start-all.mjs               starts the 3 JVMs (readiness-gated) + frontends, graceful Ctrl+C
│   ├── ensure-java.sh              uses JDK 21+ from JAVA_HOME/PATH or bootstraps Eclipse Temurin 21
│   ├── ensure-maven.sh             uses mvn from PATH or bootstraps Maven (no wrapper jar in the repo)
│   └── ensure-node.sh              uses node from PATH or bootstraps Node.js 22 LTS
├── backend/                        Maven multi-module (Java 21, Spring Boot 3.5, Spring Cloud 2025.0)
│   ├── service-commons/            error envelope + correlation-id filter (auto-configured)
│   ├── user-service/               identity: domain / application / adapter (hexagonal)
│   ├── library-service/            books, loans, wait lists: domain / application / adapter (hexagonal)
│   └── gateway/                    routes, Resilience4j circuit breakers + fallbacks, retry, rate limit
├── frontend/                       npm workspaces (React 19, TypeScript, esbuild)
│   ├── packages/contracts/         API DTOs + micro-frontend mount contract (types)
│   ├── packages/resilience/        circuit breaker, retry, timeout (dependency-free)
│   ├── apps/shell/                 host: session, API client, event bus, layout, remote loading
│   ├── apps/mfe-catalog/           remote: list / add / borrow / wait list
│   ├── apps/mfe-account/           remote: status / return
│   ├── apps/mfe-admin/             remote: librarian dashboard (admin only)
│   ├── scripts/                    build (esbuild), static servers, mock gateway
│   └── e2e/                        Playwright page object + specs
└── docs/
    ├── DESIGN.md                   architecture, class & sequence diagrams, NFRs, ADRs, assumptions
    └── diagrams/                   Mermaid sources (.mmd) and rendered SVGs
```

---

## 7. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SHELL_PORT`, `CATALOG_PORT`, `ACCOUNT_PORT`, `ADMIN_PORT` | 3000, 3001, 3002, 3003 | frontend origins |
| `GATEWAY_PORT`, `USER_SERVICE_PORT`, `LIBRARY_SERVICE_PORT` | 8080, 8081, 8082 | service ports |
| `LIBRARY_DEMO_TIME_TRAVEL` | true | admin's demo clock (+1/+7/+15 days); set `false` to disable |
| `LIBRARY_TIME_ZONE` | Asia/Kolkata | time zone for due dates |
| `JAVA_OPTS` | `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -Xmx256m` | JVM flags (tuned for fast local start) |
| `STARTUP_TIMEOUT_MS` | 180000 | how long to wait for each service's readiness probe |

Resilience settings (thresholds, timeouts, rate limits) are in each service's `application.yml`.

Useful endpoints while running: `http://localhost:8080/actuator/health`, `/actuator/circuitbreakers`, `/actuator/gateway/routes`, and the same `health`/`circuitbreakers` endpoints on 8082. Service logs are in `logs/*.log`, with `[requestId]` on every line.

**Try the resilience features:** with the app open, stop library-service (`kill` its PID or close it). The gateway answers with fast 503 fallbacks, the UI shows *"The library service is unavailable…"*, and the catalogue offers *Try again*. The other services stay up.

---

## 8. Assumptions and decisions

The full table with reasoning is in [docs/DESIGN.md §9](docs/DESIGN.md#9-decisions-on-ambiguities-and-assumptions). In short:

* **Browser UI** instead of a CLI (allowed by the brief). Messages use the CLI wording.
* **No passwords**: `login` registers unknown names. User names and book titles are **case-insensitive** and whitespace-normalised. `admin` (any case) is the only user who can add books, and it is a **librarian-only** account: it cannot borrow, return or join wait lists.
* **One copy per title**. Adding a duplicate title is rejected.
* **On return, the book goes straight to the first person on the wait list.** It stays *borrowed* and everyone else moves up one position, as in the example. That person sees *"You got the book … from your waitlist"* once, at their next login or refresh.
* You cannot wait-list an available book, a book you hold, or a book you are already waiting for. Each case gets a clear message.
* The greeting always starts with *Hello, name!* (the example omits it once, which reads as a typo).
* **Lending rules** (added on request): 14-day loans, 2 extensions of 7 days, Rs. 10/day late fee from a Rs. 500 cash deposit, borrowing blocked while overdue or in debt. See the *Lending rules* section above.
* No borrow limits, lost-book charges or leaving a wait list (not requested; listed as future work).

---

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| `Port 3000 is already in use` / a service never becomes ready | Stop the other process, or set e.g. `SHELL_PORT=4000 GATEWAY_PORT=9080 ./start.sh` |
| `Backend build failed` | Run `mvn -f backend/pom.xml package` to see the error; check internet access to Maven Central |
| Browser shows the login page after restarting | Expected: sessions do not survive a restart (clean start) |
| Playwright: `Executable doesn't exist` | `cd frontend && npx playwright install chromium` |
| No lock file | `package-lock.json` is not committed because the archive was produced without registry access. `npm install` resolves the pinned and caret ranges in `package.json`; commit the generated lock file for reproducible installs. |
