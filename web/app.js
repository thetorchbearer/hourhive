"use strict";

const API = window.HOURHIVE_API || "/api";
const app = document.getElementById("app");
const state = {
  token: localStorage.getItem("hh_token"),
  user: null,
  q: "",
  cat: "",
  sort: "",
  pending: 0,
  unread: 0,
  book: null,
  report: null,
  slots: [],
  reqPage: 0,
  adminTab: "overview",
  adminPage: 0,
  adminQ: "",
  maxMin: "",
  minRating: "",
  browsePage: 0,
  editId: null,
  chatId: null,
  chatTimer: null,
  authMode: "register",
  reviewId: null,
};

const $ = (sel, root = document) => root.querySelector(sel);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const dur = (m) => {
  const a = Math.abs(m);
  const h = Math.floor(a / 60);
  const r = a % 60;
  return (m < 0 ? "-" : "") + [h && h + "h", (r || !h) && r + "m"].filter(Boolean).join(" ");
};
const stars = (r) => (r ? `<span class="stars">${"★".repeat(Math.round(r))}</span> ${r.toFixed(1)}` : `<span class="muted">new</span>`);
const when = (iso) => new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), 3200);
}

/* ---------- API with cold-start friendliness (Render free tier sleeps) ---------- */
async function api(path, { method = "GET", body, headers: extraHeaders } = {}) {
  const headers = { "Content-Type": "application/json", ...(extraHeaders || {}) };
  if (state.token) headers.Authorization = "Bearer " + state.token;
  const slow = setTimeout(() => ($("#banner").hidden = false), 2500);
  try {
    for (let attempt = 0; ; attempt++) {
      let res;
      try {
        res = await fetch(API + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
      } catch (e) {
        if (attempt < 5) { await sleep(4000); continue; }
        throw new Error("Can't reach the server. Is the API URL in config.js correct?");
      }
      if ([502, 503, 504].includes(res.status) && attempt < 5) { await sleep(4000); continue; }
      const data = res.status === 204 ? null : await res.json().catch(() => null);
      if (!res.ok) {
        if (res.status === 401 && state.token) signOut(true);
        throw new Error((data && data.error) || "Request failed (" + res.status + ")");
      }
      return data;
    }
  } finally {
    clearTimeout(slow);
    $("#banner").hidden = true;
  }
}

/* ---------- auth ---------- */
function setSession(res) {
  state.token = res.token;
  state.user = res.user;
  localStorage.setItem("hh_token", res.token);
  renderAccount();
}

function signOut(silent) {
  state.token = null;
  state.user = null;
  localStorage.removeItem("hh_token");
  renderAccount();
  if (!silent) { toast("Signed out"); route(); }
}

async function loadUser() {
  if (!state.token) return;
  try {
    state.user = await api("/me");
  } catch (e) {
    state.user = null;
  }
  renderAccount();
  refreshBadge();
}

async function refreshBadge() {
  if (!state.user) { state.pending = 0; state.unread = 0; return renderAccount(); }
  try {
    const [sum, un] = await Promise.all([api("/me/summary"), api("/notifications/unread-count")]);
    state.pending = sum.pendingRequests;
    state.unread = un.unread;
  } catch (e) {
    state.pending = 0;
    state.unread = 0;
  }
  renderAccount();
}

function renderAccount() {
  const box = $("#account");
  const badge = $("#badge");
  badge.textContent = state.pending;
  badge.hidden = !(state.user && state.pending);
  if (state.user) {
    const staff = ["MODERATOR", "ADMIN"].includes(state.user.role);
    box.innerHTML = `<a class="plain bell" href="#/notifications" title="Notifications">🔔${state.unread ? `<span class="badge">${state.unread}</span>` : ""}</a>
      <span class="chip" title="Your time balance">⏳ ${dur(state.user.balanceMinutes)}</span>
      ${staff ? `<a class="plain" href="#/admin">Admin</a>` : ""}
      <a class="plain" href="#/me" title="Edit your profile">${esc(state.user.displayName)}</a>
      <button class="btn ghost" data-action="signout">Sign out</button>`;
  } else {
    box.innerHTML = `<button class="btn primary" data-action="login">Sign in / Join</button>`;
  }
}

function openAuth(mode) {
  state.authMode = mode || "register";
  const reg = state.authMode === "register";
  $("#authTitle").textContent = reg ? "Join the hive" : "Welcome back";
  $("#authHint").textContent = reg ? "New members start with 2 free hours." : "Sign in to continue.";
  $("#nameRow").hidden = !reg;
  $("#authSubmit").textContent = reg ? "Create account" : "Sign in";
  $("#authSwitch").textContent = reg ? "I already have an account" : "Create a new account";
  $("#authErr").hidden = true;
  $("#authDlg").showModal();
}

$("#authSwitch").addEventListener("click", () => openAuth(state.authMode === "register" ? "login" : "register"));
$("#authCancel").addEventListener("click", () => $("#authDlg").close());
$("#authForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const reg = state.authMode === "register";
  const btn = $("#authSubmit");
  btn.disabled = true;
  try {
    const res = await api(reg ? "/auth/register" : "/auth/login", {
      method: "POST",
      body: reg
        ? { email: f.get("email"), password: f.get("password"), displayName: f.get("displayName") || String(f.get("email")).split("@")[0] }
        : { email: f.get("email"), password: f.get("password") },
    });
    setSession(res);
    $("#authDlg").close();
    e.target.reset();
    toast(reg ? "Welcome! 2 free hours added 🎉" : "Signed in");
    route();
  } catch (err) {
    const el = $("#authErr");
    el.textContent = err.message;
    el.hidden = false;
  } finally {
    btn.disabled = false;
  }
});

