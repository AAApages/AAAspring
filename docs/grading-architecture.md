# Grading Architecture Guide

## Overview

Assignment grading covers a submission from the moment it is saved to the last teacher review after the assignment closes:

- **Before close.** Each submission is graded by the AI. Teachers and student teachers (assignment creators and assigned graders) can replace that grade with a manual one, remove it again, or ask for an AI re-grade.
- **Close.** A teacher closes the assignment once. Every grade becomes final and is copied to the gradebook (`SynergyGrade`). There is no reopen.
- **Reviews and petitions.** After close, a grade changes only through a review: a teacher opens one, records evidence and decides. A student can petition their own grade, which a teacher decides the same way.

The code lives in `src/main/java/com/open/spring/mvc/assignments/`. The Spring tracker (`/mvc/assignments/tracker`, `static/js/grading/`) is the teacher UI; students see grades and file petitions on the Pages submissions page (`assets/js/submissions/grading.js`).

## The standard files

Grading follows the `mvc/toolchain` and `mvc/calendar` layout. `AssignmentSubmission`, its `AssignmentSubmissionJPA` repository and `AssignmentSubmissionAPIController` are older and keep their names until the assignments package moves.

| File | Job |
| --- | --- |
| `GradeReviewApiRestController` | `/api/grade-reviews`: reviews and petitions. Holds the request records and the `ReviewView` and `PetitionView` responses. |
| `AssignmentClosureApiRestController` | `POST /api/assignments/{id}/close`. |
| `GradeReviewEntity`, `SubmissionGradeEventEntity` | One row of `assignment_grade_review` and `assignment_submission_grade_event`. |
| `GradeReviewJpaRepository`, `SubmissionGradeEventJpaRepository` | Read and write those tables. |
| `SubmissionGradingService` | The grading rules. Every write to a submission's content or grade goes through it. |
| `AssignmentClosureService`, `FinalGradeTransfer` | Close an assignment, then copy its grades to the gradebook. |
| `GradeReviewService` | Open, petition, collect evidence, decide. |
| `SubmissionAiGrader` | Calls the AI outside any transaction and records the result for the revision it graded. |
| `AssignmentTableInitializer`, `AssignmentSubmissionTableInitializer`, `SubmissionGradeEventTableInitializer`, `GradeReviewTableInitializer` | Create or upgrade each table at startup, because `ddl-auto` is `none`. They share `TableSchema` for the SQLite and MySQL differences. |

Services return a typed `GradingOutcome`. `GradingResponse.statusOf` is the only place an outcome becomes an HTTP status, and the pages hold the text for each one. `GradingOutcomeTextTest` (Spring) and `tests/test_submission_grading.mjs` (Pages) fail if an outcome has no text.

## Lifecycle

