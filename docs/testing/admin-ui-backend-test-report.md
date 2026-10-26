# Admin UI Backend (Gradebook, Tracker, AI Queue): Test Evidence

Tester: Rishabh. Code under test: William's backend for the new assignment admin UI, mainly
`New Admin UI for Assignments` (0624ffe) and `Add Weights System For Assignments` (a65ff9a):
`GradebookService`, `SynergyApiController` gradebook endpoints, `AssignmentsApiController`
tracker/checks/test/regenerate endpoints, and the `AssignmentSubmissionAPIController` AI queue endpoints.

Screenshots (gradebook, hop's card, tracker, AI queue) are attached to the GitHub issue.

## Automated Tests

| Check | Result |
| --- | --- |
| Spring regression suite before testing (baseline) | **241 passed** |
| New: `AssignmentAiQueueApiTest` (queue visibility, grade-now/run-now status codes) | **7 passed** |
| New: `AssignmentTrackerApiTest` (tracker stats, rubric regenerate, checks) | **6 passed** |
| New: `HopSubmissionLifecycleTest` (CRUD as `hop` + `toby`) | **5 passed** |
| **Total** (`./mvnw test`) | **259 passed; 0 failures** |
| `./mvnw package` and live boot on SQLite | **PASS** (after the `.env` fix in finding 3) |

The new tests use plain JUnit 5 and Mockito, as AGENTS.md asks. `HopSubmissionLifecycleTest` backs the
repositories with in-memory maps, so each step sees the previous one's writes. The AI provider is
stubbed to fail in every test, which is the "AI down" path.

## API Results (live, local server)

I sent the requests in `api/assignments_gradebook.http` to a
freshly seeded local server (`scripts/db_init.py`). Gemini was pointed at a closed port, so no
metered AI calls were made. **51 requests: 50 matched the expected status.** The one miss was my
own probe, which used a person id that doesn't exist; I re-ran it with real ids (see finding 1).

| Request / check | Result | Evidence type |
| --- | --- | --- |
| Anonymous `GET /api/assignments/tracker`, `/api/synergy/gradebook/students`, `/api/submissions/ai-queue` | **401** | Live local HTTP |
| `POST /api/assignments/test` (toby) | **201**, `[TEST]` assignment, text, 1 pt, due in a week | Live local HTTP |
| `GET /api/assignments/{id}`, `GET /api/assignments/tracker` | **200**, counts 0/0/0, `rubricReady` false | Live local HTTP |
| `GET /api/assignments/{id}/checks` on a fresh test assignment | **200**, only "AI rubric" fails (no Gemini locally) | Live local HTTP |
| `PUT /api/assignments/{id}/details` | **200** | Live local HTTP |
| `PUT /api/assignments/{id}/ai-rubric` valid / blank | **200** / **400** | Live local HTTP |
| `GET /checks` on an unknown id | **404** | Live local HTTP |
| Gradebook `?course=CSA` / `BOGUS` / `NONE` | **200** / **400** / **200** (7 people) | Live local HTTP |
| `POST /api/submissions/submit/{id}` as hop | **201**, saved with no invented grade, queued for AI (`pending_ai`) | Live local HTTP |
| Submit the wrong content type / as another student | **400** / **403** | Live local HTTP |
| hop: own card, own submissions, own AI queue | **200**; queue shows only hop's entry plus the total `queueLength` | Live local HTTP |
| hop: other student's card, roster, tracker, test-create, checks, grade-now, run-now, grade, delete assignment, delete submission | **403** on all 10 | Live local HTTP |
| `POST /api/submissions/grade/{id}?grade=0.8` (toby) | **200**; card shows 80%, `gradedAt` set, status `graded` | Live local HTTP |
| `POST /api/submissions/ai-queue/{id}/grade-now` on a graded submission | **409** `not_queued` | Live local HTTP |
| `DELETE` submission, `POST /delete/{id}` assignment, then read | **200**, **200**, **404** | Live local HTTP |
| Same flow, controller level (AI failing, teacher grade final, cleanup) | pass | Mocked controller test |
| Queue ETA mapping (graded→200, busy/not_queued→409, failed→502; second run-now→409) | pass | Mocked API test |

## Findings

Ranked by severity. "William" means the issue is in the new admin UI code; "pre-existing" means it is
older code that the new UI relies on.

1. **High, pre-existing: any student can read another student's submissions and grades.**
   The new gradebook correctly returns 403 when hop opens alan's card. But logged in as hop,
   `GET /api/submissions/getSubmissions/53` returned **200** with alan's submission text, grade (0.4)
   and private feedback. `GET /api/submissions/extract/{assignmentId}` also returned **200** with every
   submission on the assignment. `GET /api/synergy/grades` (all grades) and `/grades/map/{userId}`
   have no owner check either. *Fix:* apply the gradebook rule here too (self, group member, or staff/creator/grader).
2. **Medium, William: a teacher grade can be overwritten by a late AI result.** In
   `AssignmentAiRegradeService.regrade`, the code reloads the submission, checks
   `needsAiRegrade`, then calls `save()`. A teacher grade saved between that reload and the save is lost.
   The existing test `doesNotOverwriteATeacherGradeMadeDuringRegrade` covers a grade made *before*
   the reload, not this window. *Fix:* one conditional write,
   `UPDATE ... SET grade=?, ... WHERE id=? AND needs_ai_regrade=1`, and treat 0 rows updated as "skipped".
   **Not reproduced live** (it needs exact timing); found by reading the code.
3. **Medium, pre-existing (docs): the README `.env` template crashes startup.**
   `PAGES_BOT_PASSWORD=replace-with-a-strong-local-password` fails the new password rule, so
   `AssignmentSyncAccountProvisioner` throws `ConstraintViolationException` and the app won't boot.
   *Fix:* put a compliant example in the README (for example `Replace-Me-123!`).
4. **Low, William: no range check on grades.** `POST /api/submissions/grade/{id}` accepted
   `grade=5` and `grade=-3` on a 1-point assignment (both **200**). The tracker's "Grades in range"
   check catches it afterwards. *Fix:* reject anything outside `0..maxScore(points)` with a 400.
5. **Low, William: `/details` and `/checks` disagree about points.** `PUT /details` accepts
   `points: 0` (it only rejects values below 0), and `/checks` then reports "Points must be greater than 0".
   `/create` doesn't validate points at all. *Fix:* use the same `> 0` rule everywhere.
6. **Low, pre-existing: text submissions can't be edited.** `PUT /api/submissions/{id}` only handles
   `link`/`file` and returns 400 for `text`, which is the type `/api/assignments/test` creates.
7. **UI, William:** on cards the overall % uses Bootstrap `text-success`, which is nearly invisible
   on the dark theme (the overall grade on hop's card). The "Assignment Sync Bot" account shows up in the
   gradebook's *No class* roster like a student.
8. **Info:** AGENTS.md says `GET /api/jokes/` is a no-auth smoke test, but it returns 401.
   `application.properties` also ships a real-looking Gemini API key as the default for
   `GEMINI_API_KEY`. It should be rotated and replaced with an empty default.

## UI Evidence

hop's own card: one teacher-graded submission (0.8/1, "Graded from HTTP", shown under recent
changes) and one waiting in the AI queue with an ETA. The overall grade is the hard-to-read "80%".

## How to Re-run

1. Create `.env` from the README template, with a compliant `PAGES_BOT_PASSWORD` (finding 3).
   Then run `FORCE_YES=true python scripts/db_init.py` and `./mvnw spring-boot:run`.
2. Open `api/assignments_gradebook.http` in VS Code (REST Client) and send the blocks from top to
   bottom. Ids from earlier responses carry forward automatically.
3. Unit tests: `./mvnw test -Dtest='AssignmentAiQueueApiTest,AssignmentTrackerApiTest,HopSubmissionLifecycleTest'`.

No application code was changed during testing.
