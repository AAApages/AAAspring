# Personal AI grading keys

## Behavior and scope

For an individually owned submission, grading tries the author's personal Gemini key, their
personal Groq key, the existing system Gemini key, then the system Groq key. Teacher-triggered
and background regrading use the same author's keys, not the teacher's. Group submissions
use system keys only. Identical personal/system credentials are not tried twice.

Missing keys, invalid/revoked keys, rate limits, provider outages, and timeouts advance to
the next credential. Each credential gets one request with a 15-second timeout. Refusals,
malformed responses, and invalid grading output stop that grading attempt rather than
switching providers to bypass a refusal. Existing rubric parsing and score scaling remain
unchanged. Grading failure keeps the submission saved for review; manual grading stays available.

JSON submission creation now requires authentication and verifies that the caller is the
submitter, a member of the submitting group, or authorized staff. This prevents request-body
IDs from selecting another student's personal AI credentials. Existing staff-only AI regrade
permissions are unchanged. Rubric generation and unrelated AI tools still use their existing
configuration; this feature changes only assignment submission grading.

## Profile API

- `GET /api/ai-connections`: `{available, providers: {gemini: {connected, modelConfigured, updatedAt}, groq: {...}}}`.
- `PUT /api/ai-connections/{gemini|groq}`: `{ "key": "..." }`, returns the same secret-free status.
- `DELETE /api/ai-connections/{gemini|groq}`: `204`.

Credential ownership comes only from the Spring session, including for administrators.
Writes require an allowed OCS `Origin` and `X-Origin: client`, using existing credentialed CORS.
Keys never appear in status, profile entities, ordinary database exports, or application-owned
error messages. Saving validates provider metadata access without generating content;
this is not a guarantee of available quota, billing, or access to the configured grading model.
Invalid replacement attempts preserve the old key.

## Local and production configuration checklist

### Upstream grading compatibility

The personal-key feature is integrated with upstream Spring `e5a7650b`, including
the weighted-hack grading introduced by `a65ff9af`. Provider routing changes where
the grading request is sent, not upstream's rubric selection, per-hack instructions,
weighted score calculation, or feedback storage limits.

Updating code does not regenerate an existing assignment rubric or backfill its hack
weights by itself. The existing trusted frontmatter synchronization path supplies page
content and updates hack weights. An existing valid but incomplete rubric may still
need an authorized rubric update; do not reset the database or invent a replacement
rubric to make a smoke test score match production.

### Targeted diagnostics

Groq grading automatically budgets its response from the number of required weighted-hack
scores, for every content type handled by the shared grader. The normal budget is
`384 + 64 * hackCount`; above 800 it uses compact JSON/brief feedback with a budget of
`256 + 32 * hackCount`. If that still exceeds 800, Groq is skipped and fallback continues.
No dashboard/frontmatter configuration is required, and assignment input is not shortened
by this policy. The estimates do not guarantee available quota or that every result fits.

Both personal and system Groq requests send `max_completion_tokens`. An explicit
`finish_reason: length` discards the incomplete result and permits fallback without retrying
that credential. Safety refusals and other invalid output still stop as before. Gemini,
rubric criteria, weighting, and model reasoning defaults are unchanged. These caps address
the observed 1,000-output-token-per-minute limit, not all input/output rate limits.

October 20, 2026: the full Spring suite passed 212 tests with no failures or errors.
New checks cover budget boundaries/overflow, compact requests, truncation fallback,
unchanged Gemini requests, all supported content extraction paths, and upstream
per-hack score validation. These tests use mocks, not real provider requests.

October 21, 2026: after integrating current upstream, the complete Spring suite passed
226 tests with zero failures, errors, or skips. Upstream's grading timestamps and AI queue
remain intact. The corresponding Pages branch was integrated with `08fd2d85c`; its
9 AI-profile tests, 5 Gist-profile tests, dependency check, and Makefile build passed.

Enable `logging.level.com.open.spring.mvc.aiconnections=INFO` and
`logging.level.com.open.spring.mvc.assignments.AssignmentAiGradingService=INFO` to see
submission IDs, provider/source selection, skipped steps, HTTP failure status, timings,
and grading-validation outcomes. Provider response receipt is not reported as a saved grade.
Enable INFO for `AssignmentSubmissionAPIController` / `AssignmentSubmissionUploadService`
to also see their completed automatic-grade saves. Root logging can remain WARN.
Never enable HTTP wire/header logging to troubleshoot keys. These diagnostics deliberately
exclude credentials, prompts, submission text, provider bodies, feedback text, and exception
messages. They describe new attempts only; existing failures cannot be reconstructed retroactively.

For local debugging, set `logging.file.name=/tmp/ocs-personal-ai-local-spring.log` in
the ignored `.env` and restart Spring. Filter that file for `AI grading`, `AI provider`,
and `Auto-grade` rather than sharing the full application log. No grading request is
triggered by enabling logging.

### Settings

Configure these in the environment of the Spring process, or its ignored `.env`:

