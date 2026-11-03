# Introduction

Founded and maintained by John Mortensen, Open Coding Society's Spring project supports students as they progress from learning object-oriented programming in Java to building integrated, data-driven applications.

In the OCS computer science pathway, students move beyond individual classes and methods to explore how software components work together. Spring provides a foundation for building APIs, organizing application logic, mapping objects to persistent data, and connecting Java applications to databases and other services.

The project connects classroom concepts—including classes, objects, encapsulation, and data structures—to the architecture of working applications. Students can examine how controllers, services, persistence layers, and application models fit together, then use those patterns in their own projects.

As students progress into Computer Science A and Honors Capstone, this foundation supports more substantial work: integrating frontend applications with backend services, managing shared data, implementing application workflows, and collaborating on systems that serve real users.

This repository is both a starter for student projects and a codebase to learn from. Students can use it as a template, adapt its components to new problems, and contribute improvements back to Open Coding Society.

The goal is not simply to learn a framework. It is to develop the engineering judgment needed to understand, build, test, and maintain software systems.

## Runtime links

Backend UI

Use login with .env setup user to manage and restore data.

- Runtime link: https://spring.opencodingsociety.com/

API access

Validate system is up by testing an endpoint

- Jokes endpoint: https://spring.opencodingsociety.com/api/jokes/

Examine JWT Login

Review cookies after accessing a page that needs them (ie Groups)

- JWT Login: https://pages.opencodingsociety.com/login

## Backend UI purpose

This Backend UI is to manage adminstrative functions like reseting passwords and managing database content: CRUD, Backup, and Restore.

- Thymeleaf UI should be visual and practical
- Home page is organized with Bootstrap menu and cards
- Most menus and operations are dedicated to Tables
- Some sample menus exist to reference basic capability

## Backend Primary purpose

The site is build on Springboot.  The project is primarly used to store and retrieve data through APIs.  The site has JWT authorization and implements security.  In optimal deployed form the data would be served through a professional database, it supports SQLite for development and deployment verification.

## Architecture guides

- [Group chat architecture guide](docs/group-chat-architecture.md) — explains groups and group chat: the REST routes, the STOMP WebSocket on port 8589, S3 message storage, a diagram, known issues, and `api/groups.http`.
- [Toolchain architecture guide](docs/toolchain-architecture.md) — explains Setup, Scoring, Help, the interactive local agent, report-upload versus station-check flows, API routes, and persistence.
- [Announcement architecture guide](docs/announcement-architecture.md) — a small example of the five standard parts (REST controller, entity, JPA repository, service, table initializer), with its routes, a diagram, and `api/announcement.http`.
- [Grading architecture guide](docs/grading-architecture.md) — AI and manual grades, revisions, student petitions, closing an assignment, and teacher reviews. Try the routes with [api/grading.http](api/grading.http).
- [UI Builder architecture guide](docs/ui-builder-architecture.md) — how Spring stores, checks, publishes and shares the Pages UI Builder's page documents: who may edit and see a page, the row lock and versions, the routes, the tables, known issues, and `api/ui-builder.http`.
- [Lesson feedback architecture guide](docs/lesson-feedback-architecture.md) — students' two-step ratings of a team-taught lesson, locked ratings, homework that waits for feedback, and the teachers' live Lesson Summary. Try the routes with [api/lessonfeedback.http](api/lessonfeedback.http).

The Java implementation for CS Pathway is now together in `src/main/java/com/open/spring/mvc/toolchain/`. The interactive Toolchain Trail agent/session flow is the current integrated station-check implementation. A separate text-report upload and AI-review flow is still present in the code; confirm whether students still use it before removing it. Existing `/api/cs-pathway` and `/api/toolchain` routes are preserved even though the Java package and class names are now unified.

## Testing Toolchain REST endpoints

Use the **REST Client** VS Code extension and select the `local` or `deployed` environment:

