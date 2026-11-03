# Lesson Feedback Architecture Guide

## Overview

Students rate a lesson that a student team taught. Feedback is saved in two steps:

- **At the lesson:** Lesson Quality (`lesson`), Presentation/Engagement (`engage`) and Popcorn Hacks (`popcorn`), 1–5, plus one Pros and one Grows box. Everything is optional.
- **At homework submission:** saved ratings are locked, empty ones must be filled, Homework Hacks (`homework`) is added, and Pros and Grows can be edited. The result must be complete.

Everyone who submits the homework rates the lesson, the teaching team and teachers included, and the homework is refused until their feedback is complete. Teachers and the assignment's graders see a live Lesson Summary: averages, how many students gave each rating, and the comments without names. Pages draws the forms and the summary (`_projects/systems/lesson-feedback/` in pages_lessons); this package stores the feedback and enforces every rule.

Feedback belongs to an **assignment**, which already holds what the rules need:

| Need | Where it comes from |
|---|---|
| The lesson page | `Assignment.contentUrl` |
| Whether homework waits for feedback | `Assignment.teamTeach`, the page's `team_teach: true`, set only by the Pages sync bot through `/api/assignments/auto-create` (`teamTeach`, like `creatorUids`) |
| Who sees the summary | teachers and admins, and `Assignment.assignedGraders`; not `Assignment.creators`, who taught the lesson |
| When feedback becomes final | `Assignment.closedAt`, set by `POST /api/assignments/{id}/close` |
| The student | the signed-in account, never a request field |

## The parts

| Class | Role |
|---|---|
| `LessonFeedbackRules` | Plain rules with no database: merging each step, locked ratings, completeness, and the summary. |
| `LessonFeedbackEntity` | One row of `lesson_feedback`: one student's feedback on one assignment. |
| `LessonFeedbackJpaRepository` | Finds a student's row, or every row of an assignment. |
| `LessonFeedbackService` | Applies the rules to every read and write, and answers `isFeedbackMissing` for the submission check; the only user of the repository. |
| `LessonFeedbackApiRestController` | Translates HTTP into service calls; answers rule violations as `{"error": "..."}`. |
| `LessonFeedbackTableInitializer` | Creates `lesson_feedback` when it is missing (`ddl-auto=none`). |

A row has a random UUID `id`, the sender's `uid` from the login, where it was sent from (`assignment_id`), and server times in epoch milliseconds (`created_at`, `updated_at`).

## Homework waits for feedback

`AssignmentSubmissionHistoryService.submit`, which every submit route goes through (`POST /api/submissions/{assignmentId}`, `POST /api/submissions/submit/{assignmentId}` and the file upload), first asks `LessonFeedbackService.isFeedbackMissing(person submitting, assignment)`. When the assignment has `teamTeach`, is still open, and that person's feedback is not complete, it saves nothing and returns the typed outcome `LESSON_FEEDBACK_REQUIRED` (409, through `GradingResponse`). The upload service checks before storing the file, as it does for a closed assignment. After close the outcome is `ASSIGNMENT_CLOSED` instead, since feedback can no longer be saved.

The check uses the person submitting, the same account the Pages submit button checks. Assignments without `teamTeach` (every assignment until the sync bot sets it) are unchanged. `teamTeach` is a new nullable `team_teach` column on `assignment`, added by `AssignmentTableInitializer`.

## REST operations

All routes need a signed-in account (the default `/api/**` rule).

| Route | Who | Result |
|---|---|---|
| `GET /api/lesson-feedback/{assignmentId}/mine` | anyone signed in | 200 with their feedback, empty before the first save |
| `PUT /api/lesson-feedback/{assignmentId}/lesson` | anyone signed in | 200 with the saved feedback |
| `PUT /api/lesson-feedback/{assignmentId}/submission` | anyone signed in | 200 with the saved, complete feedback |
| `GET /api/lesson-feedback/{assignmentId}/summary` | teachers, admins, the assignment's assigned graders | 200 with the summary |

The two `PUT` bodies are `LessonFeedbackRules.LessonInput` and `SubmissionInput`: `{"lesson": 4, "engage": null, "popcorn": 3, "homework": 2, "pros": "...", "grows": "..."}` (the lesson step has no `homework`). A `null` or missing field leaves the saved value as it is, and resending a saved rating is allowed, so a form can post every field it shows.

The `mine` and `PUT` responses are `LessonFeedbackService.FeedbackView`: `assignmentId`, `page`, `closed`, `feedback` (the six fields), `locked` (ratings that can no longer change), `complete`, `createdAt` and `updatedAt`.

| Status | When |
|---|---|
| 400 | A rating outside 1–5, text over 1,000 characters, an incomplete submission step (the message names the missing fields), or a body that is not the expected JSON (the controller answers this itself instead of the app-wide 500 in `ExceptionHandlerAdvice`) |
| 401 | Not signed in |
| 403 | Reading the summary as anyone but a teacher, admin or assigned grader |
| 404 | No such assignment |
| 409 | Changing a saved rating, saving after the assignment closed, or two saves racing from different tabs |

## The summary

`summary` returns `LessonFeedbackRules.Summary`: `responseCount`, the four averages (null before the first response), `counts` (for each rating, how many students gave it 1 to 5, index 0 being a 1), and the `pros` and `grows` lists. Only complete feedback counts. It is live: there is no minimum and no wait for close. Pros and Grows are shuffled separately so a comment can't be matched to its writer's ratings, their other comment, or the order students submitted in. With very few responses a teacher may still guess who wrote a comment; the PM chose a live summary for teachers anyway. There is no view of individual feedback with names.

## Persistence

`lesson_feedback` has one row per (`assignment_id`, `uid`), enforced by `uk_lesson_feedback_assignment_uid`. The `version` column is optimistic locking: two saves from different tabs can't both pass the locked-rating check. Either race (two first saves, or two edits) answers 409 with a reload hint. The service uses `saveAndFlush` so the failure happens inside the request.

`LessonFeedbackTableInitializer` only creates the table; it never changes an existing one. When you add an entity field, add its column there with an `ALTER TABLE`, as `CalendarEventTableInitializer` does.

## Deploying

Deploy this before any Pages lesson sets `team_teach: true`: the page saves feedback through these routes before it sends the homework, so without them that lesson's homework can't be submitted. The Pages sync workflow then sets `teamTeach` on its next run (on push to main, and daily).

## Testing

```bash
./mvnw test -Dtest='LessonFeedback*Test,AssignmentSubmissionHistoryTest,AssignmentsApiControllerCreatorTest'
```

`LessonFeedbackRulesTest` covers the rules, `LessonFeedbackServiceTest` the service over mocked repositories (including `isFeedbackMissing` and who sees the summary), `LessonFeedbackApiRestControllerTest` the status mapping, and `LessonFeedbackTableInitializerTest` the table on a real in-memory SQLite database. `AssignmentSubmissionHistoryTest` covers the refused submission, and `AssignmentsApiControllerCreatorTest` the sync-only `teamTeach`. Try the routes with [api/lessonfeedback.http](../api/lessonfeedback.http).
