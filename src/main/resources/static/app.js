let currentUser = null;

const $ = (id) => document.getElementById(id);

$('loginBtn').onclick = async () => {
  const body = new URLSearchParams({ username: $('username').value, password: $('password').value });
  const r = await fetch('/api/login', {method:'POST', body});
  const data = await r.json();
  if (!data.ok) return alert(data.message);
  currentUser = data;
  $('loginView').hidden = true;
  $('appView').hidden = false;
  $('who').textContent = data.name + (data.role === 'SELLER' ? ' • магазин' : ' • поставщик');
  if (data.role === 'SELLER') $('sellerPanel').hidden = false;
  else $('supplierPanel').hidden = false;
  await refresh();
  setInterval(refresh, 5000);
};

async function refresh() {
  const [state, products, notes] = await Promise.all([
    fetch('/api/state').then(r=>r.json()),
    fetch('/api/products').then(r=>r.json()),
    fetch('/api/notifications').then(r=>r.json())
  ]);
  $('date').textContent = 'Поставка на ' + formatDate(state.date);
  $('lastUpdate').textContent = state.lastSubmittedAt ? 'Обновлено: ' + state.lastSubmittedAt : '';
  $('totalItems').textContent = state.totalItems;
  setStatus(state.status);
  renderNotifications(notes);
  if (currentUser.role === 'SELLER') renderSeller(products);
  else renderSupplier(products);
}

function renderSeller(products) {
  const groups = groupBySupplier(products);
  $('products').innerHTML = Object.entries(groups).map(([supplier, items]) => `
    <div class="supplier-block">
      <div class="supplier-title">${escapeHtml(supplier)}</div>
      ${items.map(p => `
        <div class="product">
          <div><strong>${escapeHtml(p.name)}</strong><div class="muted">Единица: ${escapeHtml(p.unit)}</div></div>
          <input class="qty" type="number" min="0" value="${p.qty}" data-id="${p.id}">
        </div>`).join('')}
    </div>`).join('');
  document.querySelectorAll('.qty').forEach(input => {
    input.onchange = async () => {
      await post('/api/update', {id: input.dataset.id, qty: input.value});
      await refresh();
    };
  });
}

function renderSupplier(products) {
  const selected = products.filter(p => p.qty > 0);
  $('supplierProducts').innerHTML = selected.length ? selected.map(p => `
    <div class="product"><div><strong>${escapeHtml(p.name)}</strong><div class="muted">Поставщик: ${escapeHtml(p.supplier)}</div></div><div><strong>${p.qty} ${escapeHtml(p.unit)}</strong></div></div>`).join('') : '<p class="muted">Пока нет товаров в заявке.</p>';
  $('confirmBtn').disabled = selected.length === 0;
}

$('submitBtn').onclick = async () => {
  const r = await post('/api/submit', {});
  if (!r.ok) return alert(r.message);
  alert('Заявка отправлена поставщику.');
  refresh();
};

$('confirmBtn').onclick = async () => {
  const r = await post('/api/confirm', {});
  if (!r.ok) return alert(r.message);
  alert('Поставка подтверждена.');
  refresh();
};

async function post(url, data) {
  const r = await fetch(url, {method:'POST', body:new URLSearchParams(data)});
  return r.json();
}
function groupBySupplier(items) { return items.reduce((a,p)=>(a[p.supplier]??=[]).push(p)&&a, {}); }
function formatDate(iso) { const [y,m,d] = iso.split('-'); return `${d}.${m}.${y}`; }
function setStatus(s) {
  const el = $('statusBadge');
  el.className = 'badge ' + s.toLowerCase();
  el.textContent = s === 'DRAFT' ? 'Черновик' : s === 'SUBMITTED' ? 'Заявка отправлена' : 'Поставка подтверждена';
}
function renderNotifications(notes) {
  $('notifications').innerHTML = notes.length ? notes.slice(0,10).map(n => `<div class="note">${escapeHtml(n.message)}<small>${escapeHtml(n.time)}</small></div>`).join('') : '<div class="muted">Уведомлений пока нет.</div>';
}
function escapeHtml(s) { return String(s).replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','\'':'&#39;','"':'&quot;'}[c])); }