Blue is the AI, green is a person, purple is the close, red is a refusal, and amber is the effective grade.

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
```mermaid
flowchart TD
    classDef ai      fill:#dbeafe,stroke:#2563eb,stroke-width:1.5px,color:#0c2d6b
    classDef manual  fill:#dcfce7,stroke:#16a34a,stroke-width:1.5px,color:#0b3d1f
    classDef final   fill:#ede9fe,stroke:#7c3aed,stroke-width:1.5px,color:#3b0764
    classDef refusal fill:#fee2e2,stroke:#dc2626,stroke-width:1.5px,color:#7f1d1d
    classDef hub     fill:#fef3c7,stroke:#d97706,stroke-width:2px,color:#78350f

    subgraph Before["Before close"]
        SUB(["Student submits"]) --> REV["Revision 1 saved, SUBMITTED"]
        REV --> AIQ["AI grades the revision"]
        AIQ -.->|"AI_FAILED, queued"| AIQ2["Background re-grade"]
        AIQ2 --> AIQ
        AIQ -.->|"AI_NOT_GRADEABLE"| NG(["No grade recorded"])
        AIQ -->|"graded"| OVR{"Manual grade active?"}
        OVR -->|"no, AI_GRADE_APPLIED"| EFF
        OVR -->|"yes, MANUAL_OVERRIDE_KEPT"| EFF

        EFF{{"Effective grade: manual if set, else AI"}}
        EFF --> ACT(["Grader or student action"])

        ACT -->|"manual grade"| VAL{"Within 0 to points?"}
        VAL -->|"MANUAL_GRADE_APPLIED"| EFF
        VAL -.->|"INVALID_GRADE"| EFF

        ACT -->|"Use AI grade"| UAG{"Manual grade active?"}
        UAG -->|"MANUAL_OVERRIDE_CLEARED"| EFF
        UAG -->|"no AI grade yet, queued"| AIQ
        UAG -.->|"NO_MANUAL_OVERRIDE"| EFF

        ACT -->|"AI re-grade request"| AIQ

        ACT -->|"student resubmits"| CHG{"Content changed?"}
        CHG -->|"REVISION_CREATED"| NEWREV["Next revision, its AI grade cleared"]
        NEWREV -->|"manual grade carries over, MANUAL_OVERRIDE_KEPT_BY_REVISION"| AIQ
        CHG -.->|"CONTENT_UNCHANGED"| EFF

        ACT -->|"student petitions"| PET{"Manual grade active?"}
        PET -.->|"NO_MANUAL_OVERRIDE"| EFF

        ACT -.->|"older revision, STALE_REVISION 409"| STALE(["Nothing saved"])
    end

    PET -->|"REVIEW_OPENED"| EV
    EFF ==>|"teacher closes"| CLOSE

    subgraph Close["Close, teacher only, no reopen"]
        CLOSE["POST close"]
        CLOSE -.->|"not a teacher"| NP(["NOT_PERMITTED"])
        CLOSE -.->|"closing again"| AC(["ASSIGNMENT_CLOSED"])
        CLOSE --> C1["Lock submissions, close, clear AI queue"]
        C1 --> C2["FINALIZED per submission"]
        C2 --> C3["Copy grades to the gradebook"]
        C3 --> C6(["ASSIGNMENT_FINALIZED"])
        C6 -.->|"submits, edits, AI, overrides"| LC(["ASSIGNMENT_CLOSED 409"])
    end

    C6 ==>|"teacher opens a review or student petitions"| OPEN

    subgraph Reviews["Reviews and petitions"]
        OPEN["Open with a reason: REGRADE_REQUEST, SIMILARITY_CHECK, PETITION"]
        OPEN -.->|"guard failed"| GRD(["REASON_REQUIRED, REVIEW_REQUIRES_CLOSE, REVIEW_ALREADY_OPEN"])
        OPEN -->|"REVIEW_OPENED"| EV["Evidence: notes, AI re-evaluation, identical check"]
        EV --> DEC{"Teacher decides"}
        DEC -.->|"RATIONALE_REQUIRED, DECISION_REQUIRED"| EV
        DEC -->|"NO_CHANGE"| NC(["Grade kept, REVIEW_NO_CHANGE"])
        DEC -->|"ADJUST"| AVAL{"Within 0 to points?"}
        AVAL -.->|"INVALID_GRADE, review stays open"| EV
        AVAL -->|"valid"| CLOSED{"Assignment closed?"}
        CLOSED -->|"yes, AUTHORIZED_CORRECTION"| GC(["Gradebook updated, GRADE_CORRECTED"])
        CLOSED -->|"no, MANUAL_OVERRIDE_SET"| PRE(["MANUAL_GRADE_APPLIED"])
        GC -->|"another review"| OPEN
        NC -.->|"deciding again, REVIEW_NOT_OPEN"| DEC
    end

    PRE --> EFF

    class AIQ,AIQ2,OVR,NG ai
    class SUB,REV,ACT,VAL,UAG,CHG,NEWREV,PET,OPEN,EV,DEC,AVAL,CLOSED,NC,GC,PRE manual
    class CLOSE,C1,C2,C3,C6 final
    class STALE,NP,AC,LC,GRD refusal
    class EFF hub
```

### Resubmitting after a manual grade

A resubmission, whether an edit or a new submit for the same assignment (`AssignmentSubmissionHistoryService`), is a new revision of the same submission, and the manual grade stays on it. The AI grades the new content in the background and saves its grade next to the manual one (`MANUAL_OVERRIDE_KEPT`), so the grader can compare them, but students keep seeing the manual grade. The history records `REVISED` followed by `MANUAL_OVERRIDE_KEPT_BY_REVISION` at the new revision.