- [api/cspathway.http](api/cspathway.http) tests the existing `/api/cs-pathway/...` compatibility routes for setup reports, help requests, and scores.
- [api/toolchain.http](api/toolchain.http) tests the interactive `/api/toolchain/...` agent/session flow.

Authenticate first for requests that require the signed-in game/student. Configure `baseUrl`, `adminUid`, and `adminPassword` under `rest-client.environmentVariables` in `.vscode/settings.json`. The `.vscode/` directory is Git-ignored; keep passwords and other secrets there locally, never in an `.http` file or commit. The Toolchain agent example submits deliberately failing sample evidence unless replaced with actual local results. Treat returned pairing codes and agent tokens as credentials. Test only systems and accounts you are authorized to use.

## Getting started

Java 21 or higher is requirement using VSCode tooling.

- Install Java 21: **macOS** `brew install --cask temurin@21` | **Linux** `sudo apt install openjdk-21-jdk`
- Clone project, open in VSCode
- Run `Main.java` (if issues: `Ctrl+Shift+P` → "Java: Reload Projects")
- Browse to http://127.0.0.1:8585/

**Build Commands:**
```bash
./mvnw clean compile    # Build
./mvnw test            # Test  
./mvnw spring-boot:run # Run
```

**Key Files:** Java source (`src/main/java/...`) | templates and application.properties (`src/main/resources/templates/...`)

### Configuration Requirements

- Create custom `.env` file to setup default user passwords to satisfy code in Person.java.  Students of OCS should leave users as default until competency is obtained.
- The default password for seed users and password resets comes from `application.properties`. Set `DEFAULT_PASSWORD` in `.env` to override it. It must meet the password rule (8+ characters, uppercase, lowercase, number, symbol).

```text
person.default-password=${DEFAULT_PASSWORD:DefaultPassword123!}
```

- Modify `application.properties` ports to be unique for your indivdual project.

```text
server.port=8585
socket.port=8589
```

### First run on a fresh checkout

A new local database is empty, and the app does not create most tables itself (`ddl-auto=none`). Without this step startup fails with `Application run failed` and `no such table: person`.

1. Create the `.env` file (see Configuration Requirements above and the `.env files` section below).
2. Make sure the app is not running. `init` refuses to run while port 8585 is in use.
3. Run `python3 scripts/db_migrate.py status` and confirm it says `Mode: SQLITE` and a path under `volumes/`.
4. Run `python3 scripts/db_migrate.py init` and answer `y`. This resets only your local SQLite file.
5. Start the app with `./mvnw spring-boot:run`.

## Run Project

- Play or click entry point is Main.java, look for Run option in code.  This eanbles Springboot to build and load.
    - If you do not see the `Run | Debug` option in code, install the **Java Extension Pack** (by Microsoft) and **Spring Boot Extension Pack** (by VMware)