/* ---------- reviews ---------- */
$("#reviewCancel").addEventListener("click", () => $("#reviewDlg").close());
$("#reviewForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    await api(`/bookings/${state.reviewId}/review`, {
      method: "POST",
      body: { rating: Number(f.get("rating")), comment: f.get("comment") || null },
    });
    $("#reviewDlg").close();
    e.target.reset();
    toast("Thanks for the review!");
    route();
  } catch (err) {
    toast(err.message);
  }
});

/* ---------- views ---------- */
function loading() {
  app.innerHTML = `<p class="empty">Loading…</p>`;
}

async function viewBrowse() {
  loading();
  const qs = new URLSearchParams();
  if (state.q) qs.set("q", state.q);
  if (state.cat) qs.set("category", state.cat);
  if (state.sort) qs.set("sort", state.sort);
  if (state.maxMin) qs.set("maxMinutes", state.maxMin);
  if (state.minRating) qs.set("minRating", state.minRating);
  qs.set("page", state.browsePage);
  qs.set("size", 12);
  const [cats, page, stats] = await Promise.all([
    api("/categories"),
    api("/listings?" + qs),
    api("/stats").catch(() => null),
  ]);
  const listings = page.items;
  app.innerHTML = `
    <section class="hero">
      <h1>Trade skills for hours, not money.</h1>
      <p>Teach something you're good at, earn time credits, and spend them learning from a neighbour. One hour given = one hour earned.</p>
      <button class="btn" data-action="offer-cta">Offer a skill</button>
      ${stats ? `<div class="stats"><span><b>${stats.members}</b> members</span><span><b>${stats.activeListings}</b> skills on offer</span><span><b>${stats.completedSessions}</b> sessions done</span><span><b>${dur(stats.minutesExchanged)}</b> exchanged</span></div>` : ""}
    </section>
    <div class="toolbar"><input id="q" type="search" placeholder="Search skills… guitar, resume review, Spanish" value="${esc(state.q)}">
      <select id="sort" aria-label="Sort">
        <option value="" ${state.sort === "" ? "selected" : ""}>Newest</option>
        <option value="rating" ${state.sort === "rating" ? "selected" : ""}>Top rated</option>
        <option value="shortest" ${state.sort === "shortest" ? "selected" : ""}>Shortest first</option>
      </select>
      <select id="maxMin" aria-label="Max length">
        <option value="" ${state.maxMin === "" ? "selected" : ""}>Any length</option>
        <option value="30" ${state.maxMin === "30" ? "selected" : ""}>Up to 30 min</option>
        <option value="60" ${state.maxMin === "60" ? "selected" : ""}>Up to 1 hour</option>
        <option value="120" ${state.maxMin === "120" ? "selected" : ""}>Up to 2 hours</option>
      </select>
      <select id="minRating" aria-label="Min rating">
        <option value="" ${state.minRating === "" ? "selected" : ""}>Any rating</option>
        <option value="4" ${state.minRating === "4" ? "selected" : ""}>4★ and up</option>
        <option value="4.5" ${state.minRating === "4.5" ? "selected" : ""}>4.5★ and up</option>
      </select></div>
    <div class="chips">
      <button data-cat="" class="${state.cat === "" ? "on" : ""}">All</button>
      ${cats.map((c) => `<button data-cat="${esc(c.category)}" class="${state.cat === c.category ? "on" : ""}">${esc(c.category)} · ${c.listings}</button>`).join("")}
    </div>
    ${listings.length ? `<div class="grid">${listings.map(listingCard).join("")}</div>${pager(page.page, page.totalPages, "browsepage")}`
      : `<p class="empty">No skills here yet. Try a different filter, or be the first to offer one!</p>`}`;
  const input = $("#q");
  let timer;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => { state.q = input.value.trim(); state.browsePage = 0; viewBrowse().catch(showError); }, 350);
  });
  $("#sort").addEventListener("change", (e) => { state.sort = e.target.value; state.browsePage = 0; viewBrowse().catch(showError); });
  $("#maxMin").addEventListener("change", (e) => { state.maxMin = e.target.value; state.browsePage = 0; viewBrowse().catch(showError); });
  $("#minRating").addEventListener("change", (e) => { state.minRating = e.target.value; state.browsePage = 0; viewBrowse().catch(showError); });
  if (state.q) {
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  }
}

function listingCard(l) {
  const mine = state.user && state.user.id === l.ownerId;
  return `<article class="card">
      <span class="tag">${esc(l.category)}</span>
      <h3>${esc(l.title)}</h3>
      <p class="muted">${esc(l.description)}</p>
      <div class="muted">by <a class="plain" href="#/u/${l.ownerId}">${esc(l.ownerName)}</a> · ${stars(l.ownerRating)}${l.ownerReviews ? ` (${l.ownerReviews})` : ""}</div>
      <div class="meta">
        <b>⏳ ${dur(l.minutes)}</b>
        ${mine ? `<span class="muted">Your listing</span>` : `<button class="btn primary" data-action="book" data-id="${l.id}" data-title="${esc(l.title)}" data-min="${l.minutes}" data-owner="${l.ownerId}">Book</button>`}
      </div>
      ${mine ? "" : `<button class="btn ghost small" style="align-self:flex-start" data-action="report" data-type="LISTING" data-id="${l.id}">⚑ Report</button>`}
    </article>`;
}

