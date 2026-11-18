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
      <div style="white-space:pre-wrap;word-break:break-word">${value == null || value === '' ? 'Not provided' : linkify(value)}</div></div>`;
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

  // ---------- links and progress ----------
  /** Escapes text and turns http(s) URLs in it into links that open in a new tab. */
  function linkify(text) {
    return esc(text).replace(/https?:\/\/[^\s<>"']+/g, raw => {
      const url = raw.replace(/[.,;:!?)\]]+$/, '');
      return `<a href="${url}" target="_blank" rel="noopener">${url}</a>${raw.slice(url.length)}`;
    });
  }

  /** A progress bar: determinate when total is given, otherwise indeterminate (busy). */
  function progressHtml(label, done, total) {
    const known = total != null && total > 0;
    const pct = known ? Math.min(100, Math.round(done / total * 100)) : 0;
    return `<div class="g-progress${known ? '' : ' busy'}" role="progressbar" aria-label="${esc(label || 'Loading')}"
        ${known ? `aria-valuemin="0" aria-valuemax="${total}" aria-valuenow="${done}"` : ''}><span style="${known ? `width:${pct}%` : ''}"></span></div>
      ${label ? `<div class="g-progress-label">${esc(label)}</div>` : ''}`;
  }

  /** A loading placeholder for a table body or an empty container. */
  function loadingHtml(label = 'Loading…', colspan) {
    const inner = `<div class="g-loading">${progressHtml(label)}</div>`;
    return colspan ? `<tr><td colspan="${colspan}">${inner}</td></tr>` : inner;
  }

  /** Disables a button and shows a busy bar in it; call the returned function to restore it. */
  function busy(button, label) {
    const html = button.innerHTML;
    button.disabled = true;
    button.innerHTML = `${esc(label || button.textContent)}<div class="g-progress busy"><span></span></div>`;
    return () => { button.innerHTML = html; button.disabled = false; };
  }

  // ---------- revision history (shared by the tracker and the student card) ----------
  /** Line diff of two texts (LCS), as [{type: 'same'|'add'|'del', line}]. */
  function lineDiff(before, after) {
    const a = String(before ?? '').split('\n');
    const b = String(after ?? '').split('\n');
    if (a.length * b.length > 250000) {
      return [...a.map(line => ({ type: 'del', line })), ...b.map(line => ({ type: 'add', line }))];
    }
    const lcs = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0));
    for (let i = a.length - 1; i >= 0; i--) {
      for (let j = b.length - 1; j >= 0; j--) {
        lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    const out = [];
    let i = 0, j = 0;
    while (i < a.length && j < b.length) {
      if (a[i] === b[j]) { out.push({ type: 'same', line: a[i] }); i++; j++; }
      else if (lcs[i + 1][j] >= lcs[i][j + 1]) out.push({ type: 'del', line: a[i++] });
      else out.push({ type: 'add', line: b[j++] });
    }
    while (i < a.length) out.push({ type: 'del', line: a[i++] });
    while (j < b.length) out.push({ type: 'add', line: b[j++] });
    return out;
  }

  function diffHtml(before, after) {
    const marks = { same: '  ', add: '+ ', del: '- ' };
    return `<div class="g-panel g-diff">${lineDiff(before, after)
      .map(d => `<span class="${d.type}">${esc(marks[d.type] + d.line)}</span>`).join('')}</div>`;
  }

  function restoreVersion(submissionId, version) {
    return postJson(`/api/submissions/${submissionId}/history/restore`,
      { submissionId: version.submissionId, revision: version.revision });
  }

  /**
   * Shows a submission's versions like GitHub revisions: a dropdown (newest selected), the chosen
   * version, an optional diff against the version before it, and "Restore this version", which
   * submits the old content as a new revision. {@code onRestored} runs after a restore.
   */
  async function showHistory(target, submissionId, { submitter, points, canRestore = true, onRestored } = {}) {
    target.innerHTML = loadingHtml('Loading history…');
    let history;
    try {
      history = await api(`/api/submissions/${submissionId}/history`);
    } catch (e) {
      target.innerHTML = `<div class="g-sub">Could not load history: ${esc(e.message)}</div>`;
      return;
    }
    const versions = history.versions;
    const scale = history.points ?? points;
    const when = v => v ? new Date(v).toLocaleString() : null;
    const label = (v, i) => {
      const name = v.current ? `Revision ${versions.length} (current)` : `Revision ${versions.length - i}`;
      const at = when(v.submittedAt);
      return at ? `${name} · ${at}` : name;
    };
    target.innerHTML = `
      <div class="g-toolbar">
        <select data-h="pick" aria-label="Revision">${versions.map((v, i) => `<option value="${i}">${esc(label(v, i))}</option>`).join('')}</select>
        <label class="g-sub mb-0"><input type="checkbox" data-h="diff"> Compare with previous</label>
        <button class="g-btn" type="button" data-h="restore">Restore this version</button>
        <span class="g-sub mb-0">${versions.length} version${versions.length === 1 ? '' : 's'}</span>
      </div>
      <div data-h="body"></div>`;
    const pick = target.querySelector('[data-h=pick]');
    const diff = target.querySelector('[data-h=diff]');
    const restore = target.querySelector('[data-h=restore]');
    const closed = !!history.closed;
    // Key order differs between the stored row and history events, so compare with sorted keys.
    const same = (x, y) => {
      const stable = v => v && typeof v === 'object' && !Array.isArray(v)
        ? Object.keys(v).sort().reduce((o, k) => { o[k] = stable(v[k]); return o; }, {}) : v;
      return JSON.stringify(stable(x ?? null)) === JSON.stringify(stable(y ?? null));
    };
    function render() {
      const i = Number(pick.value);
      const v = versions[i];
      const previous = versions[i + 1];
      const matchesCurrent = !v.current && same(v.content, versions[0].content);
      restore.style.display = v.current || matchesCurrent || !canRestore || closed ? 'none' : '';
      diff.disabled = !previous;
      const replaced = (v.replacedAt ? `<div class="g-sub">Replaced ${esc(when(v.replacedAt))}</div>` : '')
        + (matchesCurrent ? '<div class="g-sub">Same as the current version.</div>' : '');
      const changes = diff.checked && previous
        ? `<div class="g-sub mb-1">Changes from ${esc(label(previous, i + 1))}</div>${diffHtml(contentText(previous.content), contentText(v.content))}`
        : '';
      target.querySelector('[data-h=body]').innerHTML = replaced + changes + submissionDetailHtml(v, submitter, scale);
    }
    pick.addEventListener('change', render);
    diff.addEventListener('change', render);
    restore.addEventListener('click', async () => {
      const v = versions[Number(pick.value)];
      if (!confirm('Restore this version? It is submitted again as a new revision; nothing in the history is deleted.')) return;
      const done = busy(restore, 'Restoring…');
      try {
        const result = await restoreVersion(history.submissionId, v);
        toast(gradingOutcomeText(result.outcome));
        if (onRestored) await onRestored(result);
        await showHistory(target, submissionId, { submitter, points, canRestore, onRestored });
      } catch (e) {
        toast(e.message, false);
        done();
      }
    });
    render();
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
    gradeSourceBadge, closeAssignment, reviews, REVIEW_REASONS, REVIEW_DECISIONS, SIMILARITY_BASIS,
    linkify, progressHtml, loadingHtml, busy, diffHtml, showHistory };
})();