| Setting | Requirement |
| --- | --- |
| `AI_KEY_ENCRYPTION_KEY` | Stable Base64-encoded 32-byte key; required only for personal-key storage |
| `GROQ_GRADING_MODEL` | Defaults to `llama-3.1-8b-instant` for personal and system Groq grading; override with an available, school-approved model supporting JSON output. Explicitly blank disables Groq. |
| `GROQ_API_KEY` | Optional system Groq credential, used last |
| `GROQ_SERVER` / `groq.api.url` | Groq chat-completions endpoint for personal and system requests; defaults to `https://api.groq.com/openai/v1/chat/completions` |
| `gemini.api.key` | Existing system Gemini configuration; retain it for the third fallback |
| `gemini.api.url` | Existing Gemini grading model endpoint; applies to personal and system Gemini requests |

Check `GEMINI_API_URL` in the local `.env` as well: it overrides the model default
in `application.properties`. A valid key can pass the `/models` connection check but
still receive HTTP 404 for a model unavailable to that project. Use a currently
supported model and confirm project access; do not replace a key based on HTTP 404 alone.

An administrator can generate the encryption key once with `openssl rand -base64 32`.
Keep it outside source control and back it up securely. Do not regenerate it on restart.
Do not change `GIST_TOKEN_ENCRYPTION_KEY`; this feature has independent encryption and leaves
existing Gist connections untouched. A missing/invalid AI encryption key disables personal
storage, not system-key grading or submission saving.

The `ai_connections` table is created additively by the repository startup migration, using
`CREATE TABLE IF NOT EXISTS`, compatible with SQLite/MySQL and `ddl-auto=none`. Never reset
the database to install this feature. Ordinary database export deliberately excludes this
credential table; disaster recovery must retain a secure native database backup and the key.
Key rotation requires an explicit re-encryption operation or users reconnecting their keys.
Disconnect removes OCS's stored key; users revoke it separately with the provider.

Before production acceptance: confirm configuration with the deployment administrator,
test both provider paths using eligible accounts, verify fallback and submission persistence,
then deploy the backend before Pages. No frontend secrets or Flask changes are required.

### Production administrator handoff

1. Configure the actual Spring runtime, not only GitHub repository/Actions secrets. The
   deployment operator owns container environment injection and restart/redeployment.
2. Generate `AI_KEY_ENCRYPTION_KEY` once with `openssl rand -base64 32`. Store the output
   securely as a single Base64 value, use the same value on every Spring instance, and
   retain it across releases. Do not paste it into a PR or logs. Back it up separately
   from the database; loss or replacement requires users to reconnect their AI keys.
3. Groq grading defaults to `llama-3.1-8b-instant`. Optionally set
   `GROQ_GRADING_MODEL=llama-3.1-8b-instant` explicitly, or choose another model available
   to the intended accounts. This is a model ID, not a secret. An explicitly blank value
   skips both personal and system Groq grading and disables Groq connection controls.
4. Optionally configure `GROQ_API_KEY` for the final system fallback. Personal Groq keys
   do not require a system Groq key. Retain the existing `gemini.api.key` system setting
   (normally supplied by `GEMINI_API_KEY`) and verify `gemini.api.url`/`GEMINI_API_URL`
   targets an available model. Personal Gemini also uses that configured endpoint.
   Provider billing, model access, and quota must be valid independently of OCS settings.
   `GROQ_SERVER` (Spring property `groq.api.url`) defaults to
   `https://api.groq.com/openai/v1/chat/completions` for both personal and system requests;
   metadata validation uses `/models` under that same API base.
5. Preserve the existing database and `GIST_TOKEN_ENCRYPTION_KEY`. Take a secure native
   database backup; ensure Spring can create `ai_connections` at startup. No database
   reset, manual schema replacement, JWT-secret change, Pages-bot change, or Flask update
   is required. Ordinary JSON database exports exclude encrypted AI credentials.
6. Deploy Spring before Pages. Confirm an authenticated `GET /api/ai-connections` returns
   `available: true` and the intended providers' `modelConfigured: true`, without keys.
   Preserve credentialed CORS for the Pages origin and the existing `X-Origin: client`
   request header. No new CORS domain or browser-visible secret is introduced.
7. Users connect eligible personal keys through production Profile Settings; local saved
   credentials are not transferred. Verify one authorized test submission saves and grades,
   then verify operational Gemini failure can fall back to Groq. A user without personal
   keys uses configured system credentials; exhausting all providers leaves work saved
   for manual review. Confirm existing Gist connections still work. Capture only sanitized
   provider/source/status and submission-ID evidence. Obtain school eligibility/privacy
   approval before general student rollout.

## Eligibility, privacy, and testing boundaries

School rollout is subject to provider eligibility and school approval. Groq's easier
registration is not an exemption from its age requirements. Gemini's terms restrict use
in apps likely to be accessed by minors, and unpaid Gemini services must not receive sensitive
or personal information. Review [Gemini terms](https://ai.google.dev/gemini-api/terms) and
[Groq terms](https://console.groq.com/docs/legal/services-agreement) before school rollout.

Quotas apply per Google Cloud project and Groq organization, not independently per API key.
Teacher/background regrading can also consume the author's quota or incur provider charges;
the Profile panel discloses this. AI grades remain subject to human review.

Automated tests use fake credentials, mocked provider HTTP responses, and isolated in-memory
SQLite. They do not prove live provider availability. Never publish real keys, cookies,
JWTs, ciphertext, or private student work as test evidence.