function viewOffer() {
  if (!state.user) { app.innerHTML = `<p class="empty">Sign in to offer a skill.</p>`; openAuth("register"); return; }
  app.innerHTML = `<h1>Offer a skill</h1>
    <p class="muted">Every session you complete earns you the listed time.</p>
    <form id="offerForm" class="card" style="max-width:560px">
      <label>Title <input name="title" maxlength="100" required placeholder="Beginner guitar chords"></label>
      <label>Category <input name="category" maxlength="40" required list="catlist" placeholder="Music">
        <datalist id="catlist"><option>Music</option><option>Languages</option><option>Tech</option><option>Cooking</option><option>Fitness</option><option>Crafts</option><option>Career</option><option>Home fixes</option></datalist>
      </label>
      <label>What will you teach or help with? <textarea name="description" maxlength="1000" rows="4" required></textarea></label>
      <label>Session length
        <select name="minutes"><option value="30">30 minutes</option><option value="60" selected>1 hour</option><option value="90">1.5 hours</option><option value="120">2 hours</option></select>
      </label>
      <div class="row"><button class="btn primary" type="submit" id="offerSubmit">Publish</button><button class="btn ghost" type="button" id="offerCancel" hidden>Cancel edit</button></div>
    </form>
    <h2 style="margin-top:24px">Your listings</h2><div id="mine" class="list"></div>`;
  $("#offerForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const body = { title: f.get("title"), category: f.get("category"), description: f.get("description"), minutes: Number(f.get("minutes")) };
    try {
      if (state.editId) {
        await api("/listings/" + state.editId, { method: "PUT", body });
        toast("Listing updated");
      } else {
        await api("/listings", { method: "POST", body });
        toast("Listing published 🐝");
      }
      stopEdit();
      loadMine();
    } catch (err) { toast(err.message); }
  });
  $("#offerCancel").addEventListener("click", stopEdit);
  state.editId = null;
  loadMine();
}

function stopEdit() {
  state.editId = null;
  const form = $("#offerForm");
  if (!form) return;
  form.reset();
  $("#offerSubmit").textContent = "Publish";
  $("#offerCancel").hidden = true;
}

function startEdit(l) {
  const form = $("#offerForm");
  if (!form) return;
  state.editId = l.id;
  form.elements.title.value = l.title;
  form.elements.category.value = l.category;
  form.elements.description.value = l.description;
  form.elements.minutes.value = String(l.minutes);
  $("#offerSubmit").textContent = "Save changes";
  $("#offerCancel").hidden = false;
  form.scrollIntoView({ behavior: "smooth", block: "start" });
}

let myListings = [];

async function loadMine() {
  const box = $("#mine");
  if (!box) return;
  const mine = await api("/listings/mine");
  myListings = mine;
  box.innerHTML = mine.length
    ? mine.map((l) => `<div class="item"><div><b>${esc(l.title)}</b> <span class="tag">${esc(l.category)}</span><br><span class="muted">${dur(l.minutes)} · ${l.active ? "live" : "removed"}</span></div>
        ${l.active ? `<div class="row"><button class="btn" data-action="edit" data-id="${l.id}">Edit</button><button class="btn danger" data-action="remove" data-id="${l.id}">Remove</button></div>` : ""}</div>`).join("")
    : `<p class="muted">Nothing yet.</p>`;
}

async function viewBookings() {
  if (!state.user) { app.innerHTML = `<p class="empty">Sign in to see your sessions.</p>`; openAuth("login"); return; }
  loading();
  const list = await api("/bookings/mine");
  const me = state.user.id;
  app.innerHTML = `<h1>Your sessions</h1>` + (list.length ? `<div class="list">${list.map((b) => bookingRow(b, me)).join("")}</div>`
    : `<p class="empty">No sessions yet. Browse skills and book your first one!</p>`);
}

function bookingRow(b, me) {
  const learner = b.learnerId === me;
  const other = learner ? `with <a class="plain" href="#/u/${b.providerId}">${esc(b.providerName)}</a>` : `for <b>${esc(b.learnerName)}</b>`;
  const acts = [];
  if (!learner && b.status === "REQUESTED") {
    acts.push(`<button class="btn good" data-action="accept" data-id="${b.id}">Accept</button>`);
    acts.push(`<button class="btn danger" data-action="decline" data-id="${b.id}">Decline</button>`);
  }
  if (learner && b.status === "ACCEPTED") acts.push(`<button class="btn good" data-action="complete" data-id="${b.id}">Mark completed</button>`);
  if (["REQUESTED", "ACCEPTED"].includes(b.status) && (learner || b.status === "ACCEPTED")) acts.push(`<button class="btn danger" data-action="cancel" data-id="${b.id}">Cancel</button>`);
  if (learner && b.status === "COMPLETED" && !b.reviewed) acts.push(`<button class="btn" data-action="review" data-id="${b.id}">Leave review</button>`);
  if (["REQUESTED", "ACCEPTED"].includes(b.status)) acts.push(`<button class="btn" data-action="reschedule" data-id="${b.id}" data-provider="${b.providerId}">🗓 Reschedule</button>`);
  acts.push(`<button class="btn" data-action="chat" data-id="${b.id}" data-title="${esc(b.listingTitle)}">💬 Messages</button>`);
  return `<div class="item"><div>
      <span class="status s-${b.status}">${b.status}</span> <b>${esc(b.listingTitle)}</b> ${other} · ${dur(b.minutes)}
      ${b.note ? `<br><span class="muted">“${esc(b.note)}”</span>` : ""}
      ${b.scheduledAt ? `<br>🗓 <b>${when(b.scheduledAt)}</b>${b.rescheduleCount ? ` <span class="muted">(rescheduled ${b.rescheduleCount}×)</span>` : ""}` : `<br><span class="muted">No time set yet. Use Reschedule or Messages to agree one.</span>`}
      <br><span class="muted">${when(b.updatedAt)}</span></div>
      <div class="row">${acts.join("")}</div></div>`;
}