To get the new revision looked at, the student petitions. Before close a student can petition only a manual grade, because an AI grade already follows every revision. After close they can petition any grade. A teacher decides a petition like any other review:

- `NO_CHANGE` keeps the grade.
- `ADJUST` before close sets a new manual grade (`MANUAL_GRADE_APPLIED`). The gradebook is written at close.
- `ADJUST` after close is an `AUTHORIZED_CORRECTION` (`GRADE_CORRECTED`) and updates the gradebook.

A grader can also act on the new revision without a petition: give a new grade, or pick "Use AI grade" to switch to the AI's grade for that revision.

### Rules

- The effective grade (`grade`) is `manualGrade` while `manualOverride` is set, otherwise `aiGrade`.
- `aiGrade` is kept while overridden. It is saved but not effective.
- A manual grade carries over to new revisions. Only a grader removes it.
- Every grading write names its revision. A write for an older revision is refused with `STALE_REVISION` (409), and nothing is saved.
- Only a teacher can close, and there is no reopen. After close, submissions, edits, AI grades (late results included), overrides and re-grade requests are refused with `ASSIGNMENT_CLOSED` (409).
- An AI re-evaluation during a review is stored on the review only and never changes the grade.
- A submission has at most one open review at a time, whether a teacher opened it or a student petitioned.

## Night grading

The self-hosted models (Qwen, Gemma 4 on the school GPUs) are free but too slow for the submit request, so they grade at night. `AssignmentNightGradingService` runs during a window (default 22:00 to 06:00 America/Los_Angeles) and never calls a paid model:

| Path | During the window |
| --- | --- |
| Night run | House model only, one item at a time: queued submissions (`needs_ai_regrade`), then never AI-graded ones, then open assignments without a ready rubric. Each item is tried at most once a night. |
| 5-minute cloud queue, "Run queue now", rubric requests after auto-create | Wait until the window closes. |
| Student submits (`autoGrade`) | The revision is queued for the night run instead of graded. |
| Teacher-clicked Grade now, AI re-grade, review AI evaluation | Unchanged (cloud). |

Without a house model, or while its server is down, the night run grades nothing and reports what is waiting; the cloud queue grades it after the window closes. Grades go through `SubmissionAiGrader` and `SubmissionGradingService` like any AI grade, so manual grades, revisions and closed assignments are respected.

- `aiconnections/AiHouseModelClient` speaks the OpenAI-compatible API (`{url}/chat/completions`, `{url}/models`) that Ollama, vLLM and llama.cpp serve. It tries the primary model, then the backup; strips Qwen `<think>` blocks; and after a failed connection skips the server for 10 minutes. `AiGradingRouter.generateOvernight` is the only grading entry to it and never falls back to Gemini or Groq.
- `aiconnections/AiHouseSettingsRepository` holds the settings in `ai_house_settings` (name/value rows). A value saved on the page wins; otherwise these `.env` keys apply: `AI_HOUSE_PRESET`, `AI_HOUSE_URL`, `AI_HOUSE_MODELS` (`primary,backup`), `AI_HOUSE_API_KEY`, `AI_HOUSE_TIMEOUT_SECONDS` (900), `NIGHT_GRADING_ENABLED`, `NIGHT_GRADING_START`, `NIGHT_GRADING_END`, `NIGHT_GRADING_ZONE`. A saved API key is encrypted with `AI_KEY_ENCRYPTION_KEY` and never returned.
- The **Night grading** tab on `/mvc/synergy/ai-queue` shows the status and has a "Run night pass now" button. Admins edit the settings there: the model server dropdown comes from `AiHouseSettingsRepository.Preset`, and **Test connection** fills the model dropdowns from the server's model list. When the GPU box is ready, add a preset line with its URL and models and it appears in the dropdown. Teachers see the settings read-only.

## REST operations

Every route requires a signed-in account.

