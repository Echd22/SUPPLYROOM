/**
 * Supply Room Inventory — Google Apps Script backend
 * Bound to a Google Sheet. The sheet IS the database.
 *
 * Sheets: Catalog, Units, Transactions, Users, Settings
 * - Catalog: one row per equipment TYPE. Tracking = "serialized" or "counted".
 * - Units: one row per serialized piece (control tag + manufacturer serial).
 * - Transactions: append-only log. Counted stock is DERIVED from this log.
 */

const SHEETS = {
  CATALOG: 'Catalog',
  UNITS: 'Units',
  TXNS: 'Transactions',
  USERS: 'Users',
  SETTINGS: 'Settings',
};

const HEADERS = {
  Catalog: ['ItemID', 'Name', 'Category', 'Tracking', 'Barcode', 'Unit', 'MinStock', 'Location', 'Notes', 'Active'],
  Units: ['Tag', 'Serial', 'ItemID', 'Status', 'Location', 'Condition', 'AssignedTo', 'DueBack', 'Expires', 'Received', 'Notes', 'LastTxn'],
  Transactions: ['TxnID', 'BatchID', 'Timestamp', 'User', 'Action', 'ItemID', 'Tag', 'Serial', 'Qty', 'From', 'To',
    'Recipient', 'Condition', 'DueBack', 'Notes', 'Voided', 'VoidedBy', 'VoidedAt'],
  Users: ['Email', 'Name', 'Role', 'Active'],
  Settings: ['Key', 'Value', 'Description'],
};

const DEFAULT_SETTINGS = [
  ['ORG_NAME', 'Supply Room', 'Shown on forms and emails'],
  ['TAG_PREFIX', 'EQ-', 'Prefix for generated control numbers'],
  ['NEXT_TAG', '1', 'Next control number (auto-incremented)'],
  ['DEFAULT_LOCATION', 'Supply Room', 'Default location for received/returned items'],
  ['FORM_TEMPLATE_DOC_ID', '', 'Optional Google Doc template ID for dispense forms (blank = built-in form)'],
  ['FORM_CC', '', 'Optional address CC\'d on every emailed form'],
  ['SUMMARY_RECIPIENTS', '', 'Comma-separated emails for daily summary + low-stock alerts'],
  ['EXPIRY_WARN_DAYS', '90', 'Warn when an expiration date is within N days'],
  ['APP_URL', '', 'Web app /exec URL (paste after deploying; used for the phone setup code)'],
];

// sign = effect on counted stock. ADJUST uses a signed qty.
const ACTIONS = {
  RECEIVE: { label: 'Receive new', sign: 1 },
  ISSUE: { label: 'Issue / dispense', sign: -1 },
  RETURN: { label: 'Return from field', sign: 1 },
  TRANSFER: { label: 'Transfer location', sign: 0 },
  DISPOSE: { label: 'Dispose / surplus', sign: -1 },
  ADJUST: { label: 'Adjust / inspect', sign: 1 },
};

const CONDITIONS = ['Serviceable', 'Needs Inspection', 'Damaged'];

/* ======================= Web app entry ======================= */

function doGet() {
  return HtmlService.createTemplateFromFile('Index').evaluate()
    .setTitle('Supply Room')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

function include(name) {
  return HtmlService.createHtmlOutputFromFile(name).getContent();
}

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Supply Room')
    .addItem('1. Set up sheets', 'setup')
    .addItem('2. Add example items', 'seedExamples')
    .addItem('3. Install daily summary (7am)', 'installTriggers')
    .addItem('4. Show phone setup code', 'showSetupCode')
    .addSeparator()
    .addItem('Send summary now', 'dailySummary')
    .addItem('Change app key (locks out all phones)', 'rotateAppKey')
    .addToUi();
}

/* ======================= Phone app API ======================= */
/*
 * The Android app POSTs JSON: { key, user, fn, args:[...] }
 * Deploy as: Execute as ME, Who has access: ANYONE.
 * The team key (Script Property APP_KEY) gates access; "user" is the
 * person's Name from the Users sheet (picked once on the phone).
 */

let CURRENT_USER = null;

const API = {
  bootstrap: { fn: () => apiBootstrap() },
  lookup: { fn: code => apiLookup(code) },
  submit: { fn: p => apiSubmit(p) },
  stock: { fn: () => apiStock() },
  units: { fn: id => apiUnits(id) },
  history: { fn: n => apiHistory(n) },
  sendForm: { fn: (b, to) => apiSendForm(b, to) },
  cycleCount: { fn: (loc, codes) => apiCycleCount(loc, codes) },
  addItem: { fn: o => apiAddItem(o), admin: true },
  void: { fn: (id, why) => apiVoid(id, why), admin: true },
};