async function viewWallet() {
  if (!state.user) { app.innerHTML = `<p class="empty">Sign in to see your wallet.</p>`; openAuth("login"); return; }
  loading();
  const [me, ledger] = await Promise.all([api("/me"), api("/me/ledger")]);
  state.user = me;
  renderAccount();
  app.innerHTML = `<h1>Your wallet</h1>
    <div class="card"><span class="muted">Spendable balance</span><div class="big">⏳ ${dur(me.balanceMinutes)}</div>
    <span class="muted">Minutes held for open requests are already deducted.</span></div>
    <h2 style="margin-top:22px">History</h2>
    <div class="list">${ledger.map((e) => `<div class="item"><div><b>${esc(kindLabel(e.kind))}</b><br><span class="muted">${esc(e.note || "")} · ${when(e.createdAt)}</span></div>
      <span class="${e.deltaMinutes >= 0 ? "pos" : "neg"}">${e.deltaMinutes >= 0 ? "+" : ""}${dur(e.deltaMinutes)}</span></div>`).join("")}</div>`;
}

const kindLabel = (k) => ({ SIGNUP_BONUS: "Welcome bonus", ESCROW: "Held for session", REFUND: "Refund", EARNED: "Earned" }[k] || k);

async function viewLeaders() {
  loading();
  const rows = await api("/leaderboard");
  app.innerHTML = `<h1>Top helpers</h1><p class="muted">Ranked by hours given in completed sessions.</p>` +
    (rows.length ? `<div class="list">${rows.map((r, i) => `<div class="item"><div class="row"><span class="rank">#${i + 1}</span><b>${esc(r.displayName)}</b></div>
      <div class="muted">${r.sessions} sessions · ${stars(r.rating)}</div><b>⏳ ${dur(r.minutesGiven)}</b></div>`).join("")}</div>`
      : `<p class="empty">No completed sessions yet. Be the first on the board!</p>`);
}

async function viewProfile(id) {
  loading();
  const [p, slots] = await Promise.all([api("/users/" + id), api(`/users/${id}/availability`).catch(() => [])]);
  const mine = state.user && state.user.id === p.id;
  app.innerHTML = `<div class="card">
      <div class="profile-head">
        <div class="avatar">${esc(p.displayName.charAt(0).toUpperCase())}</div>
        <div><h1 style="margin:0">${esc(p.displayName)}</h1>
          <div class="muted">${stars(p.rating)}${p.reviewCount ? ` (${p.reviewCount} reviews)` : ""} · member since ${new Date(p.memberSince).toLocaleDateString(undefined, { month: "short", year: "numeric" })}</div>
          <div class="muted"><b>${dur(p.minutesGiven)}</b> given across <b>${p.sessions}</b> sessions</div></div>
        ${mine ? `<a class="btn" href="#/me" style="margin-left:auto;text-decoration:none">Edit profile</a>` : ""}
      </div>
      <p>${p.bio ? esc(p.bio) : `<span class="muted">No bio yet.</span>`}</p>
      <div class="muted">${slots.length ? "Available (UTC): " + slots.map(slotText).join(" · ") : "Flexible schedule"}</div>
      ${mine || !state.user ? "" : `<button class="btn ghost small" style="align-self:flex-start" data-action="report" data-type="USER" data-id="${p.id}">⚑ Report member</button>`}
    </div>
    <h2 style="margin-top:22px">Skills offered</h2>
    ${p.listings.length ? `<div class="grid">${p.listings.map(listingCard).join("")}</div>` : `<p class="muted">No active listings.</p>`}
    <h2 style="margin-top:22px">Reviews</h2>
    ${p.reviews.length ? p.reviews.map((r) => `<div class="review"><span class="stars">${"★".repeat(r.rating)}</span> <b>${esc(r.reviewerName)}</b> <span class="muted">on “${esc(r.listingTitle)}” · ${when(r.createdAt)}</span>${r.comment ? `<br>${esc(r.comment)}` : ""}</div>`).join("") : `<p class="muted">No reviews yet.</p>`}`;
}