- Load loopback:port in browser (http://127.0.0.1:8585/)
- Login to ADMIN (toby) user using ADMIN_PASSWORD, examing menus and data
- Try API endpoint: http://127.0.0.1:8585/api/jokes/


## IDE management

- Extension Pack for Java from the Marketplace, you may need to close are restart VSCode
- A ".gitignore" can teach a Developer a lot about Java runtime.  A target directory is created when you press play button, byte code is generated and files are moved into this location.
- "pom.xml" file can teach you a lot about Java dependencies.  This is similar to "requirements.txt" file in Python.  It manages packages and dependencies.

## .env files

The `.env` file provides local environment-specific configuration that overrides `application.properties`. This file is excluded from git (via `.gitignore`) to prevent committing sensitive credentials and local settings.

**How it works:**
- Spring Boot loads `application.properties` first (production defaults)
- Then imports `.env` which overrides those values
- Properties in `.env` take precedence over `application.properties`

**Example .env setup for local development:**

Supply credentials for the features you use; they are not all required to start Spring.
The same AI settings apply in production, using either `.env` or runtime environment variables.

```bash
# Default password for seed users and resets (overrides person.default-password)
DEFAULT_PASSWORD=123Qwerty!

# Admin user defaults
ADMIN_NAME=Thomas Edison
ADMIN_UID=toby
ADMIN_EMAIL=toby@example.com
ADMIN_SID=0000001
ADMIN_PASSWORD=123Toby!
ADMIN_PFP=/images/toby.png

# Teacher user defaults
TEACHER_NAME=Nikola Tesla
TEACHER_UID=niko
TEACHER_EMAIL=niko@example.com
TEACHER_SID=0000002
TEACHER_PASSWORD=123Niko!
TEACHER_PFP=/images/niko.png

# Default user for testing 
USER_NAME=Grace Hopper
USER_UID=hop
USER_EMAIL=hop@example.com
USER_SID=0000003
USER_PASSWORD=123Grace!
USER_PFP=/images/hop.png

# Convience user defaults
MY_NAME=John Mortensen
MY_UID=jm1021
MY_SID=0000004
MY_EMAIL=jmort1021@gmail.com

# JWT Cookie Settings - Local Development (HTTP)
# These override the production defaults in application.properties
jwt.cookie.secure=false
jwt.cookie.same-site=Lax

# Reference Points (optional - defaults exist in application.properties)
GEMINI_API_URL=https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent
GROQ_SERVER=https://api.groq.com/openai/v1/chat/completions
PAGES_BOT_UID=pages-bot
PAGES_BOT_EMAIL=pages-bot@example.com

# Shared AI API keys (required only for the corresponding system AI features)
GEMINI_API_KEY=your-gemini-api-key-here
# Final system grading fallback; personal Groq keys do not require this key
GROQ_API_KEY=your-groq-key-here
# Model ID available to your Groq accounts, used by personal and system requests
# Optional override; this is also the application.properties default
GROQ_GRADING_MODEL=llama-3.1-8b-instant

# Keys/Tokens (required only for the corresponding features)
# GitHub Reference Points
GITHUB_API_TOKEN=your-github-token-here
GIST_TOKEN=your-github-gist-token-here
GIST_TOKEN_ENCRYPTION_KEY=your-encryption-token
# Personal AI key storage: permanent Base64-encoded 32-byte key
# Generate once with openssl rand -base64 32; preserve it across deployments
AI_KEY_ENCRYPTION_KEY=replace-with-your-permanent-base64-encoded-32-byte-key
# Bot Password
PAGES_BOT_PASSWORD=replace-with-a-strong-local-password

# Email Configuration (optional - overrides application.properties)
# spring.mail.username=your-email@gmail.com
# spring.mail.password=your-app-password

# S3 Bucket Defaults
AWS_BUCKET_NAME=my-bucket
AWS_ACCESS_KEY_ID=local-access-key
AWS_SECRET_ACCESS_KEY=local-secret-key-change-me
AWS_REGION=us-east-2
# if developing locally, set the endoint heree
# AWS_S3_ENDPOINT=http://localhost:9000
```

### Personal AI keys and grading

- `GROQ_API_KEY` is the final shared/system grading fallback, not a student's personal key.
  Students save their own keys through Profile; no manual `.env` entry or database insert
  is needed for each student.
- `AI_KEY_ENCRYPTION_KEY` encrypts stored personal Gemini and Groq keys. Generate it once
  with `openssl rand -base64 32`, store it securely, and use the same value on every Spring
  instance. Back it up separately from the database. Replacing or losing it makes existing
  encrypted keys unreadable and requires users to reconnect.
- Spring creates `ai_connections` at startup independently of Hibernate's `ddl-auto=none`.
  The database account needs permission to create the table and select, insert, update,
  and delete its rows. No database reset is needed to enable personal keys.
- Saving personal keys requires a valid encryption key, accessible storage, a configured
  provider model, and successful provider key validation. A missing/invalid encryption key
  or unavailable storage disables personal-key access; it does **not** prevent submission
  uploads or grading with configured system keys. If grading fails, the submission stays
  saved for manual review.
- `GROQ_GRADING_MODEL` selects the model for both personal and shared Groq requests.
  It defaults to `llama-3.1-8b-instant` when omitted. Use a supported model ID with access
  and quota for the intended accounts. Setting it explicitly blank disables Groq grading
  and personal Groq connections.
- `GROQ_SERVER` configures the Groq chat-completions URL; key validation uses the `/models`
  endpoint under the same API base. `GEMINI_API_URL` likewise applies to personal and
  system Gemini grading.
- After deployment/restart, an authenticated `GET /api/ai-connections` should report
  `available: true` and `modelConfigured: true` for the intended providers. This confirms
  local configuration/storage readiness, not live model access or quota.

See [Personal AI grading keys](docs/personal-ai-keys.md) for routing, deployment, and testing.
The legacy `GAMIFY_API_URL` and `GAMIFY_API_KEY` names are not consumed by the current
Spring implementation and are not required.

**Production Configuration:**
- Production uses the secure defaults from `application.properties` (HTTPS settings)
- Supply production credentials through `.env` or the Spring runtime's environment;
  application defaults do not provide AI API keys or personal-key encryption keys.
- Use environment variables on production servers if preferred (e.g., `JWT_COOKIE_SECURE=true`).
  Restart/redeploy Spring after changing configuration; keep the permanent encryption key unchanged.

**Important:** Never commit the `.env` file to git. It contains sensitive credentials and local-only settings.

## Person MVC

![Class Diagram](https://github.com/user-attachments/assets/26219a16-e3dc-45e3-af1c-466763957dce)

- Basically there is a rough MVCframework.
- The webpages act as the view. These pages can view details about the users, and request the controller to change details about them
- The controller is mainly "personViewController" for the backend, but other controllers include "personApiController" for the front end.
- Techincally the image is wrong, "personDetailsService" is a controller. It is used by other controllers to change the database, so it seemed more accurate to call it a part of the model, rather than a controller.
- The person.java is the pojo (object) that is used for the database schema.


## Database Management Workflow with Scripts

> **Which database am I on?** `application.properties` defaults to
> `jdbc:sqlite:volumes/sqlite.db` and only switches to MySQL when `DB_URL` is set. If
> `DB_URL` is commented out in `.env`, the deployment is running on that **local SQLite
> file**, and that file — not RDS — holds the live data. Always run
> `python3 scripts/db_migrate.py status` first; every command follows the same detection,
> so `backup`, `init` and `restore` all act on whichever database the app itself uses.

Two deployment shapes are supported, and the scripts handle both. See
**SQLite deployment** below for the current one; the MySQL procedure that follows applies
once `DB_URL` is set and RDS becomes the live database again.

Note: the MySQL procedure assumes production is on RDS. Be sure all PRs are merged, pulled
and tested before you touch production either way.

0. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` in `.env` (pointing at production RDS) and
   create a venv with `mysql-connector-python` installed. `mysqldump` must be on PATH.

   Confirm what you are pointed at, and that the schema translation is sound:
   > python3 scripts/db_migrate.py status
   > python3 scripts/db_migrate.py check

1. Pull production into a local SQLite backup. This records production's exact MySQL DDL
   and row counts alongside the data, and **exits non-zero if any table or row is missing**.
   > python3 scripts/db_migrate.py backup

   The backup lands in `volumes/backups/mysql_backup_<timestamp>.db`. Do not proceed if
   this command fails -- an incomplete backup is not a valid migration source.

2. Point your local app at that backup (or at `volumes/sqlite.db`) and TEST TEST TEST.
   Make sure the new code works with real production data.

3. Verify the new schema builds cleanly on a scratch MySQL database first, if you have one.

4. On production (cockpit, `open/spring`):
   - Take spring down: `docker compose down`
   - Update code: `git pull`
   - Rebuild the schema with Hibernate (native MySQL DDL, no cross-dialect translation):
     `python3 scripts/db_migrate.py init`

5. Load your data on top of the new schema:
   > python3 scripts/db_migrate.py restore --keep-target-schema --backup-file volumes/backups/mysql_backup_<timestamp>.db

   This takes a `mysqldump` rollback point before touching anything, loads only the columns
   the old and new schemas share (reporting added/dropped columns), and re-counts every
   table afterwards. It exits non-zero if MySQL does not match the backup.

6. Bring spring up: `docker compose up -d --build`

### Restoring production exactly as it was (rollback)

`mysqlrestore.py` without `--keep-target-schema` rebuilds each table from the MySQL DDL
recorded in the backup -- types, indexes, UNIQUE keys and foreign keys included -- so it
reproduces the source database rather than approximating it:

> python3 scripts/db_migrate.py restore --backup-file volumes/backups/mysql_backup_<timestamp>.db

The `mysqldump` safety dump taken before any destructive run is the faster rollback:

> mysql -h <host> -P <port> -u <user> -p <database> < volumes/backups/predrop_<db>_<timestamp>.sql

### Notes on what the scripts guarantee

- **All tables, including Hibernate Envers audit tables** (`HT_*`, `HTE_*`) and the
  Hibernate id-allocation tables (`*_seq`). The app runs with
  `spring.jpa.hibernate.ddl-auto=none`, so Hibernate will *not* recreate anything that
  gets dropped -- losing `*_seq` would restart id allocation at 1 and collide with
  existing rows, and losing `HTE_*` breaks every write to an audited entity.
- **Row-count reconciliation** on both directions. Any shortfall is a non-zero exit,
  never a printed warning.
- Older backups that predate the recorded-DDL format still restore, via a fallback that
  derives types from SQLite. That path is lossy and the script says so loudly; prefer
  `--keep-target-schema`.

## SQLite deployment (current state)

With `DB_URL` unset, production data lives in `volumes/sqlite.db`. The sequence is the
same shape as the MySQL one, and `db_migrate.py` picks the SQLite code paths for you:

```bash
python3 scripts/db_migrate.py status              # confirm Mode: SQLITE
python3 scripts/db_migrate.py backup              # must exit 0
docker compose down
git pull
python3 scripts/db_migrate.py init                # fresh schema from the JPA entities
python3 scripts/db_migrate.py restore --backup-file volumes/backups/sqlite_backup_<ts>.db
docker compose up -d --build
```

`backup` uses SQLite's online backup API, not a file copy. The database runs in WAL mode,
so a `cp` of `sqlite.db` can silently miss everything still sitting in the `-wal` file.

`restore` keeps the schema `init` just built and loads only the columns both sides share.
Columns added by your change take their defaults; columns and tables removed by it are
listed explicitly rather than being dropped in silence. `*_seq` tables come across intact,
so Hibernate keeps allocating ids where it left off instead of restarting at 1.

Rolling back is a file copy, because the backup *is* a complete database:

```bash
docker compose down
cp volumes/backups/sqlite_backup_<ts>.db volumes/sqlite.db
rm -f volumes/sqlite.db-wal volumes/sqlite.db-shm
docker compose up -d
```

### How the scripts fit together

`db_migrate.py` is the only entry point you need:

| Command | What it does |
| --- | --- |
| `status` | Prints the configured target and what is currently in it |
| `check` | Round-trips the schema through both translators and fails if they disagree |
| `backup` | Verified backup of the live database (MySQL or SQLite) |
| `init` | Rebuilds the schema on the target with Hibernate |
| `restore` | Loads a backup back into the target |

Underneath, `mysqlbackup.py` / `mysqlrestore.py` (MySQL) and `sqlite_migrate.py` (SQLite)
hold the backup and restore implementations and can be run directly -- `db_migrate.py` calls straight into them, so
there is one implementation, not two. Everything shared between them (reading `.env`,
parsing `DB_URL`, opening connections, the backup metadata table name) lives in
`mysql_common.py`, so the two scripts cannot disagree about which database they are
talking to.

**Why `check` exists.** The MySQL-to-SQLite and SQLite-to-MySQL type maps are inverse
functions living in two different files. Nothing structural forces them to stay inverse,
and when they drifted the round trip quietly turned every `VARCHAR` and `DATETIME` column
into `LONGTEXT`. `check` runs every table in `schema_full.txt` through both directions and
fails on any degradation. It needs no database and no driver, so it is safe to run
anywhere, including CI. Run it after any change to either map.

`schema_full.txt` is a point-in-time snapshot, so it goes stale as entities are added.
It is fixture data for `check` and nothing else — no part of the application reads it.
To check against the schema that actually exists right now:

> python3 scripts/db_migrate.py check --live

### Removed scripts

`db_prod2local.py`, `db_local2prod.py`, `db_mysql2local.py`, `db_local2mysql.py` and
`db_prod_to_mysql.py` predated the MySQL migration and have been **deleted**. If you find
one in an old branch or a stale checkout, do not run it. `db_local2mysql.py` in particular
carried its own independent MySQL writer that received none of the schema, safety-dump or
row-count fixes -- running it against production would reintroduce every bug those fixes
addressed. They remain recoverable from git history if you ever need to read them.

The current scripts are: `db_migrate.py` (entry point), `mysql_common.py` (shared config
and connections), `mysqlbackup.py` / `mysqlrestore.py` (MySQL), `sqlite_migrate.py`
(SQLite), `db_init.py` (schema rebuild) and `migration_utils.py` (Spring Boot runner).

These seven migration files are byte-identical to the ones in `spring`. A fix applied to one
repo belongs in the other -- check both before you consider a migration bug closed.

### The full runbook

The step-by-step production procedure -- with the gates, the rollback paths, and the audit
of what was wrong with the old scripts -- lives at
<https://pages.opencodingsociety.com/documentation/migration-runbook>. Read it before your
first migration; this section is the summary.

## Local S3 Development

If you are developing a component of Spring that requires AWS S3 bucket connections, you can emulate the bucket locally with RustFS. You will need to install [Docker](docs.docker.com/engine/install/) (or a similar containerization software).

1. Start the local instance of RustFS:
```
docker-compose -f rustfs.yml up -d
```
2. Uncomment this line in `.env`:
```env
AWS_S3_ENDPOINT=http://localhost:9000
```
3. Log into `http://localhost:9001` with these keys:
```env
RUSTFS_ACCESS_KEY=local-access-key # ID
RUSTFS_SECRET_KEY=local-secret-key-change-me # password
```
4. Create a bucket in RustFS called `my-bucket` (or whatever you set `AWS_BUCKET_NAME` to).
5. Run `spring` and check if messages are written to the bucket via the Group Chat service.

# Testing Grade FRQs API with Postman

## Step 1: Authenticate

**POST** `http://127.0.0.1:8585/authenticate`

**Headers:** `Content-Type: application/json`

**Body:**
```json
{
  "uid": "toby",
  "password": "123Toby!"
}
```

**Action:** Send request → Copy `jwt_java_spring` token from Cookies tab

## Step 2: Grade FRQs

**POST** `http://127.0.0.1:8585/api/grade-frqs`

**Headers:** `Cookie: jwt_java_spring=YOUR_TOKEN_HERE`

**Action:** Send request

## Creating a GitHub Gist Token

1. Sign in to [GitHub](https://github.com).
2. Click your profile picture (top right) → **Settings**.
3. In the left sidebar, scroll down and click **Developer settings**.
4. Go to **Personal access tokens** → **Tokens (classic)**.
5. Click **Generate new token** → **Generate new token (classic)**.
6. Fill in the form:
   - **Note:** give it a name you'll recognize (e.g. `my-app-gist`)
   - **Expiration:** pick how long the token should last
   - **Scopes:** check only **`gist`**
7. Scroll down and click **Generate token**.
8. **Copy the token now.**