function doPost(e) {
  let out;
  try {
    const req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    const key = PropertiesService.getScriptProperties().getProperty('APP_KEY');
    if (!key || req.key !== key) throw new Error('This phone is not set up (bad or old setup code). Ask your admin for a new setup code.');

    if (req.fn === 'users') {
      out = { ok: true, data: readTable_(SHEETS.USERS).filter(u => u.Name && (u.Active === '' || isTrue_(u.Active)))
        .map(u => ({ name: String(u.Name), role: String(u.Role || 'user').toLowerCase() })) };
    } else {
      const u = readTable_(SHEETS.USERS).find(r => String(r.Name).trim().toLowerCase() === String(req.user || '').trim().toLowerCase() &&
        (r.Active === '' || isTrue_(r.Active)));
      if (!u) throw new Error('User "' + (req.user || '') + '" is not active. Ask your admin.');
      CURRENT_USER = { email: String(u.Name), name: String(u.Name), role: String(u.Role || 'user').toLowerCase() };
      const ep = API[req.fn];
      if (!ep) throw new Error('Unknown request: ' + req.fn);
      if (ep.admin && CURRENT_USER.role !== 'admin') throw new Error('Only admins can do that');
      out = { ok: true, data: ep.fn.apply(null, req.args || []) };
    }
  } catch (err) {
    out = { ok: false, error: err.message || String(err) };
  }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

function appKey_() {
  const props = PropertiesService.getScriptProperties();
  let k = props.getProperty('APP_KEY');
  if (!k) { k = Utilities.getUuid().replace(/-/g, ''); props.setProperty('APP_KEY', k); }
  return k;
}

function rotateAppKey() {
  const ui = SpreadsheetApp.getUi();
  if (ui.alert('Change app key?', 'Every phone will need to scan the new setup code. Continue?', ui.ButtonSet.YES_NO) !== ui.Button.YES) return;
  PropertiesService.getScriptProperties().deleteProperty('APP_KEY');
  appKey_();
  showSetupCode();
}

function showSetupCode() {
  const s = settings_();
  const url = String(s.APP_URL || '').trim();
  const ui = SpreadsheetApp.getUi();
  if (!/^https:\/\/script\.google\.com\/macros\/s\/.+\/exec$/.test(url)) {
    ui.alert('Deploy first', 'Deploy this script as a Web app (Execute as: Me, Who has access: Anyone), then paste the /exec URL into Settings → APP_URL and run this again.', ui.ButtonSet.OK);
    return;
  }
  const payload = JSON.stringify({ t: 'supplyroom', u: url, k: appKey_(), o: s.ORG_NAME || 'Supply Room' });
  const html = HtmlService.createHtmlOutput(
    '<div style="font-family:Arial;text-align:center">' +
    '<p>On each phone: open the Supply Room app and tap <b>Scan setup code</b>.</p>' +
    '<div id="q" style="display:inline-block;padding:12px;background:#fff"></div>' +
    '<p style="font-size:12px;color:#777">Treat this code like a key. Use “Change app key” if a phone is lost.</p></div>' +
    '<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>' +
    '<script>new QRCode(document.getElementById("q"),{text:' + JSON.stringify(payload) + ',width:300,height:300,correctLevel:QRCode.CorrectLevel.M});</script>'
  ).setWidth(400).setHeight(470);
  ui.showModalDialog(html, 'Phone setup code');
}

/* ======================= Setup ======================= */

function setup() {
  const ss = SpreadsheetApp.getActive();
  Object.keys(HEADERS).forEach(name => {
    const sh = ss.getSheetByName(name) || ss.insertSheet(name);
    if (sh.getLastRow() === 0) {
      sh.appendRow(HEADERS[name]);
      sh.setFrozenRows(1);
      sh.getRange(1, 1, 1, HEADERS[name].length).setFontWeight('bold');
    }
  });
  // Keep codes as plain text so leading zeros / long numbers survive.
  ss.getSheetByName(SHEETS.UNITS).getRange('A:B').setNumberFormat('@');
  ss.getSheetByName(SHEETS.CATALOG).getRange('A:A').setNumberFormat('@');
  ss.getSheetByName(SHEETS.CATALOG).getRange('E:E').setNumberFormat('@');
  ss.getSheetByName(SHEETS.TXNS).getRange('G:H').setNumberFormat('@');

  const st = ss.getSheetByName(SHEETS.SETTINGS);
  const have = new Set(readTable_(SHEETS.SETTINGS).map(r => r.Key));
  DEFAULT_SETTINGS.forEach(r => { if (!have.has(r[0])) st.appendRow(r); });

  if (readTable_(SHEETS.USERS).length === 0) {
    ss.getSheetByName(SHEETS.USERS).appendRow([Session.getActiveUser().getEmail(), 'Admin', 'admin', true]);
  }
  const blank = ss.getSheetByName('Sheet1');
  if (blank && blank.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(blank);
}

function seedExamples() {
  apiAddItem({ name: 'Plate carrier', category: 'Body armor', tracking: 'serialized', minStock: 5 });
  apiAddItem({ name: 'Rifle plate', category: 'Body armor', tracking: 'serialized', minStock: 5 });
  apiAddItem({ name: 'Tourniquet', category: 'Medical', tracking: 'counted', unit: 'ea', minStock: 20 });
}

/* ======================= Helpers ======================= */

function sheet_(name) { return SpreadsheetApp.getActive().getSheetByName(name); }
function tz_() { return Session.getScriptTimeZone(); }

function readTable_(name) {
  const sh = sheet_(name);
  if (!sh || sh.getLastRow() < 1) return [];
  const values = sh.getDataRange().getValues();
  const head = values.shift();
  return values.map((row, i) => {
    const o = { _row: i + 2 };
    head.forEach((k, j) => { o[k] = row[j]; });
    return o;
  });
}

function rowOf_(name, obj) {
  return HEADERS[name].map(k => (obj[k] === undefined || obj[k] === null ? '' : obj[k]));
}

function appendRows_(name, objs) {
  if (!objs.length) return;
  const sh = sheet_(name);
  sh.getRange(sh.getLastRow() + 1, 1, objs.length, HEADERS[name].length)
    .setValues(objs.map(o => rowOf_(name, o)));
}

function updateRow_(name, obj) {
  sheet_(name).getRange(obj._row, 1, 1, HEADERS[name].length).setValues([rowOf_(name, obj)]);
}

function settings_() {
  const o = {};
  readTable_(SHEETS.SETTINGS).forEach(r => { o[r.Key] = r.Value; });
  return o;
}

function setSetting_(key, value) {
  const r = readTable_(SHEETS.SETTINGS).find(x => x.Key === key);
  if (r) sheet_(SHEETS.SETTINGS).getRange(r._row, 2).setValue(value);
}

function eq_(a, b) {
  const x = String(a === undefined || a === null ? '' : a).trim().toLowerCase();
  const y = String(b === undefined || b === null ? '' : b).trim().toLowerCase();
  return x !== '' && x === y;
}

function isTrue_(v) { return v === true || String(v).toUpperCase() === 'TRUE'; }
function isVoid_(t) { return isTrue_(t.Voided); }
function isActive_(c) { return c.Active === '' || isTrue_(c.Active); }
function isSer_(item) { return String(item.Tracking).trim().toLowerCase() === 'serialized'; }
function esc_(s) {
  return String(s === undefined || s === null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function fmt_(v) {
  if (!(v instanceof Date)) return v;
  const dateOnly = v.getHours() === 0 && v.getMinutes() === 0;
  return Utilities.formatDate(v, tz_(), dateOnly ? 'yyyy-MM-dd' : 'yyyy-MM-dd HH:mm');
}

// google.script.run cannot return Date objects; flatten them to strings.
function clean_(o) {
  const r = {};
  Object.keys(o).forEach(k => { r[k] = fmt_(o[k]); });
  return r;
}

function catIndex_(cat) {
  const idx = {};
  cat.forEach(c => { idx[String(c.ItemID).toLowerCase()] = c; });
  return idx;
}

function me_() {
  if (CURRENT_USER) return CURRENT_USER; // set by doPost for phone-app requests
  const email = (Session.getActiveUser().getEmail() || '').toLowerCase();
  const u = readTable_(SHEETS.USERS).find(r => String(r.Email).trim().toLowerCase() === email &&
    (r.Active === '' || isTrue_(r.Active)));
  if (!u) throw new Error('Not authorized (' + (email || 'unknown user') + '). Ask an admin to add you to the Users sheet.');
  return { email: email, name: u.Name || email, role: String(u.Role || 'user').toLowerCase() };
}

function counts_(txns) {
  const c = {};
  txns.forEach(t => {
    if (isVoid_(t) || t.Tag) return; // serialized rows carry a Tag
    const a = ACTIONS[t.Action];
    if (!a) return;
    const id = String(t.ItemID).toLowerCase();
    c[id] = (c[id] || 0) + a.sign * Number(t.Qty || 0);
  });
  return c;
}

function folder_(name) {
  // Prefer a subfolder next to the spreadsheet (share that folder with the team).
  try {
    const parent = DriveApp.getFileById(SpreadsheetApp.getActive().getId()).getParents().next();
    const it = parent.getFoldersByName(name);
    return it.hasNext() ? it.next() : parent.createFolder(name);
  } catch (e) {
    const it = DriveApp.getFoldersByName(name);
    return it.hasNext() ? it.next() : DriveApp.createFolder(name);
  }
}

/* ======================= API: bootstrap & lookup ======================= */

function apiBootstrap() {
  const me = me_();
  const s = settings_();
  return {
    me: me,
    org: s.ORG_NAME,
    defaultLocation: s.DEFAULT_LOCATION,
    sheetUrl: SpreadsheetApp.getActive().getUrl(),
    actions: ACTIONS,
    conditions: CONDITIONS,
    catalog: readTable_(SHEETS.CATALOG).filter(isActive_).map(clean_),
    // Compact unit list so the phone app can resolve scans instantly.
    units: readTable_(SHEETS.UNITS).filter(u => u.Tag && u.Status !== 'Voided')
      .map(u => ({ t: String(u.Tag), s: String(u.Serial), i: String(u.ItemID), st: String(u.Status), a: String(u.AssignedTo || '') })),
  };
}

function apiLookup(code) {
  me_();
  code = String(code || '').trim();
  if (!code) throw new Error('Empty code');
  const units = readTable_(SHEETS.UNITS);
  const cat = readTable_(SHEETS.CATALOG);
  const u = units.find(r => eq_(r.Tag, code)) || units.find(r => eq_(r.Serial, code));
  if (u) {
    const item = cat.find(c => eq_(c.ItemID, u.ItemID));
    return { kind: 'unit', unit: clean_(u), item: item ? clean_(item) : null };
  }
  const item = cat.find(c => eq_(c.Barcode, code)) || cat.find(c => eq_(c.ItemID, code));
  if (item) return { kind: 'item', item: clean_(item) };
  return { kind: 'unknown', code: code };
}

/* ======================= API: transactions ======================= */

/**
 * p = { clientId, action, lines:[{itemId, tag, serial, qty, condition, expires, notes}],
 *       recipient, to, dueBack, notes, emailTo, sendForm, signature(dataURL) }
 */
function apiSubmit(p) {
  const me = me_();
  if (!ACTIONS[p.action]) throw new Error('Unknown action: ' + p.action);
  if (!p.lines || !p.lines.length) throw new Error('Nothing to submit');
  if (p.action === 'ISSUE' && !String(p.recipient || '').trim()) throw new Error('Recipient is required to issue equipment');
  if (p.action === 'TRANSFER' && !String(p.to || '').trim()) throw new Error('Destination location is required');

  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  let result;
  try {
    const s = settings_();
    const cat = readTable_(SHEETS.CATALOG);
    const units = readTable_(SHEETS.UNITS);
    const allTx = readTable_(SHEETS.TXNS);
    const now = new Date();
    const batch = /^B[A-Za-z0-9-]{4,40}$/.test(p.clientId || '') ? p.clientId
      : 'B' + Utilities.formatDate(now, tz_(), 'yyMMddHHmmss') + '-' + Math.floor(Math.random() * 1000);

    // Idempotency: a retried (offline-queued) submit doesn't double-post.
    if (allTx.some(t => t.BatchID === batch)) return { batchId: batch, duplicate: true, lines: [], low: [] };

    const stock = counts_(allTx);
    let nextTag = Number(s.NEXT_TAG) || 1;
    const txns = [], newUnits = [], changed = [], out = [], touched = new Set();

    p.lines.forEach((ln, i) => {
      const n = i + 1;
      const item = cat.find(c => eq_(c.ItemID, ln.itemId));
      if (!item) throw new Error('Line ' + n + ': unknown item ' + ln.itemId);
      touched.add(String(item.ItemID).toLowerCase());
      const base = {
        TxnID: batch + '-' + n, BatchID: batch, Timestamp: now, User: me.email, Action: p.action,
        ItemID: item.ItemID, Recipient: p.recipient || '', Condition: ln.condition || '',
        DueBack: p.action === 'ISSUE' ? (p.dueBack || '') : '',
        Notes: [p.notes, ln.notes].filter(Boolean).join(' | '), Voided: '',
      };

      if (isSer_(item)) {
        if (p.action === 'RECEIVE' && !ln.tag) {
          const serial = String(ln.serial || '').trim();
          if (!serial) throw new Error('Line ' + n + ': serial number required for ' + item.Name);
          if (units.concat(newUnits).some(x => eq_(x.Serial, serial))) throw new Error('Serial ' + serial + ' already exists');
          const tag = (s.TAG_PREFIX || '') + String(nextTag++).padStart(6, '0');
          const loc = p.to || item.Location || s.DEFAULT_LOCATION;
          const cond = ln.condition || 'Serviceable';
          newUnits.push({
            Tag: tag, Serial: serial, ItemID: item.ItemID, Status: cond === 'Serviceable' ? 'In Stock' : 'Needs Inspection',
            Location: loc, Condition: cond, AssignedTo: '', DueBack: '', Expires: ln.expires || '',
            Received: now, Notes: ln.notes || '', LastTxn: base.TxnID,
          });
          txns.push(Object.assign(base, { Tag: tag, Serial: serial, Qty: 1, From: 'Vendor', To: loc, Condition: cond }));
          out.push({ tag: tag, serial: serial, name: item.Name, isNew: true });
          return;
        }
        const u = units.find(x => eq_(x.Tag, ln.tag)) || units.find(x => eq_(x.Serial, ln.serial || ln.tag));
        if (!u) throw new Error('Line ' + n + ': unit not found (' + (ln.tag || ln.serial) + ')');
        if (changed.indexOf(u) >= 0) throw new Error(u.Tag + ' is in the list twice');
        const from = u.Status === 'Issued' ? (u.AssignedTo || 'Field') : u.Location;
        applyToUnit_(u, p.action, p, ln, s);
        u.LastTxn = base.TxnID;
        changed.push(u);
        txns.push(Object.assign(base, {
          Tag: u.Tag, Serial: u.Serial, Qty: 1, From: from,
          To: p.action === 'ISSUE' ? p.recipient : p.action === 'DISPOSE' ? 'Disposed' : u.Location,
          Condition: u.Condition,
        }));
        out.push({ tag: u.Tag, serial: u.Serial, name: item.Name });
      } else {
        const qty = Number(ln.qty);
        if (!qty || Math.floor(qty) !== qty || (p.action !== 'ADJUST' && qty < 0)) throw new Error('Line ' + n + ': bad quantity for ' + item.Name);
        const sign = ACTIONS[p.action].sign;
        const id = String(item.ItemID).toLowerCase();
        const onHand = stock[id] || 0;
        if (onHand + sign * qty < 0) throw new Error('Only ' + onHand + ' ' + item.Name + ' on hand');
        stock[id] = onHand + sign * qty;
        const home = item.Location || s.DEFAULT_LOCATION;
        let from = home, to = home;
        if (p.action === 'RECEIVE') from = 'Vendor';
        if (p.action === 'RETURN') from = 'Field';
        if (p.action === 'ISSUE') to = p.recipient;
        if (p.action === 'DISPOSE') to = 'Disposed';
        if (p.action === 'TRANSFER') to = p.to;
        txns.push(Object.assign(base, { Qty: qty, From: from, To: to }));
        out.push({ name: item.Name, qty: qty });
      }
    });

    appendRows_(SHEETS.UNITS, newUnits);
    changed.forEach(u => updateRow_(SHEETS.UNITS, u));
    appendRows_(SHEETS.TXNS, txns);
    if (nextTag !== (Number(s.NEXT_TAG) || 1)) setSetting_('NEXT_TAG', nextTag);
    SpreadsheetApp.flush();

    // Low-stock check on touched items
    const allUnits = units.concat(newUnits);
    const low = [];
    cat.filter(c => touched.has(String(c.ItemID).toLowerCase()) && Number(c.MinStock) > 0).forEach(c => {
      const have = isSer_(c)
        ? allUnits.filter(u => eq_(u.ItemID, c.ItemID) && u.Status === 'In Stock').length
        : (stock[String(c.ItemID).toLowerCase()] || 0);
      if (have < Number(c.MinStock)) low.push({ name: c.Name, onHand: have, min: Number(c.MinStock) });
    });

    result = { batchId: batch, lines: out, low: low, emailed: false };
    if (p.signature) saveSignature_(batch, p.signature);
    if (low.length && s.SUMMARY_RECIPIENTS) {
      try {
        MailApp.sendEmail({
          to: s.SUMMARY_RECIPIENTS, subject: s.ORG_NAME + ' — low stock alert',
          htmlBody: '<p>After batch ' + esc_(batch) + ' by ' + esc_(me.email) + ':</p><ul>' +
            low.map(l => '<li>' + esc_(l.name) + ': ' + l.onHand + ' on hand (min ' + l.min + ')</li>').join('') + '</ul>',
        });
      } catch (e) { result.alertError = e.message; }
    }
  } finally {
    lock.releaseLock();
  }

  if (p.sendForm && p.emailTo) {
    try { sendForm_(result.batchId, p.emailTo); result.emailed = true; } catch (e) { result.emailError = e.message; }
  }
  return result;
}

function applyToUnit_(u, action, p, ln, s) {
  const st = u.Status;
  const cond = ln.condition || '';
  switch (action) {
    case 'RECEIVE':
      throw new Error(u.Tag + ' already exists — use Return or Adjust instead');
    case 'ISSUE':
      if (st !== 'In Stock') throw new Error(u.Tag + ' is "' + st + '", not In Stock');
      u.Status = 'Issued'; u.AssignedTo = p.recipient; u.DueBack = p.dueBack || '';
      break;
    case 'RETURN':
      if (st === 'Disposed' || st === 'Voided') throw new Error(u.Tag + ' is ' + st);
      u.Condition = cond || 'Serviceable';
      u.Status = u.Condition === 'Serviceable' ? 'In Stock' : 'Needs Inspection';
      u.AssignedTo = ''; u.DueBack = '';
      u.Location = p.to || s.DEFAULT_LOCATION;
      break;
    case 'TRANSFER':
      if (st === 'Issued' || st === 'Disposed') throw new Error(u.Tag + ' is ' + st + '; cannot transfer');
      u.Location = p.to;
      break;
    case 'DISPOSE':
      if (st === 'Issued') throw new Error(u.Tag + ' is still issued to ' + u.AssignedTo);
      u.Status = 'Disposed'; u.AssignedTo = ''; u.DueBack = '';
      break;
    case 'ADJUST': // e.g. inspection passed/failed
      if (cond) {
        u.Condition = cond;
        if (st === 'In Stock' || st === 'Needs Inspection') u.Status = cond === 'Serviceable' ? 'In Stock' : 'Needs Inspection';
      }
      if (p.to) u.Location = p.to;
      break;
  }
}

/** Rebuild a unit's state from its non-voided history. */
function recomputeUnit_(u, txns) {
  const list = txns.filter(t => eq_(t.Tag, u.Tag) && !isVoid_(t))
    .sort((a, b) => new Date(a.Timestamp) - new Date(b.Timestamp));
  u.Status = 'Voided'; u.AssignedTo = ''; u.DueBack = '';
  list.forEach(t => {
    const cond = t.Condition || 'Serviceable';
    switch (t.Action) {
      case 'RECEIVE': u.Status = cond === 'Serviceable' ? 'In Stock' : 'Needs Inspection'; u.Location = t.To; u.Condition = cond; break;
      case 'ISSUE': u.Status = 'Issued'; u.AssignedTo = t.Recipient; u.DueBack = t.DueBack; break;
      case 'RETURN': u.Status = cond === 'Serviceable' ? 'In Stock' : 'Needs Inspection'; u.Condition = cond; u.Location = t.To; u.AssignedTo = ''; u.DueBack = ''; break;
      case 'TRANSFER': u.Location = t.To; break;
      case 'DISPOSE': u.Status = 'Disposed'; u.AssignedTo = ''; u.DueBack = ''; break;
      case 'ADJUST':
        u.Condition = cond;
        if (u.Status === 'In Stock' || u.Status === 'Needs Inspection') u.Status = cond === 'Serviceable' ? 'In Stock' : 'Needs Inspection';
        if (t.To) u.Location = t.To;
        break;
    }
    u.LastTxn = t.TxnID;
  });
}

function apiVoid(txnId, reason) {
  const me = me_();
  if (me.role !== 'admin') throw new Error('Only admins can void transactions');
  if (!String(reason || '').trim()) throw new Error('A reason is required');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const txns = readTable_(SHEETS.TXNS);
    const t = txns.find(x => x.TxnID === txnId);
    if (!t) throw new Error('Transaction not found');
    if (isVoid_(t)) throw new Error('Already voided');
    t.Voided = true; t.VoidedBy = me.email; t.VoidedAt = new Date();
    t.Notes = [t.Notes, 'VOID: ' + reason].filter(Boolean).join(' | ');
    updateRow_(SHEETS.TXNS, t);
    if (t.Tag) {
      const u = readTable_(SHEETS.UNITS).find(x => eq_(x.Tag, t.Tag));
      if (u) { recomputeUnit_(u, txns); updateRow_(SHEETS.UNITS, u); }
    }
    return true;
  } finally {
    lock.releaseLock();
  }
}

/* ======================= API: catalog ======================= */

function apiAddItem(o) {
  me_();
  const name = String(o.name || '').trim();
  const tracking = String(o.tracking || '').toLowerCase();
  if (!name) throw new Error('Name is required');
  if (tracking !== 'serialized' && tracking !== 'counted') throw new Error('Tracking must be serialized or counted');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const cat = readTable_(SHEETS.CATALOG);
    const barcode = String(o.barcode || '').trim();
    if (barcode && cat.some(c => eq_(c.Barcode, barcode))) throw new Error('Barcode ' + barcode + ' is already assigned');
    let n = cat.length + 1, id;
    do { id = 'ITM-' + String(n++).padStart(4, '0'); } while (cat.some(c => eq_(c.ItemID, id)));
    const row = {
      ItemID: id, Name: name, Category: o.category || '', Tracking: tracking, Barcode: barcode,
      Unit: o.unit || 'ea', MinStock: Number(o.minStock) || 0, Location: o.location || '', Notes: o.notes || '', Active: true,
    };
    appendRows_(SHEETS.CATALOG, [row]);
    return row;
  } finally {
    lock.releaseLock();
  }
}

/* ======================= API: stock, history, charts ======================= */

function stockRows_() {
  const cat = readTable_(SHEETS.CATALOG).filter(isActive_);
  const units = readTable_(SHEETS.UNITS);
  const counts = counts_(readTable_(SHEETS.TXNS));
  return cat.map(c => {
    const ser = isSer_(c);
    const r = {
      itemId: c.ItemID, name: c.Name, category: c.Category, tracking: ser ? 'serialized' : 'counted',
      unit: c.Unit, min: Number(c.MinStock) || 0, location: c.Location, barcode: c.Barcode,
    };
    if (ser) {
      const us = units.filter(u => eq_(u.ItemID, c.ItemID));
      r.inStock = us.filter(u => u.Status === 'In Stock').length;
      r.issued = us.filter(u => u.Status === 'Issued').length;
      r.inspect = us.filter(u => u.Status === 'Needs Inspection').length;
      r.onHand = r.inStock;
    } else {
      r.onHand = counts[String(c.ItemID).toLowerCase()] || 0;
    }
    r.low = r.min > 0 && r.onHand < r.min;
    return r;
  });
}

function apiStock() { me_(); return stockRows_(); }

function apiUnits(itemId) {
  me_();
  return readTable_(SHEETS.UNITS).filter(u => eq_(u.ItemID, itemId) && u.Status !== 'Voided').map(clean_);
}

function apiHistory(limit) {
  me_();
  const idx = catIndex_(readTable_(SHEETS.CATALOG));
  const n = Math.min(Number(limit) || 150, 1000);
  return readTable_(SHEETS.TXNS).slice(-n).reverse().map(t => {
    const r = clean_(t);
    const c = idx[String(t.ItemID).toLowerCase()];
    r.ItemName = c ? c.Name : t.ItemID;
    r.Voided = isVoid_(t);
    delete r._row;
    return r;
  });
}

function apiChart(days, itemId) {
  me_();
  days = Math.max(7, Math.min(Number(days) || 30, 730));
  const weekly = days > 90;
  const tz = tz_();
  const keyFor = d => {
    const x = new Date(d);
    if (weekly) x.setDate(x.getDate() - x.getDay());
    return Utilities.formatDate(x, tz, 'yyyy-MM-dd');
  };
  const since = new Date(); since.setHours(0, 0, 0, 0); since.setDate(since.getDate() - days + 1);
  const buckets = {}, order = [];
  for (let d = new Date(since); d <= new Date(); d.setDate(d.getDate() + 1)) {
    const k = keyFor(d);
    if (!buckets[k]) { buckets[k] = { RECEIVE: 0, RETURN: 0, ISSUE: 0, DISPOSE: 0 }; order.push(k); }
  }
  const idx = catIndex_(readTable_(SHEETS.CATALOG));
  const issuedBy = {};
  readTable_(SHEETS.TXNS).forEach(t => {
    if (isVoid_(t) || !(t.Timestamp instanceof Date) || t.Timestamp < since) return;
    if (itemId && !eq_(t.ItemID, itemId)) return;
    const b = buckets[keyFor(t.Timestamp)];
    const q = Math.abs(Number(t.Qty) || 0);
    if (b && b[t.Action] !== undefined) b[t.Action] += q;
    if (t.Action === 'ISSUE') {
      const c = idx[String(t.ItemID).toLowerCase()];
      const nm = c ? c.Name : t.ItemID;
      issuedBy[nm] = (issuedBy[nm] || 0) + q;
    }
  });
  const top = Object.keys(issuedBy).map(k => ({ name: k, qty: issuedBy[k] }))
    .sort((a, b) => b.qty - a.qty).slice(0, 10);
  return {
    weekly: weekly, labels: order,
    received: order.map(k => buckets[k].RECEIVE), returned: order.map(k => buckets[k].RETURN),
    issued: order.map(k => buckets[k].ISSUE), disposed: order.map(k => buckets[k].DISPOSE),
    topIssued: top,
  };
}

/* ======================= API: cycle count ======================= */

function apiCycleCount(location, codes) {
  me_();
  const units = readTable_(SHEETS.UNITS);
  const cat = readTable_(SHEETS.CATALOG);
  const idx = catIndex_(cat);
  const counts = counts_(readTable_(SHEETS.TXNS));
  const nm = id => { const c = idx[String(id).toLowerCase()]; return c ? c.Name : id; };
  const tally = {};
  (codes || []).forEach(c => { c = String(c).trim(); if (c) tally[c] = (tally[c] || 0) + 1; });

  const found = new Set(), unexpected = [], unknown = [], counted = {};
  Object.keys(tally).forEach(code => {
    const u = units.find(x => eq_(x.Tag, code)) || units.find(x => eq_(x.Serial, code));
    if (u) {
      found.add(u.Tag);
      const shelf = u.Status === 'In Stock' || u.Status === 'Needs Inspection';
      if (!shelf || (location && !eq_(u.Location, location))) {
        unexpected.push({ tag: u.Tag, serial: u.Serial, name: nm(u.ItemID), status: u.Status, location: u.Location, assignedTo: u.AssignedTo });
      }
      return;
    }
    const item = cat.find(c => eq_(c.Barcode, code)) || cat.find(c => eq_(c.ItemID, code));
    if (item && !isSer_(item)) { counted[item.ItemID] = (counted[item.ItemID] || 0) + tally[code]; return; }
    unknown.push(item ? code + ' (item barcode — scan unit tags for ' + item.Name + ')' : code);
  });

  const expected = units.filter(u => (u.Status === 'In Stock' || u.Status === 'Needs Inspection') &&
    (!location || eq_(u.Location, location)));
  const missing = expected.filter(u => !found.has(u.Tag))
    .map(u => ({ tag: u.Tag, serial: u.Serial, name: nm(u.ItemID), location: u.Location }));
  return {
    location: location || '(all)', expected: expected.length, matched: expected.length - missing.length,
    missing: missing, unexpected: unexpected, unknown: unknown,
    counted: Object.keys(counted).map(id => ({
      itemId: id, name: nm(id), scanned: counted[id], system: counts[String(id).toLowerCase()] || 0,
    })),
  };
}

/* ======================= Forms, signatures, email ======================= */

function saveSignature_(batchId, dataUrl) {
  try {
    const b64 = String(dataUrl).split(',')[1];
    if (!b64) return;
    const blob = Utilities.newBlob(Utilities.base64Decode(b64), 'image/png', batchId + '.png');
    folder_('Supply Room Signatures').createFile(blob);
  } catch (e) { /* non-fatal */ }
}

function getSignature_(batchId) {
  try {
    const it = folder_('Supply Room Signatures').getFilesByName(batchId + '.png');
    return it.hasNext() ? it.next().getBlob() : null;
  } catch (e) { return null; }
}

function formData_(batchId) {
  const txns = readTable_(SHEETS.TXNS).filter(t => t.BatchID === batchId && !isVoid_(t));
  if (!txns.length) throw new Error('No active transactions in batch ' + batchId);
  const s = settings_();
  const idx = catIndex_(readTable_(SHEETS.CATALOG));
  const t0 = txns[0];
  const ctx = {
    ORG: s.ORG_NAME, BATCH: batchId, DATE: fmt_(t0.Timestamp), ACTION: (ACTIONS[t0.Action] || {}).label || t0.Action,
    RECIPIENT: t0.Recipient || '', ISSUED_BY: t0.User, DUE_BACK: t0.DueBack ? fmt_(t0.DueBack) : '', NOTES: t0.Notes || '',
  };
  const rows = [['Item', 'Control #', 'Serial', 'Qty', 'Condition']].concat(txns.map(t => {
    const c = idx[String(t.ItemID).toLowerCase()];
    return [c ? c.Name : String(t.ItemID), String(t.Tag || ''), String(t.Serial || ''), String(t.Qty), String(t.Condition || '')];
  }));
  return { ctx: ctx, rows: rows, s: s, sig: getSignature_(batchId) };
}

function buildFormPdf_(batchId) {
  const f = formData_(batchId);
  const name = f.ctx.ORG + ' ' + f.ctx.BATCH + '.pdf';
  if (f.s.FORM_TEMPLATE_DOC_ID) return templatePdf_(f.s.FORM_TEMPLATE_DOC_ID, f).setName(name);

  const c = f.ctx;
  const sigImg = f.sig ? '<img src="data:image/png;base64,' + Utilities.base64Encode(f.sig.getBytes()) + '" style="height:70px">' : '';
  const html =
    '<html><body style="font-family:Arial,sans-serif;font-size:11pt">' +
    '<h2 style="margin:0">' + esc_(c.ORG) + '</h2>' +
    '<h3 style="margin:4px 0 12px">Equipment ' + esc_(c.ACTION) + ' Record</h3>' +
    '<table style="font-size:10pt;margin-bottom:12px">' +
    '<tr><td><b>Date:</b></td><td>' + esc_(c.DATE) + '</td><td style="padding-left:24px"><b>Record #:</b></td><td>' + esc_(c.BATCH) + '</td></tr>' +
    '<tr><td><b>Recipient:</b></td><td>' + esc_(c.RECIPIENT) + '</td><td style="padding-left:24px"><b>Processed by:</b></td><td>' + esc_(c.ISSUED_BY) + '</td></tr>' +
    (c.DUE_BACK ? '<tr><td><b>Due back:</b></td><td>' + esc_(c.DUE_BACK) + '</td></tr>' : '') +
    '</table>' +
    '<table style="border-collapse:collapse;width:100%;font-size:10pt">' +
    f.rows.map((r, i) => '<tr>' + r.map(v => (i === 0 ? '<th' : '<td') +
      ' style="border:1px solid #444;padding:4px;text-align:left' + (i === 0 ? ';background:#ddd' : '') + '">' +
      esc_(v) + (i === 0 ? '</th>' : '</td>')).join('') + '</tr>').join('') +
    '</table>' +
    (c.NOTES ? '<p><b>Notes:</b> ' + esc_(c.NOTES) + '</p>' : '') +
    '<p style="margin-top:24px">I acknowledge receipt of the equipment listed above.</p>' +
    '<div style="margin-top:8px">' + sigImg + '</div>' +
    '<div style="border-top:1px solid #000;width:300px;margin-top:4px">Signature — ' + esc_(c.RECIPIENT) + '</div>' +
    '</body></html>';
  return Utilities.newBlob(html, 'text/html', 'form.html').getAs('application/pdf').setName(name);
}

/**
 * Template placeholders (put {{ITEMS}} and {{SIGNATURE}} each in their own paragraph):
 * {{ORG}} {{BATCH}} {{DATE}} {{ACTION}} {{RECIPIENT}} {{ISSUED_BY}} {{DUE_BACK}} {{NOTES}} {{ITEMS}} {{SIGNATURE}}
 */
function templatePdf_(templateId, f) {
  const copy = DriveApp.getFileById(templateId).makeCopy('tmp-' + f.ctx.BATCH);
  try {
    const doc = DocumentApp.openById(copy.getId());
    const body = doc.getBody();
    Object.keys(f.ctx).forEach(k => body.replaceText('\\{\\{' + k + '\\}\\}', String(f.ctx[k] || '')));
    const items = body.findText('\\{\\{ITEMS\\}\\}');
    if (items) {
      const para = items.getElement().getParent();
      const i = body.getChildIndex(para);
      const table = body.insertTable(i + 1, f.rows);
      table.getRow(0).editAsText().setBold(true);
      para.asParagraph().setText('');
    }
    const sig = body.findText('\\{\\{SIGNATURE\\}\\}');
    if (sig) {
      const p = sig.getElement().getParent().asParagraph();
      p.setText('');
      if (f.sig) {
        const img = p.appendInlineImage(f.sig);
        const ratio = img.getWidth() / img.getHeight();
        img.setHeight(60).setWidth(Math.round(60 * ratio));
      }
    }
    doc.saveAndClose();
    return copy.getAs('application/pdf');
  } finally {
    copy.setTrashed(true);
  }
}

function sendForm_(batchId, to) {
  const pdf = buildFormPdf_(batchId);
  const s = settings_();
  const opts = {
    to: to,
    subject: s.ORG_NAME + ' — equipment record ' + batchId,
    htmlBody: '<p>Attached is the equipment record for batch <b>' + esc_(batchId) + '</b>.</p>',
    attachments: [pdf],
  };
  if (s.FORM_CC) opts.cc = s.FORM_CC;
  MailApp.sendEmail(opts);
}

function apiSendForm(batchId, to) {
  me_();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(String(to || '').split(',')[0].trim())) throw new Error('Enter a valid email address');
  sendForm_(batchId, to);
  return true;
}

/* ======================= Labels ======================= */

function apiLabelData(itemId) {
  me_();
  const idx = catIndex_(readTable_(SHEETS.CATALOG));
  return readTable_(SHEETS.UNITS)
    .filter(u => eq_(u.ItemID, itemId) && u.Status !== 'Disposed' && u.Status !== 'Voided')
    .map(u => ({ tag: u.Tag, serial: u.Serial, name: (idx[String(u.ItemID).toLowerCase()] || {}).Name || u.ItemID }));
}

/** labels = [{tag, serial, name, img(dataURL of QR)}] -> PDF in Drive, returns URL */
function apiLabelsPdf(labels) {
  me_();
  if (!labels || !labels.length) throw new Error('No labels');
  const cells = labels.map(l =>
    '<td style="width:33%;padding:6px;border:1px dashed #bbb;text-align:center;vertical-align:top">' +
    '<img src="' + l.img + '" style="width:90px;height:90px"><br>' +
    '<b style="font-size:11pt">' + esc_(l.tag) + '</b><br>' +
    '<span style="font-size:8pt">' + esc_(l.name) + '</span><br>' +
    '<span style="font-size:7pt">S/N ' + esc_(l.serial) + '</span></td>');
  let rows = '';
  for (let i = 0; i < cells.length; i += 3) {
    rows += '<tr>' + cells.slice(i, i + 3).join('') + '<td></td>'.repeat(Math.max(0, 3 - cells.slice(i, i + 3).length)) + '</tr>';
  }
  const html = '<html><body style="font-family:Arial,sans-serif"><table style="width:100%;border-collapse:collapse">' + rows + '</table></body></html>';
  const name = 'Labels ' + Utilities.formatDate(new Date(), tz_(), 'yyyy-MM-dd HHmm') + '.pdf';
  const pdf = Utilities.newBlob(html, 'text/html', 'labels.html').getAs('application/pdf').setName(name);
  return folder_('Supply Room Labels').createFile(pdf).getUrl();
}

/* ======================= Daily summary ======================= */

function installTriggers() {
  ScriptApp.getProjectTriggers().filter(t => t.getHandlerFunction() === 'dailySummary')
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('dailySummary').timeBased().everyDays(1).atHour(7).create();
}

function dailySummary() {
  const s = settings_();
  if (!s.SUMMARY_RECIPIENTS) return;
  const idx = catIndex_(readTable_(SHEETS.CATALOG));
  const nm = id => (idx[String(id).toLowerCase()] || {}).Name || id;
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const warn = new Date(today); warn.setDate(warn.getDate() + (Number(s.EXPIRY_WARN_DAYS) || 90));
  const units = readTable_(SHEETS.UNITS).filter(u => u.Status !== 'Disposed' && u.Status !== 'Voided');

  const low = stockRows_().filter(r => r.low);
  const expiring = units.filter(u => u.Expires instanceof Date && u.Expires <= warn).sort((a, b) => a.Expires - b.Expires);
  const overdue = units.filter(u => u.Status === 'Issued' && u.DueBack instanceof Date && u.DueBack < today);
  const inspect = units.filter(u => u.Status === 'Needs Inspection');
  const since = new Date(Date.now() - 864e5);
  const activity = {};
  readTable_(SHEETS.TXNS).forEach(t => {
    if (!isVoid_(t) && t.Timestamp instanceof Date && t.Timestamp >= since) activity[t.Action] = (activity[t.Action] || 0) + Math.abs(Number(t.Qty) || 0);
  });

  const table = (head, rows) => rows.length
    ? '<table style="border-collapse:collapse">' + '<tr>' + head.map(h => '<th style="border:1px solid #999;padding:3px 6px;background:#eee">' + h + '</th>').join('') + '</tr>' +
      rows.map(r => '<tr>' + r.map(v => '<td style="border:1px solid #999;padding:3px 6px">' + esc_(fmt_(v)) + '</td>').join('') + '</tr>').join('') + '</table>'
    : '<p style="color:#777">None</p>';

  const html =
    '<div style="font-family:Arial,sans-serif;font-size:10pt">' +
    '<h3>Last 24 hours</h3>' +
    (Object.keys(activity).length ? '<p>' + Object.keys(activity).map(a => ACTIONS[a].label + ': ' + activity[a]).join(' · ') + '</p>' : '<p style="color:#777">No activity</p>') +
    '<h3>Below minimum stock (' + low.length + ')</h3>' + table(['Item', 'On hand', 'Min'], low.map(r => [r.name, r.onHand, r.min])) +
    '<h3>Overdue loaners (' + overdue.length + ')</h3>' + table(['Tag', 'Item', 'Issued to', 'Due'], overdue.map(u => [u.Tag, nm(u.ItemID), u.AssignedTo, u.DueBack])) +
    '<h3>Expiring within ' + (s.EXPIRY_WARN_DAYS || 90) + ' days (' + expiring.length + ')</h3>' + table(['Tag', 'Item', 'Status', 'Expires'], expiring.map(u => [u.Tag, nm(u.ItemID), u.Status, u.Expires])) +
    '<h3>Needs inspection (' + inspect.length + ')</h3>' + table(['Tag', 'Item', 'Condition', 'Location'], inspect.map(u => [u.Tag, nm(u.ItemID), u.Condition, u.Location])) +
    '</div>';

  MailApp.sendEmail({ to: s.SUMMARY_RECIPIENTS, subject: s.ORG_NAME + ' — daily summary ' + Utilities.formatDate(new Date(), tz_(), 'yyyy-MM-dd'), htmlBody: html });
}