async function viewMe() {
  if (!state.user) { app.innerHTML = `<p class="empty">Sign in to edit your profile.</p>`; openAuth("login"); return; }
  state.slots = (await api(`/users/${state.user.id}/availability`).catch(() => [])).map((x) => ({ dayOfWeek: x.dayOfWeek, startMinute: x.startMinute, endMinute: x.endMinute }));
  app.innerHTML = `<h1>Your profile</h1>
    <p class="muted">This is what other members see before they book you. <a class="plain" href="#/u/${state.user.id}">View public page</a></p>
    <form id="meForm" class="card" style="max-width:560px">
      <label>Display name <input name="displayName" maxlength="60" required value="${esc(state.user.displayName)}"></label>
      <label>Bio <textarea name="bio" maxlength="500" rows="5" placeholder="What are you good at? What do you want to learn?">${esc(state.user.bio || "")}</textarea></label>
      <div class="row"><button class="btn primary" type="submit">Save profile</button></div>
    </form>
    <div class="card" style="max-width:560px;margin-top:18px">
      <h3 style="margin:0">Weekly availability</h3>
      <p class="muted">Times are UTC. Leave it empty if you're flexible; otherwise bookings must fall inside a slot.</p>
      <div id="slotList" class="list"></div>
      <div class="row">
        <select id="slotDay" style="width:auto">${DAYS.slice(1).map((d, i) => `<option value="${i + 1}">${d}</option>`).join("")}</select>
        <input type="time" id="slotStart" value="09:00" style="width:auto"><input type="time" id="slotEnd" value="12:00" style="width:auto">
        <button class="btn" type="button" id="slotAdd">Add slot</button>
      </div>
      <div class="row"><button class="btn primary" type="button" id="slotSave">Save availability</button></div>
    </div>
    ${state.user.role === "USER" ? `<form id="bootForm" class="card" style="max-width:560px;margin-top:18px">
      <h3 style="margin:0">Admin access</h3>
      <p class="muted">Only works while the platform has no admin yet.</p>
      <label>Bootstrap secret <input type="password" name="secret" autocomplete="off" required></label>
      <div class="row"><button class="btn" type="submit">Claim admin</button></div>
    </form>` : `<p class="muted" style="margin-top:18px">Your role: <b>${esc(state.user.role)}</b></p>`}`;
  renderSlots();
  $("#meForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try {
      state.user = await api("/me", { method: "PUT", body: { displayName: f.get("displayName"), bio: f.get("bio") || null } });
      renderAccount();
      toast("Profile saved");
    } catch (err) { toast(err.message); }
  });
  const toMin = (v) => { const [h, m] = v.split(":").map(Number); return h * 60 + m; };
  $("#slotAdd").addEventListener("click", () => {
    const start = toMin($("#slotStart").value), end = toMin($("#slotEnd").value);
    if (!(end > start)) return toast("End time must be after start time");
    state.slots.push({ dayOfWeek: Number($("#slotDay").value), startMinute: start, endMinute: end });
    state.slots.sort((x, y) => x.dayOfWeek - y.dayOfWeek || x.startMinute - y.startMinute);
    renderSlots();
  });
  $("#slotSave").addEventListener("click", async () => {
    try { await api("/me/availability", { method: "PUT", body: state.slots }); toast("Availability saved"); }
    catch (err) { toast(err.message); }
  });
  const boot = $("#bootForm");
  if (boot) boot.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await api("/admin/bootstrap", { method: "POST", body: { secret: new FormData(e.target).get("secret") } });
      await loadUser();
      toast("You are now an admin");
      viewMe();
    } catch (err) { toast(err.message); }
  });
}

const DAYS = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const hm = (m) => String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");
const slotText = (x) => `${DAYS[x.dayOfWeek]} ${hm(x.startMinute)}–${hm(x.endMinute)}`;

function renderSlots() {
  const box = $("#slotList");
  if (!box) return;
  box.innerHTML = state.slots.length
    ? state.slots.map((x, i) => `<div class="item"><span>${slotText(x)} UTC</span><button class="btn danger small" data-action="slotrm" data-i="${i}">Remove</button></div>`).join("")
    : `<p class="muted">No slots. You're flexible.</p>`;
}

/* ---------- notifications ---------- */
async function viewNotifications() {
  if (!state.user) { app.innerHTML = `<p class="empty">Sign in to see notifications.</p>`; openAuth("login"); return; }
  loading();
  const r = await api("/notifications?size=50");
  app.innerHTML = `<div class="row" style="justify-content:space-between"><h1>Notifications</h1><button class="btn" data-action="readall">Mark all read</button></div>` +
    (r.items.length ? `<div class="list">${r.items.map((n) => `<a class="item notif ${n.read ? "" : "unread"}" href="${esc(n.link || "#/notifications")}" data-action="readone" data-id="${n.id}"><div>${esc(n.message)}<br><span class="muted">${when(n.createdAt)}</span></div>${n.read ? "" : `<span class="dot"></span>`}</a>`).join("")}</div>`
      : `<p class="empty">Nothing yet. You'll be notified about bookings, messages, reviews and matching requests.</p>`);
}

/* ---------- skill requests board ---------- */
const pager = (p, total, act) => total > 1
  ? `<div class="row" style="justify-content:center;margin:14px 0"><button class="btn" data-action="${act}" data-dir="-1" ${p <= 0 ? "disabled" : ""}>‹ Prev</button><span class="muted">Page ${p + 1} of ${total}</span><button class="btn" data-action="${act}" data-dir="1" ${p >= total - 1 ? "disabled" : ""}>Next ›</button></div>` : "";

async function viewRequests() {
  loading();
  const [r, mine] = await Promise.all([
    api(`/requests?page=${state.reqPage}&size=8`),
    state.user ? api("/requests/mine") : Promise.resolve([]),
  ]);
  const card = (q, own) => `<article class="card">
      <div class="row" style="justify-content:space-between"><span class="tag">${esc(q.category)}</span><span class="muted">${when(q.createdAt)}</span></div>
      <h3>${esc(q.title)}</h3><p class="muted">${esc(q.description)}</p>
      <div class="muted">asked by <a class="plain" href="#/u/${q.requesterId}">${esc(q.requesterName)}</a> · ⏳ ${dur(q.minutes)}${own ? ` · <b>${esc(q.status)}</b>` : ""}</div>
      <div class="row">
        ${state.user ? `<button class="btn primary small" data-action="matches" data-id="${q.id}">Find helpers</button>` : ""}
        ${own && q.status === "OPEN" ? `<button class="btn danger small" data-action="closereq" data-id="${q.id}">Close</button>` : ""}
      </div>
      <div id="m-${q.id}"></div>
    </article>`;
  app.innerHTML = `<h1>Skill requests</h1>
    <p class="muted">Need help with something? Post it here. Members whose skills match get notified.</p>
    ${state.user ? `<form id="reqForm" class="card" style="max-width:560px">
      <label>What do you need? <input name="title" maxlength="100" required placeholder="Help preparing for a system design interview"></label>
      <label>Category <input name="category" maxlength="40" required list="catlist2" placeholder="Career">
        <datalist id="catlist2"><option>Music</option><option>Languages</option><option>Tech</option><option>Cooking</option><option>Fitness</option><option>Crafts</option><option>Career</option><option>Home fixes</option></datalist></label>
      <label>Details <textarea name="description" maxlength="1000" rows="3" required></textarea></label>
      <label>Session length <select name="minutes"><option value="30">30 minutes</option><option value="60" selected>1 hour</option><option value="90">1.5 hours</option><option value="120">2 hours</option></select></label>
      <div class="row"><button class="btn primary" type="submit">Post request</button></div>
    </form>` : `<p class="muted"><button class="btn primary" data-action="login">Sign in</button> to post a request.</p>`}
    ${mine.length ? `<h2 style="margin-top:22px">Your requests</h2><div class="grid">${mine.map((q) => card(q, true)).join("")}</div>` : ""}
    <h2 style="margin-top:22px">Open requests</h2>
    ${r.items.length ? `<div class="grid">${r.items.map((q) => card(q, state.user && q.requesterId === state.user.id)).join("")}</div>${pager(r.page, r.totalPages, "reqpage")}` : `<p class="empty">No open requests right now.</p>`}`;
  const form = $("#reqForm");
  if (form) form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try {
      await api("/requests", { method: "POST", body: { title: f.get("title"), category: f.get("category"), description: f.get("description"), minutes: Number(f.get("minutes")) } });
      toast("Posted. Matching helpers were notified 🐝");
      state.reqPage = 0;
      viewRequests();
    } catch (err) { toast(err.message); }
  });
}

