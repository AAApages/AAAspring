// Assignment tracker: searchable/filterable assignment list, an assignment window with tabs,
// and test tools. Data comes from /api/assignments/tracker; helpers from grading/common.js.
(function () {
  const { esc, api, postJson, formatEta, formatGrade, debounce, toast, contentText, submissionDetailHtml,
    gradingOutcomeText, saveManualGrade, clearManualGrade, requestAiGrade, gradeSourceBadge, closeAssignment,
    reviews, REVIEW_REASONS, REVIEW_DECISIONS, SIMILARITY_BASIS,
    linkify, progressHtml, loadingHtml, busy, showHistory } = window.Grading;
  const SUBMISSION_TYPES = ['text', 'link', 'github_issue', 'code', 'file'];
  const $ = id => document.getElementById(id);

  // contentUrl is stored as a page path ("csa/lesson"); the lesson itself lives on the Pages site.
  const PAGES_BASE = /^(localhost|127\.0\.0\.1)$/.test(location.hostname)
    ? 'http://localhost:4500' : 'https://pages.opencodingsociety.com';
  function pageLink(contentUrl) {
    if (!contentUrl) return '';
    const href = /^https?:\/\//.test(contentUrl) ? contentUrl : `${PAGES_BASE}/${contentUrl.replace(/^\/+/, '')}`;
    return `<a href="${esc(href)}" target="_blank" rel="noopener">${esc(contentUrl)}</a>`;
  }

  let assignments = [];
  let current = null;          // assignment shown in the window
  let submissions = [];
  let queueEta = {};           // submissionId -> eta seconds
  let openReviews = {};        // submissionId -> open review, such as a petition before close
  let graders = new Map();     // id -> {id, name, uid} for the graders panel

  // ---------- dates: the API stores MM/DD/YYYY, date inputs use YYYY-MM-DD ----------
  function toIsoDate(mmddyyyy) {
    const m = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec((mmddyyyy || '').trim());
    return m ? `${m[3]}-${m[1].padStart(2, '0')}-${m[2].padStart(2, '0')}` : '';
  }
  function fromIsoDate(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    return m ? `${m[2]}/${m[3]}/${m[1]}` : '';
  }

  // ---------- list ----------
  async function loadAssignments() {
    try {
      assignments = await api('/api/assignments/tracker');
    } catch (e) {
      $('assignmentRows').innerHTML = `<tr><td colspan="6" class="g-empty">Could not load assignments: ${esc(e.message)}</td></tr>`;
      return;
    }
    assignments.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    buildTypeFilters();
    renderList();
    fillCheckSelect();
  }

  function buildTypeFilters() {
    const checked = new Set([...document.querySelectorAll('#typeFilters input:checked')].map(i => i.value));
    const types = [...new Set(assignments.map(a => (a.assignmentType || '(none)').toLowerCase()))].sort();
    $('typeFilters').innerHTML = types.map(t => `
      <label><input type="checkbox" value="${esc(t)}" ${checked.has(t) ? 'checked' : ''}> ${esc(t)}
        <span class="g-sub mb-0">(${assignments.filter(a => (a.assignmentType || '(none)').toLowerCase() === t).length})</span></label>`).join('');
  }

  function activeFilters() {
    return {
      types: [...document.querySelectorAll('#typeFilters input:checked')].map(i => i.value),
      due: fromIsoDate($('dueFilter').value),
      rubric: $('rubricFilter').checked,
      min: $('pointsMin').value === '' ? null : Number($('pointsMin').value),
      max: $('pointsMax').value === '' ? null : Number($('pointsMax').value),
    };
  }

  function renderList() {
    const needle = $('assignmentSearch').value.trim().toLowerCase();
    const f = activeFilters();
    const count = f.types.length + (f.due ? 1 : 0) + (f.rubric ? 1 : 0) + (f.min != null || f.max != null ? 1 : 0);
    $('filterCount').textContent = count ? `(${count})` : '';
    const rows = assignments.filter(a =>
      (!needle || (a.name || '').toLowerCase().includes(needle))
      && (!f.types.length || f.types.includes((a.assignmentType || '(none)').toLowerCase()))
      && (!f.due || toIsoDate(a.dueDate) === toIsoDate(f.due))
      && (!f.rubric || !a.rubricReady)
      && (f.min == null || (a.points ?? 0) >= f.min)
      && (f.max == null || (a.points ?? 0) <= f.max));
    $('assignmentCount').textContent = `${rows.length} of ${assignments.length}`;
    $('assignmentRows').innerHTML = rows.length ? rows.map(a => `
      <tr class="g-click" data-id="${a.id}" tabindex="0">
        <td>${esc(a.name)}</td>
        <td>${esc(a.assignmentType || '—')}</td>
        <td class="text-nowrap">${esc(a.dueDate || '—')}</td>
        <td>${a.points ?? '—'}</td>
        <td class="text-nowrap">${a.submissionCount}${a.ungradedCount ? ` <span class="g-sub">(${a.ungradedCount} ungraded)</span>` : ''}</td>
        <td>${a.rubricReady ? '<span class="g-badge ok">AI</span>' : '<span class="g-badge warn">Default</span>'}</td>
      </tr>`).join('')
      : '<tr><td colspan="6" class="g-empty">No assignments match.</td></tr>';
  }

  // ---------- assignment window ----------
  function openModal(id) { $(id).classList.add('open'); }
  function closeModal(el) {
    if (!el.classList.contains('open')) return;
    el.classList.remove('open');
    if (el.id === 'assignmentModal') syncUrl();
  }

  function openAssignment(id, tab = 'submissions') {
    current = assignments.find(a => a.id === id);
    if (!current) return false;
    closeModal($('testModal'));
    renderHeader();
    openModal('assignmentModal');
    showTab(tab);
    return true;
  }

  // ---------- deep links: /mvc/assignments/tracker?assignment=12&tab=edit ----------
  function syncUrl() {
    const url = new URL(location.href);
    if (current && $('assignmentModal').classList.contains('open')) {
      url.searchParams.set('assignment', current.id);
      const tab = document.querySelector('#assignmentModal .g-tab.active')?.dataset.tab;
      if (tab && tab !== 'submissions') url.searchParams.set('tab', tab); else url.searchParams.delete('tab');
    } else {
      url.searchParams.delete('assignment');
      url.searchParams.delete('tab');
    }
    history.replaceState(null, '', url);
  }

  function openFromUrl() {
    const params = new URLSearchParams(location.search);
    const id = Number(params.get('assignment'));
    if (!id) return;
    const tab = ['submissions', 'edit', 'rubric', 'more'].includes(params.get('tab')) ? params.get('tab') : 'submissions';
    if (!openAssignment(id, tab)) toast(`Assignment #${id} was not found`, false);
  }

  function renderHeader() {
    const a = current;
    const closed = !!a.closedAt;
    $('amTitle').textContent = a.name;
    $('amSubtitle').innerHTML = `#${a.id}${a.contentUrl ? ' · ' + pageLink(a.contentUrl) : ''} · `
      + (closed ? `<span class="g-badge ok">Closed</span> ${esc(new Date(a.closedAt).toLocaleString())}: grades are final`
        : '<span class="g-badge warn">Open</span> grading in progress');
    $('closeAssignment').disabled = closed;
    // Closed is final: nothing that changes grading inputs stays available.
    $('regenRubric').disabled = closed;
    $('saveRubric').disabled = closed;
    $('randomizeGraders').disabled = closed;
    $('editForm').querySelectorAll('input, select, textarea, button').forEach(el => { el.disabled = closed; });
    $('editClosedNote').style.display = closed ? '' : 'none';
    $('rubricClosedNote').style.display = closed ? '' : 'none';
    $('closeStatus').textContent = a.closedAt
      ? 'Closed. Grade changes now go through a teacher review on each submission.'
      : 'Closing makes every grade final and copies it to the gradebook. It cannot be undone.';
    $('amStats').innerHTML = [
      [a.submissionCount, 'submissions'],
      [a.ungradedCount, 'ungraded'],
      [a.pendingAiCount, 'in AI queue'],
      [a.averageGrade == null ? '—' : formatGrade(a.averageGrade, a.points), 'average grade'],
    ].map(([v, label]) => `<div class="g-stat"><b>${esc(v)}</b><span>${label}</span></div>`).join('');
    const total = (a.hackWeights || []).reduce((s, h) => s + (Number(h.weight) || 0), 0) || 1;
    const hacks = (a.hackWeights || []).map(h => `${h.hack} ${Math.round(h.weight / total * 1000) / 10}%`).join(', ');
    const resource = a.resourceType === 'file' ? `<a href="/mvc/assignments/tracker-v2/file/${a.id}">${esc(a.resourceFilename || 'Download')}</a>`
      : a.resourceUrl ? `<a href="${esc(a.resourceUrl)}" target="_blank" rel="noopener">${esc(a.resourceUrl)}</a>` : '';
    $('amDetails').innerHTML = [
      ['Category', esc(a.type)],
      ['Submission type', esc(a.assignmentType)],
      ['Points', esc(a.points)],
      ['Due', esc(a.dueDate)],
      ['Created', esc(a.timestamp)],
      ['Courses', esc((a.courseCodes || []).join(', '))],
      ['Hack weights', esc(hacks)],
      ['Lesson page', pageLink(a.contentUrl)],
      ['Resource', resource],
      ['Description', linkify(a.description || '')],
    ].filter(([, v]) => v).map(([k, v]) => `<tr><th>${k}</th><td>${v}</td></tr>`).join('');
    $('amRubric').textContent = a.aiRubric || '(no rubric)';
    $('amRubricBadge').innerHTML = a.rubricReady ? '<span class="g-badge ok">AI-generated</span>'
      : '<span class="g-badge warn">Default / not ready</span>';
  }

  function showTab(tab) {
    document.querySelectorAll('#assignmentModal .g-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
    document.querySelectorAll('#assignmentModal [data-panel]').forEach(p => { p.style.display = p.dataset.panel === tab ? '' : 'none'; });
    if (tab === 'submissions') loadSubmissions();
    if (tab === 'edit') fillEditForm();
    if (tab === 'rubric') { $('rubricText').value = current.aiRubric || ''; $('rubricStatus').textContent = ''; }
    if (tab === 'more') showMore('checks');
    syncUrl();
  }

  // ---------- submissions tab ----------
  /** Reloads the submissions; keepDetail leaves an open submission on screen (after a restore). */
  async function loadSubmissions(keepDetail = false) {
    if (!keepDetail) {
      $('subDetail').style.display = 'none';
      $('subList').style.display = '';
      $('subRows').innerHTML = loadingHtml('Loading submissions…', 4);
    }
    try {
      const [subs, queue, open] = await Promise.all([
        api(`/api/submissions/assignment/${current.id}`),
        api('/api/submissions/ai-queue').catch(() => ({ entries: [] })),
        reviews.openForAssignment(current.id).catch(() => []),
      ]);
      submissions = subs;
      queueEta = Object.fromEntries(queue.entries.map(e => [e.submissionId, e.etaSeconds]));
      openReviews = Object.fromEntries(open.map(r => [r.submissionId, r]));
      renderSubmissions();
    } catch (e) {
      $('subRows').innerHTML = `<tr><td colspan="4" class="g-empty">Could not load submissions: ${esc(e.message)}</td></tr>`;
    }
  }

  function submitterName(s) {
    return s.submitter?.name || (s.isGroup ? 'Group' : 'Unknown');
  }

  function renderSubmissions() {
    const needle = $('subSearch').value.trim().toLowerCase();
    const rows = submissions.filter(s => !needle || submitterName(s).toLowerCase().includes(needle));
    if (!rows.length) {
      $('subRows').innerHTML = '<tr><td colspan="4" class="g-empty">No submissions.</td></tr>';
      return;
    }
    $('subRows').innerHTML = rows.map(s => {
      const eta = queueEta[s.id];
      const status = eta != null ? `<span class="g-badge warn">AI queue · ${esc(formatEta(eta))}</span>`
        : s.grade != null ? '<span class="g-badge ok">Graded</span>' : '<span class="g-badge muted">Ungraded</span>';
      const overridden = s.grading?.manualOverride;
      const pending = openReviews[s.id];
      const flag = pending ? ` <span class="g-badge warn">${pending.reason === 'PETITION' ? 'Petition' : 'Review'} open</span>` : '';
      const reviewButton = '<button class="g-btn" type="button" data-sub="review">Review</button>';
      // Closed assignments only offer a teacher review; before close, only an open petition does.
      const actions = current.closedAt ? reviewButton
        : `${pending ? reviewButton : ''}
          <input type="number" class="g-grade-input" step="0.01" min="0" max="${current.points ?? 1}" value="${s.grade ?? ''}" placeholder="/ ${current.points ?? 1}" aria-label="Grade">
          <button class="g-btn" type="button" data-sub="grade">Save</button>
          <button class="g-btn" type="button" data-sub="ai">AI grade</button>
          ${overridden ? '<button class="g-btn" type="button" data-sub="clear" title="Remove the instructor grade so the AI grade applies">Use AI grade</button>' : ''}`;
      return `<tr data-id="${s.id}">
        <td>${esc(submitterName(s))}${s.isLate ? ' <span class="g-badge bad">Late</span>' : ''}${flag}</td>
        <td class="text-nowrap">${esc(formatGrade(s.grade, current.points))} ${gradeSourceBadge(s.grading)}</td>
        <td>${status}</td>
        <td class="text-nowrap">
          <button class="g-btn" type="button" data-sub="view">View</button>
          ${actions}
        </td></tr>`;
    }).join('');
  }

  // A submission opens on its revision dropdown: the latest version first, older ones to pick,
  // compare and restore (see Grading.showHistory).
  function showSubmission(id) {
    const s = submissions.find(x => x.id === id);
    if (!s) return;
    $('subList').style.display = 'none';
    $('subDetail').style.display = '';
    showHistory($('subDetailBody'), id, {
      submitter: submitterName(s),
      points: current.points,
      onRestored: async () => { await loadSubmissions(true); refreshCurrent(); },
    });
  }

  async function gradeRow(row, action, button) {
    const id = Number(row.dataset.id);
    const revision = submissions.find(s => s.id === id)?.grading?.revision;
    const restore = action === 'ai' ? busy(button, 'Grading…') : null;
    button.disabled = true;
    try {
      let result;
      if (action === 'grade') {
        const grade = row.querySelector('.g-grade-input').value;
        if (grade === '') throw new Error('Enter a grade first');
        result = await saveManualGrade(id, grade, revision);
      } else if (action === 'clear') {
        result = await clearManualGrade(id, revision);
      } else {
        result = await requestAiGrade(id);
      }
      const aiGrade = result.grading?.aiGrade;
      toast(gradingOutcomeText(result.outcome)
        + (action === 'ai' && aiGrade != null ? ` AI grade: ${formatGrade(aiGrade, current.points)}` : ''));
      await loadSubmissions();
      refreshCurrent();
    } catch (e) {
      toast(e.message, false);
      if (restore) restore(); else button.disabled = false;
    }
  }

  // Reload the list in the background so stats/badges stay in sync with the window.
  async function refreshCurrent() {
    const id = current?.id;
    await loadAssignments();
    if (id != null) {
      current = assignments.find(a => a.id === id) || null;
      if (current) renderHeader();
    }
  }

  // ---------- closing ----------
  async function closeCurrent() {
    const a = current;
    const pending = a.pendingAiCount ? ` ${a.pendingAiCount} submission(s) are still waiting for AI grading and will be finalized as they are.` : '';
    if (!confirm(`Close "${a.name}"? Every grade becomes final and is copied to the gradebook.${pending} This cannot be undone.`)) return;
    const restore = busy($('closeAssignment'), 'Closing…');
    try {
      const result = await closeAssignment(a.id);
      const s = result.summary;
      toast(`${gradingOutcomeText(result.outcome)} ${s.graded} graded, ${s.ungraded} ungraded, ${s.transferred} gradebook grade(s) written.`);
      restore();
      await refreshCurrent();
      await loadSubmissions();
    } catch (e) {
      toast(e.message, false);
      restore();
    }
  }

  // ---------- teacher review (after close, or a petition before) ----------
  let reviewSubmission = null;
  let openReviewId = null;

  async function openReview(submissionId) {
    reviewSubmission = submissions.find(s => s.id === submissionId);
    if (!reviewSubmission) return;
    $('reviewSubtitle').textContent = `${submitterName(reviewSubmission)} · ${current.closedAt ? 'final grade' : 'grade'} `
      + formatGrade(reviewSubmission.grade, current.points);
    $('reviewReason').innerHTML = Object.entries(REVIEW_REASONS)
      .map(([value, label]) => `<option value="${value}">${esc(label)}</option>`).join('');
    $('reviewGrade').max = current.points ?? 1;
    openModal('reviewModal');
    await renderReviews();
  }

  async function renderReviews() {
    let list;
    try {
      list = await reviews.list(reviewSubmission.id);
    } catch (e) {
      $('reviewHistory').innerHTML = `<span class="g-sub">${esc(e.message)}</span>`;
      return;
    }
    const open = list.find(r => r.status === 'OPEN');
    openReviewId = open ? open.id : null;
    $('reviewOpenForm').style.display = open || !current.closedAt ? 'none' : '';
    $('reviewOpen').style.display = open ? '' : 'none';
    if (open) {
      $('reviewOpenedBy').textContent = `${REVIEW_REASONS[open.reason]} · opened by ${open.openedBy || 'a teacher'} · grade then ${formatGrade(open.gradeAtOpen, current.points)}`;
      $('reviewEvidence').value = open.evidence || '';
      $('reviewEvidenceDetail').innerHTML = evidenceHtml(open);
    }
    const decided = list.filter(r => r.status === 'DECIDED').reverse();
    $('reviewHistory').innerHTML = decided.length ? decided.map(r => `<div class="g-panel">
        <b>${esc(REVIEW_REASONS[r.reason])}</b> · ${esc(REVIEW_DECISIONS[r.decision])}
        ${r.decision === 'ADJUST' ? ` to ${esc(formatGrade(r.adjustedGrade, current.points))}` : ''}
        <div class="g-sub">${esc(r.decidedBy || 'A teacher')} · ${esc(new Date(r.decidedAt).toLocaleString())}</div>
        <div style="white-space:pre-wrap">${esc(r.rationale)}</div></div>`).join('')
      : '<span class="g-sub">None.</span>';
  }

  function evidenceHtml(review) {
    const parts = [];
    if (review.petitionMessage) {
      parts.push(`<div><b>Student's petition:</b><div style="white-space:pre-wrap">${esc(review.petitionMessage)}</div></div>`);
    }
    if (review.aiGrade != null) {
      parts.push(`<div><b>AI re-evaluation:</b> ${esc(formatGrade(review.aiGrade, current.points))}
        <div class="g-sub" style="white-space:pre-wrap">${esc(review.aiFeedback)}</div></div>`);
    }
    if (review.similarSubmissions) {
      parts.push(review.similarSubmissions.length
        ? `<div><b>Identical submissions:</b><ul class="mb-0">${review.similarSubmissions.map(m =>
            `<li>#${esc(m.submissionId)} ${esc(m.submitterName || '')}: ${esc(m.basis.map(b => SIMILARITY_BASIS[b] || b).join(', '))}</li>`).join('')}</ul>
            <div class="g-sub">Exact matches only: a reason to look, not proof of copying.</div></div>`
        : '<div><b>Identical submissions:</b> none found.</div>');
    }
    return parts.join('');
  }

  async function reviewAction(button, action, label) {
    const restore = busy(button, label);
    try {
      const result = await action();
      toast(gradingOutcomeText(result.outcome, result.detail));
      await renderReviews();
      return result;
    } catch (e) {
      toast(e.message, false);
    } finally {
      restore();
    }
  }

  async function decideReview(event) {
    event.preventDefault();
    const decision = document.querySelector('input[name="reviewDecision"]:checked').value;
    const body = { decision, rationale: $('reviewRationale').value };
    if (decision === 'ADJUST') {
      if ($('reviewGrade').value === '') { toast('Enter the corrected grade', false); return; }
      body.grade = Number($('reviewGrade').value);
      body.feedback = $('reviewFeedback').value;
    }
    const result = await reviewAction(event.submitter || $('reviewDecisionForm').querySelector('button'),
      () => reviews.decide(openReviewId, body));
    if (result) {
      $('reviewDecisionForm').reset();
      $('reviewAdjustFields').style.display = 'none';
      await loadSubmissions();
      refreshCurrent();
    }
  }

  // ---------- edit tab ----------
  function fillEditForm() {
    const a = current;
    $('editName').value = a.name || '';
    $('editType').value = a.type || '';
    $('editPoints').value = a.points ?? '';
    $('editDue').value = toIsoDate(a.dueDate);
    $('editDescription').value = a.description || '';
    $('editContentUrl').value = a.contentUrl || '';
    const types = [...new Set([...SUBMISSION_TYPES, (a.assignmentType || '').toLowerCase()].filter(Boolean))];
    $('editSubmissionType').innerHTML = types.map(t =>
      `<option value="${esc(t)}" ${t === (a.assignmentType || '').toLowerCase() ? 'selected' : ''}>${esc(t)}</option>`).join('');
    $('editStatus').textContent = a.dueDate && !toIsoDate(a.dueDate) ? `Current due date "${a.dueDate}" isn't MM/DD/YYYY; pick a new one.` : '';
  }

  async function saveEdit(event) {
    event.preventDefault();
    const payload = {
      name: $('editName').value.trim(),
      type: $('editType').value.trim(),
      points: Number($('editPoints').value),
      dueDate: fromIsoDate($('editDue').value),
      assignmentType: $('editSubmissionType').value,
      description: $('editDescription').value.trim(),
      contentUrl: $('editContentUrl').value.trim(),
    };
    $('editStatus').textContent = 'Saving…';
    try {
      await api(`/api/assignments/${current.id}/details`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
      });
      $('editStatus').textContent = 'Saved.';
      toast('Assignment saved');
      await refreshCurrent();
    } catch (e) {
      $('editStatus').textContent = e.message;
    }
  }

  // ---------- rubric tab ----------
  async function saveRubric() {
    const rubric = $('rubricText').value;
    if (!rubric.trim()) { $('rubricStatus').textContent = 'A rubric is required.'; return; }
    $('rubricStatus').textContent = 'Saving…';
    try {
      await api(`/api/assignments/${current.id}/ai-rubric`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rubric }),
      });
      await refreshCurrent();
      $('rubricStatus').textContent = current.rubricReady ? 'Saved.'
        : 'Saved, but it is not on the points scale, so the AI will replace it.';
    } catch (e) {
      $('rubricStatus').textContent = e.message;
    }
  }

  async function regenerateRubric() {
    const restore = busy($('regenRubric'), 'Generating…');
    $('rubricStatus').textContent = 'This takes about 10-20 seconds.';
    try {
      const result = await api(`/api/assignments/${current.id}/ai-rubric/regenerate`, { method: 'POST' });
      $('rubricText').value = result.aiRubric;
      $('rubricStatus').textContent = 'New rubric saved.';
      await refreshCurrent();
    } catch (e) {
      $('rubricStatus').textContent = e.message;
    } finally {
      restore();
      if (current?.closedAt) $('regenRubric').disabled = true;
    }
  }

  // ---------- more tab ----------
  function showMore(panel) {
    document.querySelectorAll('[data-more-panel]').forEach(p => { p.style.display = p.dataset.morePanel === panel ? '' : 'none'; });
    if (panel === 'checks') renderChecks($('moreChecks'), current.id);
    if (panel === 'graders') loadGraders();
    if (panel === 'frontmatter') loadFrontmatter();
  }

  // ---------- checks and their fixes (AssignmentRubricService.Fix) ----------
  const FIX_FIELDS = {
    EDIT_NAME: 'editName', EDIT_POINTS: 'editPoints', EDIT_DUE_DATE: 'editDue',
    EDIT_SUBMISSION_TYPE: 'editSubmissionType', EDIT_CONTENT_URL: 'editContentUrl',
  };

  function fixButton(assignmentId, check) {
    if (check.ok) return '';
    if (check.fix === 'NONE') return '<a class="g-btn" href="/mvc/synergy/ai-queue">Open AI queue</a>';
    const label = check.fix === 'REGENERATE_RUBRIC' ? 'Regenerate rubric'
      : check.fix === 'REGRADE_OUT_OF_RANGE' ? 'Re-grade' : 'Fix';
    return `<button class="g-btn" type="button" data-fix="${esc(check.fix)}" data-assignment="${assignmentId}">${label}</button>`;
  }

  function checkRowsHtml(assignmentId, checks) {
    return `<table class="g-details"><tbody>${checks.map(c => `<tr>
      <th>${c.ok ? '<span class="g-badge ok">OK</span>' : '<span class="g-badge bad">Problem</span>'} ${esc(c.check)}</th>
      <td>${c.ok ? '' : linkify(c.message)}</td>
      <td class="text-end">${fixButton(assignmentId, c)}</td></tr>`).join('')}</tbody></table>`;
  }

  async function renderChecks(target, id) {
    target.innerHTML = loadingHtml('Running checks…');
    try {
      const checks = await api(`/api/assignments/${id}/checks`);
      const failed = checks.filter(c => !c.ok).length;
      const a = assignments.find(x => x.id === Number(id));
      const open = target === $('checkResults') && a
        ? ` <button class="g-btn" type="button" data-open-assignment="${a.id}">Open ${esc(a.name)}</button>` : '';
      target.innerHTML = `<div class="mb-2">${failed ? `<span class="g-badge bad">${failed} problem(s)</span>` : '<span class="g-badge ok">All checks passed</span>'}${open}</div>
        ${checkRowsHtml(id, checks)}`;
      target.dataset.checked = id;
    } catch (e) {
      target.innerHTML = `<div class="g-sub">${esc(e.message)}</div>`;
    }
  }

  /** Re-runs whatever check view the fix came from, so the problem disappears once fixed. */
  function rerunChecks(fromEl) {
    const results = fromEl.closest('#checkResults, #moreChecks');
    if (!results) return;
    if (results.id === 'checkResults' && results.dataset.all) checkAll();
    else if (results.dataset.checked) renderChecks(results, results.dataset.checked);
  }

  async function regradeOutOfRange(button) {
    const status = document.createElement('div');
    button.after(status);
    const restore = busy(button, 'Re-grading…');
    try {
      let state = await api('/api/submissions/fix-out-of-range-grades', { method: 'POST' });
      while (state.running) {
        status.innerHTML = progressHtml(`Re-graded ${state.done} of ${state.total}`, state.done, state.total);
        await new Promise(r => setTimeout(r, 2000));
        state = await api('/api/submissions/fix-out-of-range-grades');
      }
      toast(`Re-graded ${state.fixed} submission(s)${state.failed ? `, ${state.failed} failed` : ''}`, !state.failed);
    } catch (e) {
      toast(e.message, false);
    } finally {
      restore();
      status.remove();
    }
  }

  async function applyFix(button) {
    const id = Number(button.dataset.assignment);
    const fix = button.dataset.fix;
    if (FIX_FIELDS[fix]) {
      if (!openAssignment(id, 'edit')) return;
      const field = $(FIX_FIELDS[fix]);
      field.focus();
      field.classList.add('is-invalid');
      field.addEventListener('input', () => field.classList.remove('is-invalid'), { once: true });
      return;
    }
    if (fix === 'REGENERATE_RUBRIC') {
      const restore = busy(button, 'Generating…');
      try {
        await api(`/api/assignments/${id}/ai-rubric/regenerate`, { method: 'POST' });
        toast('New AI rubric saved');
        await refreshCurrent();
        rerunChecks(button);
      } catch (e) {
        toast(e.message, false);
        restore();
      }
      return;
    }
    if (fix === 'REGRADE_OUT_OF_RANGE') {
      await regradeOutOfRange(button);
      await refreshCurrent();
      rerunChecks(button);
    }
  }

  async function loadGraders() {
    $('graderResults').innerHTML = '';
    $('graderSearch').value = '';
    try {
      const list = await api(`/api/assignments/assignedGraders/${current.id}`);
      graders = new Map(list.map(p => [p.id, p]));
    } catch (e) {
      graders = new Map();
      toast(e.message, false);
    }
    renderGraders();
  }

  function renderGraders() {
    $('graderList').innerHTML = graders.size ? [...graders.values()].map(p =>
      `<button class="g-btn me-1 mb-1" type="button" data-remove="${p.id}" title="Remove">${esc(p.name)} &times;</button>`).join('')
      : '<span class="g-sub">No graders assigned.</span>';
  }

  const searchGraders = debounce(async q => {
    if (q.length < 2) { $('graderResults').innerHTML = ''; return; }
    try {
      const found = await api(`/api/synergy/gradebook/students?q=${encodeURIComponent(q)}`);
      $('graderResults').innerHTML = found.filter(p => !graders.has(p.id)).slice(0, 15).map(p =>
        `<button class="g-btn me-1 mb-1" type="button" data-add="${p.id}" data-name="${esc(p.name)}" data-uid="${esc(p.uid)}">+ ${esc(p.name)}</button>`).join('')
        || '<span class="g-sub">No students found.</span>';
    } catch (e) {
      $('graderResults').innerHTML = `<span class="g-sub">${esc(e.message)}</span>`;
    }
  }, 250);

  async function saveGraders() {
    try {
      await postJson(`/api/assignments/assignGraders/${current.id}`, [...graders.keys()]);
      toast('Graders saved');
    } catch (e) {
      toast(e.message, false);
    }
  }

  async function randomizeGraders() {
    if (!confirm('Randomly assign each submission another student to peer grade it? This replaces current submission graders.')) return;
    try {
      const message = await api(`/api/assignments/randomizeGraders/${current.id}`, { method: 'POST' });
      toast(typeof message === 'string' ? message : 'Graders randomized');
    } catch (e) {
      toast(e.message, false);
    }
  }

  async function loadFrontmatter() {
    $('frontmatterText').value = 'Loading…';
    try {
      const result = await api(`/api/assignments/${current.id}/frontmatter`);
      $('frontmatterText').value = result.frontmatter || '';
    } catch (e) {
      $('frontmatterText').value = e.message;
    }
  }

  async function deleteCurrent() {
    if (!confirm(`Delete "${current.name}" and all of its submissions? This can't be undone.`)) return;
    try {
      await api(`/api/assignments/delete/${current.id}`, { method: 'POST' });
      toast('Assignment deleted');
      closeModal($('assignmentModal'));
      current = null;
      await loadAssignments();
    } catch (e) {
      toast(e.message, false);
    }
  }

  // ---------- CSV export ----------
  function downloadCsv(filename, rows) {
    const cell = v => {
      const s = String(v ?? '');
      return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const blob = new Blob([rows.map(r => r.map(cell).join(',')).join('\n')], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  }

  function assignmentCsvRows(list) {
    const headers = ['id', 'name', 'type', 'assignmentType', 'points', 'dueDate', 'timestamp', 'contentUrl', 'description', 'aiRubric'];
    return [headers, ...list.map(a => headers.map(h => a[h]))];
  }

  function exportSubmissions() {
    if (!submissions.length) { toast('No submissions to export', false); return; }
    const rows = [['submission_id', 'submitter_id', 'submitter_name', 'content', 'comment', 'grade', 'feedback', 'is_late']];
    submissions.forEach(s => rows.push([s.id, s.submitter?.id, submitterName(s), contentText(s.content), s.comment, s.grade, s.feedback, !!s.isLate]));
    downloadCsv(`submissions_assignment_${current.id}.csv`, rows);
  }

  // ---------- test tools ----------
  function fillCheckSelect() {
    const selected = $('checkSelect').value;
    $('checkSelect').innerHTML = assignments.map(a => `<option value="${a.id}">${esc(a.name)}</option>`).join('');
    if (selected) $('checkSelect').value = selected;
  }

  async function createTest() {
    const restore = busy($('createTest'), 'Creating…');
    $('createTestStatus').textContent = '';
    try {
      const created = await api('/api/assignments/test', { method: 'POST' });
      await loadAssignments();
      $('checkSelect').value = String(created.id);
      $('createTestStatus').innerHTML = `Created <button class="g-btn" type="button" data-open-assignment="${created.id}">${esc(created.name)} (#${created.id})</button>
        Its AI rubric generates in the background.`;
    } catch (e) {
      $('createTestStatus').textContent = e.message;
    } finally {
      restore();
    }
  }

  async function checkAll() {
    const target = $('checkResults');
    const button = $('runAllChecks');
    if (button.disabled) return;
    const restore = busy(button, 'Checking…');
    target.dataset.all = 'true';
    delete target.dataset.checked;
    const problems = [];
    let done = 0;
    const total = assignments.length;
    const queue = [...assignments];
    target.innerHTML = progressHtml(`Checked 0 of ${total}`, 0, total);
    async function worker() {
      while (queue.length) {
        const a = queue.shift();
        try {
          const checks = await api(`/api/assignments/${a.id}/checks`);
          if (checks.some(c => !c.ok)) problems.push({ a, checks });
        } catch (e) {
          problems.push({ a, checks: [{ check: 'Request', ok: false, message: e.message, fix: null }] });
        }
        done++;
        target.innerHTML = progressHtml(`Checked ${done} of ${total}`, done, total);
      }
    }
    await Promise.all([1, 2, 3, 4].map(worker));
    restore();
    problems.sort((x, y) => (x.a.name || '').localeCompare(y.a.name || ''));
    // Each assignment links to its window; each problem has its Fix button.
    target.innerHTML = problems.length
      ? `<div class="mb-2"><span class="g-badge bad">${problems.length} assignment(s) with problems</span></div>
         <div style="max-height:360px;overflow:auto">${problems.map(p => `<div class="g-panel">
           <a href="?assignment=${p.a.id}" data-open-assignment="${p.a.id}"><b>${esc(p.a.name)}</b></a> <span class="g-sub">#${p.a.id}</span>
           ${p.a.contentUrl ? `<div class="g-sub mb-1">${pageLink(p.a.contentUrl)}</div>` : ''}
           ${checkRowsHtml(p.a.id, p.checks.filter(c => !c.ok))}</div>`).join('')}</div>`
      : '<span class="g-badge ok">Every assignment passed</span>';
  }

  // ---------- wiring ----------
  $('assignmentSearch').addEventListener('input', debounce(renderList, 150));
  $('filterPanel').addEventListener('input', renderList);
  $('filterToggle').addEventListener('click', () => {
    const drop = $('filterDrop');
    drop.classList.toggle('open');
    $('filterToggle').setAttribute('aria-expanded', drop.classList.contains('open'));
  });
  document.addEventListener('click', e => { if (!$('filterDrop').contains(e.target)) $('filterDrop').classList.remove('open'); });
  $('clearFilters').addEventListener('click', () => {
    document.querySelectorAll('#filterPanel input').forEach(i => { if (i.type === 'checkbox') i.checked = false; else i.value = ''; });
    renderList();
  });

  $('assignmentRows').addEventListener('click', e => {
    const row = e.target.closest('tr[data-id]');
    if (row) openAssignment(Number(row.dataset.id));
  });
  $('assignmentRows').addEventListener('keydown', e => {
    const row = e.target.closest('tr[data-id]');
    if (row && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openAssignment(Number(row.dataset.id)); }
  });

  document.querySelectorAll('.g-modal').forEach(modal => {
    modal.addEventListener('click', e => { if (e.target === modal || e.target.closest('[data-close]')) closeModal(modal); });
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') document.querySelectorAll('.g-modal.open').forEach(closeModal);
  });

  document.querySelectorAll('#assignmentModal .g-tab').forEach(t => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $('subSearch').addEventListener('input', debounce(renderSubmissions, 150));
  $('subRows').addEventListener('click', e => {
    const button = e.target.closest('button[data-sub]');
    if (!button) return;
    const row = button.closest('tr');
    if (button.dataset.sub === 'view') showSubmission(Number(row.dataset.id));
    else if (button.dataset.sub === 'review') openReview(Number(row.dataset.id));
    else gradeRow(row, button.dataset.sub, button);
  });
  $('subBack').addEventListener('click', () => { $('subDetail').style.display = 'none'; $('subList').style.display = ''; });
  $('exportSubs').addEventListener('click', exportSubmissions);

  $('editForm').addEventListener('submit', saveEdit);
  $('saveRubric').addEventListener('click', saveRubric);
  $('regenRubric').addEventListener('click', regenerateRubric);

  document.querySelectorAll('[data-more]').forEach(b => b.addEventListener('click', () => showMore(b.dataset.more)));
  $('graderSearch').addEventListener('input', e => searchGraders(e.target.value.trim()));
  $('graderResults').addEventListener('click', e => {
    const b = e.target.closest('button[data-add]');
    if (!b) return;
    graders.set(Number(b.dataset.add), { id: Number(b.dataset.add), name: b.dataset.name, uid: b.dataset.uid });
    b.remove();
    renderGraders();
  });
  $('graderList').addEventListener('click', e => {
    const b = e.target.closest('button[data-remove]');
    if (!b) return;
    graders.delete(Number(b.dataset.remove));
    renderGraders();
  });
  $('saveGraders').addEventListener('click', saveGraders);
  $('randomizeGraders').addEventListener('click', randomizeGraders);
  $('copyFrontmatter').addEventListener('click', () => {
    navigator.clipboard?.writeText($('frontmatterText').value).then(() => toast('Copied'), () => toast('Copy failed', false));
  });
  $('exportAssignment').addEventListener('click', () => downloadCsv(`assignment_${current.id}.csv`, assignmentCsvRows([current])));
  $('deleteAssignment').addEventListener('click', deleteCurrent);
  $('closeAssignment').addEventListener('click', closeCurrent);

  $('reviewOpenForm').addEventListener('submit', e => {
    e.preventDefault();
    reviewAction(e.submitter || $('reviewOpenForm').querySelector('button'),
      () => reviews.open(reviewSubmission.id, $('reviewReason').value, $('reviewOpenEvidence').value))
      .then(() => { $('reviewOpenEvidence').value = ''; });
  });
  $('reviewSaveEvidence').addEventListener('click', e =>
    reviewAction(e.currentTarget, () => reviews.saveEvidence(openReviewId, $('reviewEvidence').value)));
  $('reviewAiEvaluate').addEventListener('click', e =>
    reviewAction(e.currentTarget, () => reviews.aiEvaluation(openReviewId), 'Evaluating…'));
  $('reviewSimilarity').addEventListener('click', e =>
    reviewAction(e.currentTarget, () => reviews.similarity(openReviewId), 'Searching…'));
  document.querySelectorAll('input[name="reviewDecision"]').forEach(radio => radio.addEventListener('change', () => {
    $('reviewAdjustFields').style.display = radio.value === 'ADJUST' && radio.checked ? '' : 'none';
  }));
  $('reviewDecisionForm').addEventListener('submit', decideReview);

  $('exportAll').addEventListener('click', () => downloadCsv('assignments.csv', assignmentCsvRows(assignments)));
  $('openTestTools').addEventListener('click', () => openModal('testModal'));
  $('createTest').addEventListener('click', createTest);
  $('runCheck').addEventListener('click', () => {
    if (!$('checkSelect').value) return;
    delete $('checkResults').dataset.all;
    renderChecks($('checkResults'), $('checkSelect').value);
  });
  $('runAllChecks').addEventListener('click', checkAll);

  // Fix buttons and assignment links in check results, test tools and anywhere else.
  document.addEventListener('click', e => {
    const fix = e.target.closest('button[data-fix]');
    if (fix) { applyFix(fix); return; }
    const open = e.target.closest('[data-open-assignment]');
    if (open) {
      e.preventDefault();
      openAssignment(Number(open.dataset.openAssignment));
    }
  });

  loadAssignments().then(openFromUrl);
})();
