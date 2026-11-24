# Library Management

A small library system: members register with a cash deposit, borrow books with a due date, extend or return them (late returns cost a fee), and join wait lists; an admin (librarian) manages the catalogue.

It is built as **Spring Boot microservices** behind a **Spring Cloud Gateway**, with a **React micro-frontend** UI. Resilience patterns (circuit breakers, retries, timeouts, fallbacks and rate limiting) are applied at every hop.

```
Browser ── shell :3000 ──┬── catalog remote :3001        (React micro-frontends, runtime composition)
                         ├── account remote :3002       (members)
                         ├── admin remote   :3003       (librarian dashboard)
                         └── /api ─► gateway :8080 ──┬─► user-service    :8081  (login, sessions)
                                                     └─► library-service :8082  (books, loans, wait lists, deposits)
                                                                  └─► user-service (token check, circuit breaker)
```

## Features at a glance

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

Details: [Instruction manual](#2-instruction-manual) · [Lending rules](#lending-rules) · [Assumptions](#6-assumptions-and-decisions).

The architecture, **class diagrams**, **design patterns**, NFRs, decisions and trade-offs are in **[docs/DESIGN.md](docs/DESIGN.md)**. Diagrams are also in `docs/diagrams/` as SVG (with Mermaid sources).

---

## 1. Quick start

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

Free ports needed: 3000–3003 and 8080–8082. Each can be changed with an environment variable (see §5).

---

## 2. Instruction manual

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

## 3. Tests

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

## 4. Project layout

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

## 5. Configuration

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

## 6. Assumptions and decisions

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

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `Port 3000 is already in use` / a service never becomes ready | Stop the other process, or set e.g. `SHELL_PORT=4000 GATEWAY_PORT=9080 ./start.sh` |
| `Backend build failed` | Run `mvn -f backend/pom.xml package` to see the error; check internet access to Maven Central |
| Browser shows the login page after restarting | Expected: sessions do not survive a restart (clean start) |
| Playwright: `Executable doesn't exist` | `cd frontend && npx playwright install chromium` |
| No lock file | `package-lock.json` is not committed because the archive was produced without registry access. `npm install` resolves the pinned and caret ranges in `package.json`; commit the generated lock file for reproducible installs. |