async function showMatches(id, el) {
  const box = $("#m-" + id);
  box.innerHTML = `<p class="muted">Finding helpers…</p>`;
  const list = await api(`/requests/${id}/matches`);
  box.innerHTML = list.length
    ? `<h4 style="margin:8px 0 0">Suggested helpers</h4>` + list.map((m) => `<div class="match"><div><b>${esc(m.title)}</b> · ${dur(m.minutes)}<br>
        <a class="plain" href="#/u/${m.ownerId}">${esc(m.ownerName)}</a> ${stars(m.ownerRating)} <span class="muted">· ${esc(m.reason)}</span></div>
        ${state.user && state.user.id !== m.ownerId ? `<button class="btn primary small" data-action="book" data-id="${m.listingId}" data-title="${esc(m.title)}" data-min="${m.minutes}" data-owner="${m.ownerId}">Book</button>` : ""}</div>`).join("")
    : `<p class="muted">No matching helpers yet. They'll see your request as they browse.</p>`;
}

/* ---------- admin dashboard ---------- */
async function viewAdmin() {
  if (!state.user || !["MODERATOR", "ADMIN"].includes(state.user.role)) { app.innerHTML = `<p class="empty">Staff only.</p>`; return; }
  const isAdmin = state.user.role === "ADMIN";
  const tabs = [["overview", "Overview"], ["reports", "Reports"], ...(isAdmin ? [["users", "Users"], ["audit", "Audit log"], ["ledger", "Ledger"]] : [])];
  if (!tabs.some((t) => t[0] === state.adminTab)) state.adminTab = "overview";
  loading();
  let body = "";
  if (state.adminTab === "overview") {
    const o = await api("/admin/overview");
    const num = (label, v) => `<div class="card"><b>${v}</b><span class="muted">${label}</span></div>`;
    body = `<div class="numbers">${num("members", o.users)}${num("suspended", o.suspendedUsers)}${num("open reports", o.openReports)}${num("active listings", o.activeListings)}${num("open requests", o.openRequests)}${num("hours exchanged", (o.completedMinutes / 60).toFixed(1))}</div>
      <h2 style="margin-top:20px">Bookings by status</h2><div class="numbers">${Object.entries(o.bookingsByStatus).map(([k, v]) => num(k.toLowerCase(), v)).join("") || `<p class="muted">None yet.</p>`}</div>`;
  } else if (state.adminTab === "reports") {
    const r = await api("/admin/reports?status=OPEN&size=20");
    body = r.items.length ? `<div class="list">${r.items.map((x) => `<div class="item"><div><b>${x.targetType}</b> ${x.targetType === "USER" ? `<a class="plain" href="#/u/${x.targetId}">${esc(x.targetLabel || "member")}</a>` : esc(x.targetLabel || "(removed)")} · ${esc(x.reason)}<br>
        <span class="muted">${esc(x.details || "")} · reported by ${esc(x.reporterName)} · ${when(x.createdAt)}</span></div>
        <div class="row">${x.targetType === "LISTING" ? `<button class="btn danger small" data-action="resolve" data-id="${x.id}" data-act="REMOVE_LISTING">Remove listing</button>` : `<button class="btn danger small" data-action="resolve" data-id="${x.id}" data-act="SUSPEND_USER">Suspend user</button>`}
        <button class="btn small" data-action="resolve" data-id="${x.id}" data-act="DISMISS">Dismiss</button></div></div>`).join("")}</div>` : `<p class="empty">No open reports 🎉</p>`;
  } else if (state.adminTab === "users") {
    const r = await api(`/admin/users?q=${encodeURIComponent(state.adminQ)}&page=${state.adminPage}&size=15`);
    body = `<form id="adminSearch" class="toolbar"><input name="q" placeholder="Search name or email" value="${esc(state.adminQ)}"><button class="btn" type="submit">Search</button></form>
      <div class="list">${r.items.map((u) => `<div class="item"><div><b>${esc(u.displayName)}</b> <span class="muted">${esc(u.email)}</span>${u.disabled ? ` <span class="status s-CANCELLED">SUSPENDED</span>` : ""}</div>
        <div class="row"><select data-role-user="${u.id}" ${u.id === state.user.id ? "disabled" : ""} style="width:auto">${["USER", "MODERATOR", "ADMIN"].map((ro) => `<option ${ro === u.role ? "selected" : ""}>${ro}</option>`).join("")}</select>
        ${u.id === state.user.id ? "" : `<button class="btn ${u.disabled ? "" : "danger"} small" data-action="suspend" data-id="${u.id}" data-value="${!u.disabled}">${u.disabled ? "Reinstate" : "Suspend"}</button>`}</div></div>`).join("")}</div>${pager(r.page, r.totalPages, "adminpage")}`;
  } else if (state.adminTab === "audit") {
    const r = await api(`/admin/audit?page=${state.adminPage}&size=30`);
    body = `<div class="list">${r.items.map((a) => `<div class="item"><div><b>${esc(a.action)}</b> <span class="muted">${esc(a.entityType || "")} ${a.entityId ?? ""} ${esc(a.detail || "")}</span><br><span class="muted">by ${esc(a.actorName || "system")} · ${when(a.createdAt)}</span></div></div>`).join("") || `<p class="muted">Empty.</p>`}</div>${pager(r.page, r.totalPages, "adminpage")}`;
  } else {
    const l = await api("/admin/ledger/verify");
    body = `<div class="card"><b>${l.ok ? "✅ Ledger is consistent" : "⚠️ Ledger problems found"}</b>
        <span class="muted">${l.bookingsChecked} bookings checked</span></div>
      ${l.violations.length ? `<h2 style="margin-top:16px">Violations</h2><div class="list">${l.violations.map((v) => `<div class="item"><span>Booking #${v.bookingId} (${v.status})</span><span class="muted">${esc(v.problem)}</span></div>`).join("")}</div>` : ""}
      ${l.negativeBalanceUsers.length ? `<h2 style="margin-top:16px">Negative balances</h2><p class="muted">User IDs: ${l.negativeBalanceUsers.join(", ")}</p>` : ""}`;
  }
  app.innerHTML = `<h1>Admin</h1><div class="chips">${tabs.map((t) => `<button data-action="atab" data-tab="${t[0]}" class="${state.adminTab === t[0] ? "on" : ""}">${t[1]}</button>`).join("")}</div>${body}`;
  const search = $("#adminSearch");
  if (search) search.addEventListener("submit", (e) => { e.preventDefault(); state.adminQ = new FormData(e.target).get("q").trim(); state.adminPage = 0; viewAdmin().catch(showError); });
}

