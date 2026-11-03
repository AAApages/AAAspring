// Shared helpers for the gradebook, AI queue, grade request and assignment tracker pages.
window.Grading = (function () {
  function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  // Display text for each GradingOutcome.
  const GRADING_OUTCOME_TEXT = {
    MANUAL_GRADE_APPLIED: 'Grade saved. It replaces the AI grade.',
    MANUAL_OVERRIDE_CLEARED: 'Instructor grade removed; the AI grade applies again.',
    NO_MANUAL_OVERRIDE: 'There is no instructor grade to remove or petition.',
    AI_GRADE_APPLIED: 'AI graded the submission.',
    MANUAL_OVERRIDE_KEPT: 'AI grade saved for reference; the instructor grade still applies.',
    REVISION_CREATED: 'Saved as a new revision. An instructor grade stays until a teacher changes it.',
    CONTENT_UNCHANGED: 'Saved.',
    STALE_REVISION: 'The student changed this submission since it was loaded. Reload and review the new version.',
    INVALID_GRADE: "Enter a grade from 0 to the assignment's points.",
    NOT_PERMITTED: 'You do not have permission to do this.',
    AI_NOT_GRADEABLE: "The AI can't grade this submission.",
    AI_FAILED: 'The AI grader is unavailable right now; try again later.',
    SUBMISSION_NOT_FOUND: 'This submission no longer exists.',
    ASSIGNMENT_NOT_FOUND: 'This assignment no longer exists.',
    ASSIGNMENT_CLOSED: 'This assignment is closed, so its grades are final. Changes go through a teacher review.',
    ASSIGNMENT_FINALIZED: 'Assignment closed. Grades are final and copied to the gradebook.',
    LESSON_FEEDBACK_REQUIRED: 'Finish your lesson feedback before you submit this homework.',
    REVIEW_REQUIRES_CLOSE: 'Reviews are for closed assignments. Before close, grade or re-grade directly.',
    REVIEW_OPENED: 'Review opened.',
    REVIEW_ALREADY_OPEN: 'This submission already has an open review.',
    EVIDENCE_RECORDED: 'Evidence saved.',
    REVIEW_NOT_OPEN: 'This review is already decided.',
    REASON_REQUIRED: 'Choose a reason for the review.',
    DECISION_REQUIRED: 'Choose a decision.',
    RATIONALE_REQUIRED: 'Write a rationale for the decision.',
    REVIEW_NO_CHANGE: 'Decision recorded. The grade is unchanged.',
    GRADE_CORRECTED: 'Grade corrected and copied to the gradebook.',
    REVIEW_NOT_FOUND: 'This review no longer exists.',
  };

  // Labels for grade reviews (GradeReviewEntity on the server).
  const REVIEW_REASONS = {
    REGRADE_REQUEST: 'Re-grade request',
    SIMILARITY_CHECK: 'Similarity / copying check',
    PETITION: 'Petition or grade reconsideration',
  };
  const REVIEW_DECISIONS = { NO_CHANGE: 'No change', ADJUST: 'Grade adjusted' };
  const SIMILARITY_BASIS = { SAME_LINK: 'same link', SAME_TEXT: 'same text', SAME_FILE: 'same file' };

  function gradingOutcomeText(outcome, detail) {
    const text = GRADING_OUTCOME_TEXT[outcome] || `Request finished: ${outcome}`;
    return detail && (outcome === 'AI_NOT_GRADEABLE' || outcome === 'AI_FAILED') ? `${text} (${detail})` : text;
  }

  /** Fetches JSON; throws an Error carrying the server's message on a non-2xx response. */
  async function api(url, options = {}) {
    const response = await fetch(url, { credentials: 'include', ...options });
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch (e) { body = text; }
    if (!response.ok) {
      const message = body && body.outcome ? gradingOutcomeText(body.outcome, body.detail)
        : body && (body.error || body.message) ? (body.error || body.message)
        : (typeof body === 'string' && body ? body : `Request failed (${response.status})`);
      const error = new Error(message);
      error.body = body;
      throw error;
    }
    return body;
  }

  // ---------- grading actions (each resolves to { outcome, grading, detail }) ----------
  /** Sets a manual grade; revision is the one on screen. */
  function saveManualGrade(submissionId, grade, revision, feedback) {
    const params = new URLSearchParams({ grade });
    if (feedback != null) params.set('feedback', feedback);
    if (revision != null) params.set('revision', revision);
    return api(`/api/submissions/grade/${submissionId}`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params,
    });
  }

  function clearManualGrade(submissionId, revision) {
    const query = revision != null ? `?revision=${encodeURIComponent(revision)}` : '';
    return api(`/api/submissions/grade/${submissionId}${query}`, { method: 'DELETE' });
  }

  function requestAiGrade(submissionId) {
    return api(`/api/submissions/ai-grade/${submissionId}`, { method: 'POST' });
  }

  /** Badges for a SubmissionGradingState: grade source, and Final once closed. */
  function gradeSourceBadge(grading) {
    if (!grading) return '';
    const final = grading.closed ? ' <span class="g-badge ok" title="The assignment is closed">Final</span>' : '';
    if (grading.source === 'NONE') return final;
    return (grading.source === 'MANUAL'
      ? '<span class="g-badge warn" title="An instructor overrode the AI grade">Instructor</span>'
      : '<span class="g-badge muted" title="Graded by the AI">AI</span>') + final;
  }

  // ---------- closing and teacher reviews ----------
  function closeAssignment(assignmentId) {
    return api(`/api/assignments/${assignmentId}/close`, { method: 'POST' });
  }

  const reviews = {
    list: submissionId => api(`/api/grade-reviews/submission/${submissionId}`),
    openForAssignment: assignmentId => api(`/api/grade-reviews/assignment/${assignmentId}/open`),
    open: (submissionId, reason, evidence) => postJson(`/api/grade-reviews/submission/${submissionId}`, { reason, evidence }),
    saveEvidence: (reviewId, evidence) => api(`/api/grade-reviews/${reviewId}/evidence`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ evidence }),
    }),
    aiEvaluation: reviewId => api(`/api/grade-reviews/${reviewId}/ai-evaluation`, { method: 'POST' }),
    similarity: reviewId => api(`/api/grade-reviews/${reviewId}/similarity`, { method: 'POST' }),
    decide: (reviewId, decision) => postJson(`/api/grade-reviews/${reviewId}/decision`, decision),
  };

  function postJson(url, data) {
    return api(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  }

  function formatEta(seconds) {
    if (seconds == null) return '';
    if (seconds < 60) return '< 1 min';
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `~${minutes} min`;
    const hours = Math.floor(minutes / 60);
    return `~${hours} h ${minutes % 60} min`;
  }

  function formatPct(pct) {
    return pct == null ? '—' : `${pct}%`;
  }

  function formatGrade(grade, points) {
    if (grade == null) return '—';
    const g = Math.round(grade * 100) / 100;
    return points ? `${g} / ${points}` : `${g}`;
  }

  /** Badge for a gradebook submission status (graded / pending_ai / ungraded). */
  function statusBadge(status, etaSeconds) {
    if (status === 'graded') return '<span class="g-badge ok">Graded</span>';
    if (status === 'pending_ai') {
      const eta = etaSeconds != null ? ` · ${formatEta(etaSeconds)}` : '';
      return `<span class="g-badge warn">AI queue${esc(eta)}</span>`;
    }
    return '<span class="g-badge muted">Ungraded</span>';
  }

  function pctClass(pct) {
    if (pct == null) return '';
    return pct >= 80 ? 'text-success' : pct >= 60 ? 'text-warning' : 'text-danger';
  }

  function debounce(fn, ms = 200) {
    let timer;
    return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
  }

  /** Best readable form of a submission's content map (link, file name, text, …). */
  function contentText(content) {
    if (!content) return 'No content';
    if (typeof content === 'string') return content;
    return content.url || content.filename || content.fileName || content.path
      || content.text || content.answer || content.response || content.value || JSON.stringify(content);
  }

  /**
   * Submission details (submitter, grade, AI quality, late status, work, comment, feedback, AI summary),
   * shared by the assignment tracker and the student grade card. {@code s} needs grade, qualityScore,
   * isLate, content, comment, feedback and aiSummary.
   */
  function submissionDetailHtml(s, submitter, points) {
    const field = (label, value) => `<div class="g-panel"><div class="g-sub mb-1">${label}</div>
      <div style="white-space:pre-wrap;word-break:break-word">${esc(value == null || value === '' ? 'Not provided' : value)}</div></div>`;
    return `
      <div class="g-stats">
        <div class="g-stat"><b>${esc(submitter)}</b><span>submitter</span></div>
        <div class="g-stat"><b>${esc(formatGrade(s.grade, points))}</b><span>grade</span></div>
        <div class="g-stat"><b>${s.qualityScore == null ? '—' : s.qualityScore + '/5'}</b><span>AI quality</span></div>
        <div class="g-stat"><b>${s.isLate ? 'Late' : 'On time'}</b><span>status</span></div>
      </div>
      ${gradingPanel(s.grading, points)}
      ${field('Submitted work', contentText(s.content))}
      ${field('Student comment', s.comment)}
      ${field('Feedback', s.feedback)}
      ${field('AI summary', s.aiSummary)}`;
  }

  /** AI and instructor grades side by side, and which is effective. */
  function gradingPanel(grading, points) {
    if (!grading) return '';
    const effective = grading.source === 'MANUAL' ? 'instructor grade' : grading.source === 'AI' ? 'AI grade' : 'nothing yet';
    const final = grading.closed ? ' · final (assignment closed)' : '';
    return `<div class="g-panel"><div class="g-sub mb-1">Grading · revision ${esc(grading.revision)} · effective: ${effective}${final}</div>
      <div>AI grade: <b>${esc(formatGrade(grading.aiGrade, points))}</b>
        · Instructor grade: <b>${esc(grading.manualOverride ? formatGrade(grading.manualGrade, points) : '—')}</b></div></div>`;
  }

  let toastTimer;
  function toast(message, ok = true) {
    let el = document.getElementById('g-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'g-toast';
      el.setAttribute('role', 'status');
      document.body.appendChild(el);
    }
    el.className = `g-toast ${ok ? 'ok' : 'bad'}`;
    el.textContent = message;
    el.style.display = 'block';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.style.display = 'none'; }, 4000);
  }

  return { esc, api, postJson, formatEta, formatPct, formatGrade, statusBadge, pctClass, debounce, toast,
    contentText, submissionDetailHtml, gradingOutcomeText, saveManualGrade, clearManualGrade, requestAiGrade,
    gradeSourceBadge, closeAssignment, reviews, REVIEW_REASONS, REVIEW_DECISIONS, SIMILARITY_BASIS };
})();
