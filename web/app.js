"use strict";

const API = window.HOURHIVE_API || "/api";
const app = document.getElementById("app");
const state = {
  token: localStorage.getItem("hh_token"),
  user: null,
  q: "",
  cat: "",
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
async function api(path, { method = "GET", body } = {}) {
  const headers = { "Content-Type": "application/json" };
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
}

function renderAccount() {
  const box = $("#account");
  if (state.user) {
    box.innerHTML = `<span class="chip" title="Your time balance">⏳ ${dur(state.user.balanceMinutes)}</span>
      <span class="muted">${esc(state.user.displayName)}</span>
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
  const [cats, listings] = await Promise.all([api("/categories"), api("/listings?" + qs)]);
  app.innerHTML = `
    <section class="hero">
      <h1>Trade skills for hours, not money.</h1>
      <p>Teach something you're good at, earn time credits, and spend them learning from a neighbour. One hour given = one hour earned.</p>
      <button class="btn" data-action="offer-cta">Offer a skill</button>
    </section>
    <div class="toolbar"><input id="q" type="search" placeholder="Search skills… guitar, resume review, Spanish" value="${esc(state.q)}"></div>
    <div class="chips">
      <button data-cat="" class="${state.cat === "" ? "on" : ""}">All</button>
      ${cats.map((c) => `<button data-cat="${esc(c.category)}" class="${state.cat === c.category ? "on" : ""}">${esc(c.category)} · ${c.listings}</button>`).join("")}
    </div>
    ${listings.length ? `<div class="grid">${listings.map(listingCard).join("")}</div>`
      : `<p class="empty">No skills here yet. Be the first to offer one!</p>`}`;
  const input = $("#q");
  let timer;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => { state.q = input.value.trim(); viewBrowse().catch(showError); }, 350);
  });
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
      <div class="muted">by <b>${esc(l.ownerName)}</b> · ${stars(l.ownerRating)}${l.ownerReviews ? ` (${l.ownerReviews})` : ""}</div>
      <div class="meta">
        <b>⏳ ${dur(l.minutes)}</b>
        ${mine ? `<span class="muted">Your listing</span>` : `<button class="btn primary" data-action="book" data-id="${l.id}" data-title="${esc(l.title)}" data-min="${l.minutes}">Book</button>`}
      </div>
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
      <div class="row"><button class="btn primary" type="submit">Publish</button></div>
    </form>
    <h2 style="margin-top:24px">Your listings</h2><div id="mine" class="list"></div>`;
  $("#offerForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try {
      await api("/listings", { method: "POST", body: { title: f.get("title"), category: f.get("category"), description: f.get("description"), minutes: Number(f.get("minutes")) } });
      toast("Listing published 🐝");
      e.target.reset();
      loadMine();
    } catch (err) { toast(err.message); }
  });
  loadMine();
}

async function loadMine() {
  const box = $("#mine");
  if (!box) return;
  const mine = await api("/listings/mine");
  box.innerHTML = mine.length
    ? mine.map((l) => `<div class="item"><div><b>${esc(l.title)}</b> <span class="tag">${esc(l.category)}</span><br><span class="muted">${dur(l.minutes)} · ${l.active ? "live" : "removed"}</span></div>
        ${l.active ? `<button class="btn danger" data-action="remove" data-id="${l.id}">Remove</button>` : ""}</div>`).join("")
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
  const other = learner ? `with <b>${esc(b.providerName)}</b>` : `for <b>${esc(b.learnerName)}</b>`;
  const acts = [];
  if (!learner && b.status === "REQUESTED") {
    acts.push(`<button class="btn good" data-action="accept" data-id="${b.id}">Accept</button>`);
    acts.push(`<button class="btn danger" data-action="decline" data-id="${b.id}">Decline</button>`);
  }
  if (learner && b.status === "ACCEPTED") acts.push(`<button class="btn good" data-action="complete" data-id="${b.id}">Mark completed</button>`);
  if (["REQUESTED", "ACCEPTED"].includes(b.status) && (learner || b.status === "ACCEPTED")) acts.push(`<button class="btn danger" data-action="cancel" data-id="${b.id}">Cancel</button>`);
  if (learner && b.status === "COMPLETED" && !b.reviewed) acts.push(`<button class="btn" data-action="review" data-id="${b.id}">Leave review</button>`);
  return `<div class="item"><div>
      <span class="status s-${b.status}">${b.status}</span> <b>${esc(b.listingTitle)}</b> ${other} · ${dur(b.minutes)}
      ${b.note ? `<br><span class="muted">“${esc(b.note)}”</span>` : ""}
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

/* ---------- actions (event delegation) ---------- */
document.addEventListener("click", async (e) => {
  const chip = e.target.closest("[data-cat]");
  if (chip && app.contains(chip)) { state.cat = chip.dataset.cat; return viewBrowse().catch(showError); }

  const el = e.target.closest("[data-action]");
  if (!el) return;
  const { action, id } = el.dataset;
  try {
    switch (action) {
      case "login": return openAuth("login");
      case "signout": return signOut();
      case "offer-cta": location.hash = "#/offer"; return;
      case "book": {
        if (!state.user) return openAuth("register");
        const note = prompt(`Book "${el.dataset.title}" for ${dur(Number(el.dataset.min))}?\nAdd a short note for the provider (optional):`);
        if (note === null) return;
        el.disabled = true;
        await api("/bookings", { method: "POST", body: { listingId: Number(id), note: note || null } });
        toast("Requested! Your minutes are held until the session ends.");
        await loadUser();
        location.hash = "#/bookings";
        return;
      }
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

/* ---------- router ---------- */
function showError(err) {
  app.innerHTML = `<p class="empty err">${esc(err.message)}</p>`;
}

async function route() {
  const name = (location.hash.replace(/^#\/?/, "") || "").split("/")[0];
  document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", a.dataset.route === name));
  try {
    switch (name) {
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
renderAccount();
loadUser().finally(route);