/* ---------- booking + report dialogs ---------- */
async function openBook(ctx) {
  state.book = ctx;
  if (ctx.mode === "book") {
    state.book.idemKey = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random();
  }
  const book = ctx.mode === "book";
  $("#bookTitle").textContent = book ? `Book "${ctx.title}"` : "Reschedule session";
  $("#bookHint").textContent = book ? `Costs ${dur(ctx.minutes)}, held until the session ends. Picking a time is optional; you can agree one in Messages.` : "The other person will be notified.";
  $("#noteRow").hidden = !book;
  $("#bookSubmit").textContent = book ? "Request session" : "Move session";
  $("#bookErr").hidden = true;
  $("#bookForm").reset();
  $("#slotHint").textContent = "";
  $("#bookDlg").showModal();
  if (ctx.providerId) {
    try {
      const slots = await api(`/users/${ctx.providerId}/availability`);
      $("#slotHint").textContent = slots.length ? "Provider is available (UTC): " + slots.map(slotText).join(", ") : "Provider is flexible: no fixed hours set.";
    } catch (e) { /* hint only */ }
  }
}

$("#bookCancel").addEventListener("click", () => $("#bookDlg").close());
$("#bookForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const local = f.get("when");
  const iso = local ? new Date(local).toISOString() : null;
  const ctx = state.book;
  const btn = $("#bookSubmit");
  btn.disabled = true;
  try {
    if (ctx.mode === "book") {
      await api("/bookings", {
        method: "POST",
        headers: { "Idempotency-Key": ctx.idemKey },
        body: { listingId: Number(ctx.listingId), note: f.get("note") || null, scheduledAt: iso },
      });
      toast("Requested! Your minutes are held until the session ends.");
      $("#bookDlg").close();
      await loadUser();
      if (location.hash !== "#/bookings") location.hash = "#/bookings"; else route();
    } else {
      if (!iso) throw new Error("Pick the new time");
      await api(`/bookings/${ctx.bookingId}/reschedule`, { method: "POST", body: { scheduledAt: iso } });
      toast("Session rescheduled");
      $("#bookDlg").close();
      await loadUser();
      route();
    }
  } catch (err) {
    const el = $("#bookErr");
    el.textContent = err.message;
    el.hidden = false;
  } finally {
    btn.disabled = false;
  }
});

$("#reportCancel").addEventListener("click", () => $("#reportDlg").close());
$("#reportForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    await api("/reports", { method: "POST", body: { targetType: state.report.type, targetId: Number(state.report.id), reason: f.get("reason"), details: f.get("details") || null } });
    $("#reportDlg").close();
    e.target.reset();
    toast("Thanks, a moderator will review it");
  } catch (err) { toast(err.message); }
});

/* ---------- booking messages ---------- */
async function loadChat() {
  if (!state.chatId) return;
  try {
    const msgs = await api(`/bookings/${state.chatId}/messages`);
    const log = $("#chatLog");
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    log.innerHTML = msgs.length
      ? msgs.map((m) => `<div class="msg ${state.user && m.senderId === state.user.id ? "me" : ""}"><small>${esc(m.senderName)} · ${when(m.createdAt)}</small>${esc(m.body)}</div>`).join("")
      : `<p class="muted">No messages yet. Say hello and agree on a time!</p>`;
    if (atBottom) log.scrollTop = log.scrollHeight;
  } catch (e) { /* keep last view; toast is noisy while polling */ }
}

