"use strict";
const $ = (selector) => document.querySelector(selector);
const state = { actor: "alice", users: [], orgs: [], contracts: [], inbox: [], selected: null, version: null, doc: null, versions: [], comments: [], approvals: [], busy: false, editMode: "create" };
const escape = (value) => String(value ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const names = (id) => state.users.find(u => u.id === id)?.name || id;
const date = (value) => new Date(value).toLocaleString("ko-KR", {month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit"});
const labels = { draft: "작성 중", delivered: "검토 중", approved: "승인 완료" };
const badge = (status) => `<span class="badge ${escape(status)}">${escape(labels[status])}</span>`;

async function api(path, method = "GET", body) {
  const response = await fetch(path, { method, headers: {"X-User": state.actor, ...(body ? {"Content-Type":"application/json"} : {})}, ...(body ? {body:JSON.stringify(body)} : {}) });
  const payload = await response.json();
  if (!response.ok) throw new Error(typeof payload.detail === "string" ? payload.detail : "입력값을 확인해 주세요.");
  return payload;
}
function message(text, error = false) {
  const element = $("#message"); element.textContent = text; element.className = error ? "error" : ""; element.hidden = !text;
}
async function run(work) {
  if (state.busy) return;
  state.busy = true; message(""); $("#editor-error").hidden = true;
  document.querySelectorAll("button, select").forEach(e => e.disabled = true);
  try { await work(); } catch (error) { message(error.message, true); if ($("#editor").open) { $("#editor-error").textContent = error.message; $("#editor-error").hidden = false; } }
  finally { state.busy = false; document.querySelectorAll("button, select").forEach(e => e.disabled = false); render(); }
}
async function refresh() {
  state.doc = null;
  [state.contracts, state.inbox] = await Promise.all([api("/contracts"), api("/inbox")]);
  if (!state.contracts.some(c => c.contract_id === state.selected)) { state.selected = null; state.doc = null; state.version = null; }
  if (state.selected) await loadDetail();
}
async function loadDetail() {
  state.doc = null;
  state.versions = await api(`/contracts/${state.selected}/versions`);
  if (!state.versions.some(v => v.version === state.version)) state.version = state.versions[0].version;
  [state.doc, state.comments, state.approvals] = await Promise.all([
    api(`/contracts/${state.selected}/versions/${state.version}`),
    api(`/contracts/${state.selected}/comments?version=${state.version}`),
    api(`/contracts/${state.selected}/approvals`)
  ]);
}
function render() {
  const identity = state.users.find(u => u.id === state.actor);
  $("#identity").value = state.actor;
  $("#identity-info").textContent = `${state.orgs.find(o => o.id === identity?.organization_id)?.name || ""} · ${identity?.role === "author" ? "계약 작성자" : "수신 검토자"}`;
  $("#create-contract").hidden = identity?.role !== "author";
  $("#stat-total").textContent = state.contracts.length;
  $("#stat-pending").textContent = state.contracts.filter(c => c.status === "delivered").length;
  $("#stat-approved").textContent = state.contracts.filter(c => c.status === "approved").length;
  $("#stat-inbox").textContent = state.inbox.length;
  $("#contracts").innerHTML = state.contracts.length ? state.contracts.map(c => `<button class="contract-card ${c.contract_id === state.selected ? "active" : ""}" data-contract="${escape(c.contract_id)}"><span class="card-top">${badge(c.status)}<span class="version-label">VERSION ${c.version}</span></span><span class="card-title">${escape(c.title)}</span><span class="card-meta">${escape(names(c.author))} → ${escape(names(c.recipient))}<br>${escape(date(c.created_at))}</span></button>`).join("") : '<div class="empty"><h2>아직 문서가 없습니다</h2><p>작성하거나 전달받은 계약이 여기에 표시됩니다.</p></div>';
  document.querySelectorAll("[data-contract]").forEach(button => button.onclick = () => run(async () => { state.selected = button.dataset.contract; state.version = null; state.doc = null; await loadDetail(); }));
  const doc = state.doc;
  if (!doc) { $("#detail").innerHTML = '<div class="empty"><span class="empty-icon">▤</span><h2>문서를 선택해 주세요</h2><p>계약 내용과 검토 이력을 확인할 수 있습니다.</p></div>'; return; }
  const isAuthor = doc.author === state.actor;
  $("#detail").innerHTML = `<div class="detail-heading"><div>${badge(doc.status)}<h2>${escape(doc.title)}</h2><div class="detail-meta">작성 ${escape(names(doc.author))} &nbsp; → &nbsp; 검토 ${escape(names(doc.recipient))}<br>${escape(date(doc.created_at))}</div></div><label>문서 버전<select id="version-select">${state.versions.map(v => `<option value="${v.version}" ${v.version === doc.version ? "selected" : ""}>버전 ${v.version} · ${escape(labels[v.status])}</option>`).join("")}</select></label></div><div class="actions">${isAuthor ? '<button id="new-version" class="secondary">새 버전 작성</button>' : ""}${isAuthor && doc.status === "draft" ? '<button id="deliver" class="primary">검토자에게 전달</button>' : ""}${!isAuthor && doc.status === "delivered" ? '<button id="approve" class="primary">이 버전 승인</button>' : ""}${doc.status === "approved" ? '<button id="download" class="secondary">승인본 다운로드 ↓</button>' : ""}</div>${doc.approval ? `<p class="verified">✓ ${escape(names(doc.approval.approver))} 승인 · 서명 확인됨 · ${escape(date(doc.approval.created_at))}</p>` : ""}<pre class="document-body">${escape(doc.content)}</pre><section class="review-section"><h3>검토 의견 <span class="muted">${state.comments.length}</span></h3>${state.comments.map(c => `<div class="comment"><header>${escape(names(c.author))} · ${escape(date(c.created_at))}</header><p>${escape(c.content)}</p></div>`).join("") || '<p class="muted">아직 등록된 의견이 없습니다.</p>'}${doc.status !== "draft" ? '<form id="comment-form" class="comment-form"><textarea id="comment-content" aria-label="검토 의견" placeholder="이 버전에 대한 의견을 남겨 주세요." required maxlength="4000"></textarea><button class="secondary" type="submit">의견 등록</button></form>' : '<p class="muted">검토자에게 전달하면 의견을 주고받을 수 있습니다.</p>'}<p class="muted">이 계약의 승인 이력 ${state.approvals.length}건</p></section>`;
  $("#version-select").onchange = event => run(async () => { state.version = Number(event.target.value); state.doc = null; await loadDetail(); });
  if ($("#new-version")) $("#new-version").onclick = () => openEditor("version");
  if ($("#deliver")) $("#deliver").onclick = () => run(async () => { await api(`/contracts/${state.selected}/deliveries`, "POST", {version:state.version}); await refresh(); message("검토자에게 문서를 전달했습니다."); });
  if ($("#approve")) $("#approve").onclick = () => run(async () => { await api(`/contracts/${state.selected}/approvals`, "POST", {version:state.version}); await refresh(); message("선택한 문서 버전을 승인했습니다."); });
  if ($("#download")) $("#download").onclick = () => run(async () => {
    const result = await api(`/contracts/${state.selected}/approved-copy?version=${state.version}`);
    const url = URL.createObjectURL(new Blob([JSON.stringify(result,null,2)], {type:"application/json;charset=utf-8"}));
    const link = document.createElement("a"); link.href = url; link.download = `contract-${state.selected}-v${state.version}.json`; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000); message("검증된 승인본을 내려받았습니다.");
  });
  if ($("#comment-form")) $("#comment-form").onsubmit = event => { event.preventDefault(); const content = $("#comment-content").value; run(async () => { await api(`/contracts/${state.selected}/comments`, "POST", {version:state.version,content}); await loadDetail(); message("검토 의견을 등록했습니다."); }); };
}
function openEditor(mode) {
  state.editMode = mode; $("#editor-error").hidden = true;
  $("#editor-title").textContent = mode === "create" ? "새 계약 작성" : "새 버전 작성";
  $("#save-editor").textContent = mode === "create" ? "초안 저장" : "새 버전 저장";
  $("#recipient-label").hidden = mode !== "create";
  $("#input-title").value = mode === "create" ? "" : state.doc.title;
  $("#input-content").value = mode === "create" ? "" : state.doc.content;
  $("#editor").showModal(); $("#input-title").focus();
}
$("#create-contract").onclick = () => openEditor("create");
$("#close-editor").onclick = $("#cancel-editor").onclick = () => $("#editor").close();
$("#editor-form").onsubmit = event => {
  event.preventDefault();
  const body = {title:$("#input-title").value,content:$("#input-content").value};
  if (state.editMode === "create") body.recipient = $("#input-recipient").value;
  const path = state.editMode === "create" ? "/contracts" : `/contracts/${state.selected}/versions`;
  run(async () => { const result = await api(path,"POST",body); $("#editor").close(); state.selected = result.contract_id; state.version = result.version; await refresh(); message("문서를 저장했습니다. 내용을 확인한 뒤 검토자에게 전달해 주세요."); });
};
$("#identity").onchange = event => { const actor = event.target.value; run(async () => { state.actor = actor; state.selected = null; state.doc = null; state.contracts = []; state.inbox = []; await refresh(); }); };
$("#refresh").onclick = () => run(refresh);
run(async () => {
  [state.users, state.orgs] = await Promise.all([api("/users"), api("/organizations")]);
  $("#identity").innerHTML = state.users.map(u => `<option value="${escape(u.id)}">${escape(u.name)} (${escape(u.id)})</option>`).join("");
  $("#input-recipient").innerHTML = state.users.filter(u => u.role === "reviewer").map(u => `<option value="${escape(u.id)}">${escape(u.name)} · ${escape(state.orgs.find(o=>o.id === u.organization_id)?.name)}</option>`).join("");
  await refresh();
});
