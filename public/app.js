const $ = id => document.getElementById(id);
let currentUser = null;
let authToken = null;

// ---------- helpers ----------
async function api(path, options = {}) {
  options.headers = Object.assign({}, options.headers);
  if (authToken && path !== 'login') options.headers['Authorization'] = 'Bearer ' + authToken;
  const response = await fetch('/api/' + path, options);
  let data = {};
  try { data = await response.json(); } catch (e) { /* non-JSON response */ }
  if (response.status === 401 && authToken && path !== 'login') { logout(); throw new Error('Session expired. Please sign in again.'); }
  if (!response.ok) throw new Error(data.error || 'Something went wrong.');
  return data;
}
function post(path, data) {
  const body = data instanceof HTMLFormElement ? new URLSearchParams(new FormData(data)) : data;
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() });
}
function esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function openModal(id) { $(id).classList.add('open'); }
function closeModal(id) { $(id).classList.remove('open'); }
const isStaff = () => currentUser && (currentUser.role === 'ADMIN' || currentUser.role === 'HOSPITAL');

// ---------- pages and session ----------
function showPage(page) {
  document.querySelectorAll('.page-view').forEach(v => v.classList.toggle('active', v.id === 'page-' + page));
  document.querySelectorAll('.nav-link').forEach(b => b.classList.toggle('active', b.dataset.page === page));
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (page === 'dashboard') loadDashboard();
  if (page === 'donor') renderRoleWorkspace();
}
function renderRoleWorkspace() {
  const role = currentUser?.role;
  $('donorWelcome').textContent = currentUser ? `Welcome, ${currentUser.name}` : 'Welcome, donor';
  $('donorSummary').textContent = !currentUser ? 'Sign in with a donor account to see your personalised workspace.'
    : role === 'DONOR' ? 'Your donor account is active. Explore sample inventory and contact a registered blood bank for real donation arrangements.'
    : `You are signed in as ${role}. This page is the donor information centre; donor-only features are shown to donor accounts.`;
  $('donorWelcome').closest('.role-welcome').classList.toggle('role-active', role === 'DONOR');
}
function setSignedIn(user, token) {
  currentUser = user; authToken = token;
  sessionStorage.setItem('lifelineUser', JSON.stringify(user));
  sessionStorage.setItem('lifelineToken', token);
  $('authButton').textContent = `${user.name.split(' ')[0]} · Sign out`;
  $('authButton').onclick = logout;
  document.body.dataset.role = user.role;
  renderRoleWorkspace();
}
function applyUser(user, token) {
  setSignedIn(user, token);
  const roleText = { DONOR: 'Donor workspace', PATIENT: 'User workspace', HOSPITAL: 'Hospital workspace', ADMIN: 'Admin control centre' }[user.role] || 'User workspace';
  $('profileTitle').textContent = `Welcome, ${user.name}`;
  $('profileText').textContent = `Signed in as ${roleText} (${user.email}).`;
  if (user.role === 'ADMIN') showPage('dashboard');
  else if (user.role === 'DONOR') showPage('donor');
  else if (user.role === 'HOSPITAL') showPage('emergency');
  else showPage('find');
  openModal('profileModal');
}
function logout() {
  if (authToken) fetch('/api/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + authToken } }).catch(() => {});
  currentUser = null; authToken = null;
  sessionStorage.removeItem('lifelineUser'); sessionStorage.removeItem('lifelineToken');
  document.body.removeAttribute('data-role');
  $('authButton').textContent = 'Sign in';
  $('authButton').onclick = () => openModal('loginModal');
  closeModal('profileModal');
  showPage('home');
}
document.querySelectorAll('.modal-backdrop').forEach(m => m.addEventListener('click', e => { if (e.target === m) m.classList.remove('open'); }));

// ---------- find blood ----------
async function searchBlood() {
  const group = $('searchGroup').value, location = $('searchLocation').value.trim();
  const box = $('bloodResults');
  box.innerHTML = '<div class="empty-state">Searching sample inventory…</div>';
  try {
    const rows = await api('blood?' + new URLSearchParams({ group, location }));
    box.innerHTML = rows.length ? rows.map(x => `<article class="blood-card"><div class="blood-card-top"><div class="blood-type">${esc(x.bloodGroup)}</div><span class="stock-status ${x.units < 10 ? 'low' : ''}">${esc(x.status)}</span></div><h3>${esc(x.bank)}</h3><p>⌖ ${esc(x.location)}</p><div class="units"><span>${x.units}</span> units available</div></article>`).join('')
      : '<div class="empty-state">No sample matches found. Try another blood group or location.</div>';
  } catch (e) { box.innerHTML = `<div class="empty-state">${esc(e.message)}</div>`; }
}

// ---------- dashboard ----------
async function loadDashboard() {
  try {
    const d = await api('dashboard');
    $('dashStock').textContent = d.stockTotal + ' units';
    $('dashRequests').textContent = d.emergencyTotal;
    $('dashDonors').textContent = d.donorTotal;
    $('dashDonorsNote').textContent = `${d.donorAvailable} available to be contacted`;
    $('dashLow').textContent = d.lowStock;
    $('statStock').textContent = d.stockTotal;
    $('statDonors').textContent = d.donorTotal;
    $('inventoryRows').innerHTML = d.inventory.map(x => `<tr><td class="table-group">${esc(x.bloodGroup)}</td><td class="table-bank">${esc(x.bank)}<small>${esc(x.location)}</small></td><td>${x.units}</td><td><span class="table-status ${x.units < 10 ? 'low' : ''}">${x.units < 10 ? 'LOW STOCK' : 'AVAILABLE'}</span></td></tr>`).join('');
    $('requestRows').innerHTML = !isStaff() ? '<p class="muted">Sign in as hospital staff or admin to view request details.</p>'
      : d.requests.length ? d.requests.slice().reverse().map(r => `<div class="request-item"><div><b>${esc(r.bloodGroup)} · ${r.units} unit(s) · ${esc(r.hospital)}</b><small>${esc(r.location)} · ${esc(r.urgency)}${r.contact ? ' · ' + esc(r.contact) : ''}</small></div><span class="request-tag">DEMO REQUEST</span></div>`).join('')
      : '<p class="muted">No requests yet. Submit the emergency form to add one.</p>';
    const admin = currentUser?.role === 'ADMIN';
    document.querySelectorAll('.admin-only').forEach(el => el.hidden = !admin);
  } catch (e) { console.error(e); }
}
async function loadRefill() {
  const box = $('refillRows');
  box.innerHTML = '<p class="muted">Calculating…</p>';
  try {
    const rows = await api('refill');
    box.innerHTML = rows.map(r => `<div><span><b>${esc(r.bloodGroup)}</b> · ${esc(r.bank)}<br><span class="muted">Current ${r.currentUnits} / target ${r.illustrativeTarget} units</span></span><span class="refill-need">Refill ${r.refillUnits}</span></div>`).join('');
  } catch (e) { box.innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
}
async function clearDemoData() {
  if (!confirm('Clear demo registrations and emergency requests?')) return;
  try { await post('clear', new URLSearchParams()); await loadDashboard(); $('emergencyMessage').textContent = ''; alert('Demo data cleared.'); }
  catch (e) { alert(e.message); }
}

// ---------- forms ----------
$('emergencyForm').addEventListener('submit', async e => {
  e.preventDefault();
  const msg = $('emergencyMessage'); msg.className = 'message'; msg.textContent = 'Submitting demo request…';
  try { const d = await post('emergency', e.target); msg.textContent = d.message; e.target.reset(); await loadDashboard(); await refreshNotificationCount(); }
  catch (err) { msg.className = 'message error'; msg.textContent = err.message; }
});
$('loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  const msg = $('loginMessage'); msg.className = 'message'; msg.textContent = 'Verifying account…';
  try {
    const d = await post('login', e.target);
    msg.textContent = d.message; closeModal('loginModal'); e.target.reset();
    applyUser({ name: d.name, email: d.email, role: d.role }, d.token);
    await loadDashboard(); await refreshNotificationCount();
  } catch (err) { msg.className = 'message error'; msg.textContent = err.message; }
});
$('registerForm').addEventListener('submit', async e => {
  e.preventDefault();
  const msg = $('registerMessage'); msg.className = 'message'; msg.textContent = 'Creating account…';
  try { const d = await post('register', e.target); e.target.reset(); await loadDashboard(); msg.textContent = d.message + ' You can now sign in with that email and password.'; }
  catch (err) { msg.className = 'message error'; msg.textContent = err.message; }
});
$('donorProfileForm')?.addEventListener('submit', async e => {
  e.preventDefault();
  const msg = $('donorProfileMessage');
  if (!currentUser || currentUser.role !== 'DONOR') { msg.className = 'message error'; msg.textContent = 'Sign in with a donor account to update this profile.'; return; }
  msg.className = 'message'; msg.textContent = 'Saving profile…';
  try { const d = await post('donor-profile', e.target); msg.textContent = d.message; await loadDashboard(); }
  catch (err) { msg.className = 'message error'; msg.textContent = err.message; }
});
$('stockForm')?.addEventListener('submit', async e => {
  e.preventDefault();
  const msg = $('stockMessage');
  try { const d = await post('stock', e.target); msg.className = 'message'; msg.textContent = d.message; e.target.reset(); await loadDashboard(); await searchBlood(); await refreshNotificationCount(); }
  catch (err) { msg.className = 'message error'; msg.textContent = err.message; }
});

// ---------- notification centre (in-app only; no SMS or external alerts) ----------
let readNotifications = new Set(JSON.parse(localStorage.getItem('lifelineReadNotifications') || '[]'));
async function getNotifications() {
  const items = [];
  try {
    const d = await api('dashboard');
    d.inventory.filter(x => x.units < 10).forEach(x => items.push({ id: `low-${x.bloodGroup}-${x.bank}-${x.location}`, title: `Low ${x.bloodGroup} stock`, body: `${x.bank} in ${x.location} has ${x.units} sample unit(s) listed. Call the blood bank to confirm.` }));
    d.requests.slice().reverse().forEach(r => items.push({ id: 'request-' + r.id, title: `${r.urgency} blood request`, body: `${r.bloodGroup}: ${r.units} unit(s) requested by ${r.hospital}, ${r.location}. Demo record only.` }));
    if (currentUser) items.unshift({ id: 'welcome-' + currentUser.email, title: `Welcome, ${currentUser.name}`, body: `You are signed in to the ${currentUser.role.toLowerCase()} workspace.` });
  } catch (e) { items.push({ id: 'system', title: 'Notifications unavailable', body: 'Start the Java server and refresh this panel.' }); }
  return items;
}
function updateNotificationCount(items) {
  const n = items.filter(x => !readNotifications.has(x.id)).length;
  const el = $('notificationCount'); if (el) el.textContent = n > 99 ? '99+' : String(n);
}
function renderNotifications(items) {
  $('notificationList').innerHTML = items.length
    ? items.map(x => `<article class="notification-item ${readNotifications.has(x.id) ? 'read' : ''}"><span class="notification-dot"></span><div><b>${esc(x.title)}</b><p>${esc(x.body)}</p></div></article>`).join('')
    : '<p class="muted">You are all caught up.</p>';
  updateNotificationCount(items);
}
async function refreshNotificationCount() { updateNotificationCount(await getNotifications()); }
async function openNotifications() {
  openModal('notificationsModal');
  $('notificationList').innerHTML = '<p class="muted">Loading…</p>';
  renderNotifications(await getNotifications());
}
async function markNotificationsRead() {
  const items = await getNotifications();
  items.forEach(x => readNotifications.add(x.id));
  localStorage.setItem('lifelineReadNotifications', JSON.stringify([...readNotifications]));
  renderNotifications(items);
}

// ---------- start-up ----------
try {
  const saved = sessionStorage.getItem('lifelineUser'), token = sessionStorage.getItem('lifelineToken');
  if (saved && token) setSignedIn(JSON.parse(saved), token);
} catch (e) { /* ignore bad saved data */ }
searchBlood(); loadDashboard(); refreshNotificationCount();