async function openChat(id, title) {
  state.chatId = id;
  $("#chatTitle").textContent = "Messages · " + title;
  $("#chatLog").innerHTML = `<p class="muted">Loading…</p>`;
  $("#chatDlg").showModal();
  await loadChat();
  $("#chatLog").scrollTop = $("#chatLog").scrollHeight;
  clearInterval(state.chatTimer);
  state.chatTimer = setInterval(loadChat, 8000);
}

function closeChat() {
  clearInterval(state.chatTimer);
  state.chatId = null;
  if ($("#chatDlg").open) $("#chatDlg").close();
}

$("#chatClose").addEventListener("click", closeChat);
$("#chatDlg").addEventListener("close", () => { clearInterval(state.chatTimer); state.chatId = null; });
$("#chatForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = e.target.elements.body;
  const body = input.value.trim();
  if (!body) return;
  try {
    await api(`/bookings/${state.chatId}/messages`, { method: "POST", body: { body } });
    input.value = "";
    await loadChat();
    const log = $("#chatLog");
    log.scrollTop = log.scrollHeight;
  } catch (err) { toast(err.message); }
});

/* ---------- actions (event delegation) ---------- */
document.addEventListener("click", async (e) => {
  const chip = e.target.closest("[data-cat]");
  if (chip && app.contains(chip)) { state.cat = chip.dataset.cat; state.browsePage = 0; return viewBrowse().catch(showError); }

  const el = e.target.closest("[data-action]");
  if (!el) return;
  const { action, id } = el.dataset;
  try {
    switch (action) {
      case "login": return openAuth("login");
      case "signout": return signOut();
      case "offer-cta": location.hash = "#/offer"; return;
      case "book":
        if (!state.user) return openAuth("register");
        return openBook({ mode: "book", listingId: id, title: el.dataset.title, minutes: Number(el.dataset.min), providerId: el.dataset.owner });
      case "reschedule": return openBook({ mode: "reschedule", bookingId: id, providerId: el.dataset.provider });
      case "report":
        if (!state.user) return openAuth("login");
        state.report = { type: el.dataset.type, id };
        return $("#reportDlg").showModal();
      case "readone": api(`/notifications/${id}/read`, { method: "POST" }).then(refreshBadge).catch(() => {}); return;
      case "readall": await api("/notifications/read-all", { method: "POST" }); await refreshBadge(); return viewNotifications();
      case "matches": return showMatches(id, el);
      case "closereq": await api(`/requests/${id}/close`, { method: "POST" }); toast("Request closed"); return viewRequests();
      case "reqpage": state.reqPage = Math.max(0, state.reqPage + Number(el.dataset.dir)); return viewRequests();
      case "browsepage": state.browsePage = Math.max(0, state.browsePage + Number(el.dataset.dir)); return viewBrowse();
      case "slotrm": state.slots.splice(Number(el.dataset.i), 1); return renderSlots();
      case "atab": state.adminTab = el.dataset.tab; state.adminPage = 0; return viewAdmin();
      case "adminpage": state.adminPage = Math.max(0, state.adminPage + Number(el.dataset.dir)); return viewAdmin();
      case "resolve": {
        const note = prompt("Note for the record (optional):");
        if (note === null) return;
        await api(`/admin/reports/${id}/resolve`, { method: "POST", body: { action: el.dataset.act, note: note || null } });
        toast("Report resolved");
        return viewAdmin();
      }
      case "suspend":
        await api(`/admin/users/${id}/suspend`, { method: "POST", body: { value: el.dataset.value === "true" } });
        toast("Updated");
        return viewAdmin();
      case "edit": {
        const l = myListings.find((x) => String(x.id) === String(id));
        if (l) startEdit(l);
        return;
      }
      case "chat": return openChat(id, el.dataset.title);
      case "remove":
        if (!confirm("Remove this listing?")) return;
        await api("/listings/" + id, { method: "DELETE" });
        return loadMine();
      case "review": state.reviewId = id; return $("#reviewDlg").showModal();
      case "accept": case "decline": case "cancel": case "complete":
        el.disabled = true;
        await api(`/bookings/${id}/${action}`, { method: "POST" });
        toast({ accept: "Accepted", decline: "Declined", cancel: "Cancelled", complete: "Session completed — provider paid ✅" }[action]);
        await loadUser();
        return route();
    }
  } catch (err) {
    el.disabled = false;
    toast(err.message);
  }
});

document.addEventListener("change", async (e) => {
  const sel = e.target.closest("[data-role-user]");
  if (!sel) return;
  try {
    await api(`/admin/users/${sel.dataset.roleUser}/role`, { method: "POST", body: { role: sel.value } });
    toast("Role updated");
  } catch (err) { toast(err.message); viewAdmin(); }
});

/* ---------- router ---------- */
function showError(err) {
  app.innerHTML = `<p class="empty err">${esc(err.message)}</p>`;
}

async function route() {
  const parts = (location.hash.replace(/^#\/?/, "") || "").split("/");
  const name = parts[0];
  document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", a.dataset.route === name));
  try {
    switch (name) {
      case "requests": return await viewRequests();
      case "notifications": return await viewNotifications();
      case "admin": return await viewAdmin();
      case "u": return await viewProfile(parts[1]);
      case "me": return await viewMe();
      case "offer": return viewOffer();
      case "bookings": return await viewBookings();
      case "wallet": return await viewWallet();
      case "leaders": return await viewLeaders();
      default: return await viewBrowse();
    }
  } catch (err) {
    showError(err);
  }
}

window.addEventListener("hashchange", route);
setInterval(() => { if (state.user && document.visibilityState === "visible") refreshBadge(); }, 60000);
renderAccount();
loadUser().finally(route);