| Method | Route | Who | Request | Response |
| --- | --- | --- | --- | --- |
| POST | `/api/submissions/grade/{id}?grade&feedback&revision` | grader | | `GradingResponse` |
| DELETE | `/api/submissions/grade/{id}?revision` | grader | | `GradingResponse` |
| POST | `/api/submissions/ai-grade/{id}` | grader | | `GradingResponse` |
| POST | `/api/submissions/submit/{assignmentId}` | submitter | `SubmitAssignmentDto` | submission; a resubmit revises the existing one |
| PUT | `/api/submissions/{id}` | submitter | multipart content | submission |
| GET | `/api/submissions/{id}/grading-history` | grader or submitter | | `GradingHistoryResponse` |
| POST | `/api/assignments/{id}/close` | teacher | | `ClosureResponse` |
| POST | `/api/grade-reviews/submission/{id}` | teacher | `OpenReviewRequest` | `ReviewResponse` |
| POST | `/api/grade-reviews/submission/{id}/petition` | submitter | `PetitionRequest` | `PetitionResponse` |
| GET | `/api/grade-reviews/submission/{id}/petitions` | submitter | | list of `PetitionView` |
| GET | `/api/grade-reviews/submission/{id}` | teacher | | list of `ReviewView` |
| GET | `/api/grade-reviews/assignment/{id}/open` | teacher | | list of `ReviewView` |
| PUT | `/api/grade-reviews/{reviewId}/evidence` | teacher | `EvidenceRequest` | `ReviewResponse` |
| POST | `/api/grade-reviews/{reviewId}/ai-evaluation` | teacher | | `ReviewResponse` |
| POST | `/api/grade-reviews/{reviewId}/similarity` | teacher | | `ReviewResponse` |
| POST | `/api/grade-reviews/{reviewId}/decision` | teacher | `DecisionRequest` | `ReviewResponse` |
| GET | `/api/night-grading/status` | signed in | | `Status` |
| POST | `/api/night-grading/run-now` | teacher | | `StartResult` (202 started, 409 busy, 200 with counts when no house model) |
| GET | `/api/night-grading/settings` | teacher | | settings, presets, `canEdit` |
| PUT, DELETE | `/api/night-grading/settings` | admin | `Changes` / none (reset to `.env`) | settings |
| POST | `/api/night-grading/settings/test-connection` | admin | `ConnectionTest` | `ModelList` |
| POST | `/api/night-grading/settings/test-prompt` | admin | | model and elapsed time |

`PetitionView` shows the student their message, the decision and its rationale, but not the teacher's evidence, AI re-evaluation or similarity matches.

Test the routes with the VS Code REST Client and [`api/grading.http`](../api/grading.http). It uses `baseUrl`, `adminUid` and `adminPassword` like the other `.http` files, plus `studentUid` and `studentPassword`, all set in the Git-ignored `.vscode/settings.json`. It works on a throwaway `[TEST]` assignment, closes it, and deletes it at the end.

## Persistence and concurrency

- The content and grading columns of `AssignmentSubmission` are mapped `updatable = false`. Never set them on an entity and `save()`; `SubmissionGradingService` changes them with the conditional UPDATEs in `AssignmentSubmissionJPA`.
- Each write takes the row lock first (`lockForGrading`, a no-op UPDATE), because SQLite has no `SELECT ... FOR UPDATE` and a read-then-write transaction fails there with `SQLITE_BUSY`. Close locks the assignment's submissions and then the assignment, the same order every grading path uses.
- Don't save a new `GenerationType.AUTO` entity after taking a lock: its id comes from a sequence table on another connection, which on SQLite waits on that lock. That's why `FinalGradeTransfer` runs after the close or decision commits.
- The table initializers run before the other startup runners. `AssignmentSubmissionTableInitializer` also sorts grades saved before these columns existed into manual or AI, once. When you add a field to one of the entities, add its column to that table's initializer too.
- `SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_RELEASED_BY_REVISION` stays for history rows written before manual grades carried over to new revisions.

## Tests

`./mvnw test -Dtest='Submission*Test,Grad*Test,Assignment*Test,TableSchemaTest'` runs the grading tests. `SubmissionGradingPersistenceTest` runs the rules, petitions and races against a real SQLite file. Each `*TableInitializerTest` builds its table in an in-memory SQLite database, including from the older shapes of the table. In Pages, `node --test tests/test_submission_grading.mjs` covers the shared grading module.
