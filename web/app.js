(() => {
  'use strict';

  const DB_NAME = 'sleep-journal-hybrid';
  const DB_VERSION = 1;
  const HOUR = 60 * 60 * 1000;
  const TWO_HOURS = 2 * HOUR;
  const ruDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
  const ruMonth = new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric' });
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  let db;
  let entries = [];
  let selectedDate = dateKey(new Date());
  let shownMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
  let activeSession = null;
  let toastTimer = null;
  let deferredInstallPrompt = null;
  let nativeSyncBusy = false;
  let sessionActionBusy = false;
  let lastNativePoll = 0;

  function dateKey(date) {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  function dateFromKey(key) {
    const [year, month, day] = key.split('-').map(Number);
    return new Date(year, month - 1, day, 12, 0, 0, 0);
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (char) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[char]);
  }

  function openDatabase() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const database = request.result;
        if (!database.objectStoreNames.contains('entries')) {
          const store = database.createObjectStore('entries', { keyPath: 'id', autoIncrement: true });
          store.createIndex('nightDate', 'nightDate', { unique: false });
          store.createIndex('startedAt', 'startedAt', { unique: false });
        }
        if (!database.objectStoreNames.contains('meta')) database.createObjectStore('meta', { keyPath: 'key' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('Не удалось открыть локальную базу данных'));
      request.onblocked = () => reject(new Error('Закрой другие вкладки дневника и обнови страницу'));
    });
  }

  function dbRequest(storeName, mode, callback) {
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeName, mode);
      let result;
      const request = callback(tx.objectStore(storeName));
      request.onsuccess = () => { result = request.result; };
      request.onerror = () => reject(request.error || tx.error || new Error('Ошибка локальной базы'));
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error || request.error || new Error('Ошибка локальной базы'));
      tx.onabort = () => reject(tx.error || new Error('Транзакция отменена'));
    });
  }

  const getAllEntries = () => dbRequest('entries', 'readonly', (store) => store.getAll());
  const getEntry = (id) => dbRequest('entries', 'readonly', (store) => store.get(Number(id)));
  const putEntry = (entry) => dbRequest('entries', 'readwrite', (store) => store.put(entry));
  const addEntry = (entry) => dbRequest('entries', 'readwrite', (store) => store.add(entry));
  const deleteEntry = (id) => dbRequest('entries', 'readwrite', (store) => store.delete(Number(id)));
  const getMeta = async (key) => (await dbRequest('meta', 'readonly', (store) => store.get(key)))?.value ?? null;
  const setMeta = (key, value) => dbRequest('meta', 'readwrite', (store) => store.put({ key, value }));
  const deleteMeta = (key) => dbRequest('meta', 'readwrite', (store) => store.delete(key));

  function showToast(message) {
    const node = $('#toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.remove('show'), 2600);
  }

  function durationMs(entry, now = Date.now()) {
    if (!entry?.startedAt) return 0;
    return Math.max(0, (entry.endedAt ?? now) - entry.startedAt);
  }

  function formatDuration(ms) {
    const mins = Math.floor(Math.max(0, ms) / 60000);
    return `${Math.floor(mins / 60)} ч ${String(mins % 60).padStart(2, '0')} мин`;
  }

  function compactDuration(ms) {
    const mins = Math.floor(Math.max(0, ms) / 60000);
    return `${Math.floor(mins / 60)}ч${String(mins % 60).padStart(2, '0')}`;
  }

  function clock(timestamp) {
    if (!timestamp) return '—';
    return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp));
  }

  function longDate(key) {
    return ruDate.format(dateFromKey(key));
  }

  function formatYesNo(value) {
    return value === true ? 'Да' : value === false ? 'Нет' : 'Не указано';
  }

  function hasAnswers(entry) {
    return entry.mood != null && entry.rested != null && entry.energy != null && entry.stress != null;
  }

  function sortedEntries() {
    return [...entries].sort((a, b) => b.startedAt - a.startedAt);
  }

  async function refreshEntries() {
    entries = await getAllEntries();
    activeSession = await getMeta('activeSession');
    renderAll();
  }

  function showScreen(name) {
    $$('.screen').forEach((screen) => {
      const visible = screen.id === `screen-${name}`;
      screen.hidden = !visible;
      screen.classList.toggle('active', visible);
    });
    $$('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.screen === name));
    if (name === 'analytics') renderAnalytics();
  }

  function renderAll() {
    renderSession();
    renderCalendar();
    renderDayDetail();
    renderAnalytics();
    updateStatus();
  }

  function renderSession() {
    const button = $('#sleep-toggle');
    const title = $('#session-title');
    const label = $('#session-label');
    const description = $('#session-description');
    const timer = $('#session-timer');
    const hint = $('#tracking-hint');
    if (activeSession) {
      const startedAt = Number(activeSession.startedAt);
      const elapsed = durationMs({ startedAt }, Date.now());
      label.textContent = 'ЗАПИСЬ ИДЁТ';
      title.textContent = 'Сладких снов';
      description.textContent = `Начало: ${clock(startedAt)} · ${longDate(activeSession.nightDate)}`;
      timer.hidden = false;
      const seconds = Math.floor(elapsed / 1000);
      const hours = Math.floor(seconds / 3600);
      timer.textContent = `${String(hours).padStart(2, '0')}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
      button.classList.add('stop');
      button.innerHTML = '<span class="button-icon">◼</span><span>Завершить вручную</span>';
      hint.textContent = activeSession.native
        ? 'Автоотслеживание Android включено: до 2 часов событие разблокировки перенесёт начало, позже — завершит ночь.'
        : 'В режиме браузера автоматическая разблокировка недоступна — заверши запись кнопкой выше.';
    } else {
      label.textContent = 'ГОТОВ К НОВОЙ НОЧИ';
      title.textContent = 'Пора отдыхать?';
      description.textContent = 'Нажми кнопку, когда будешь готов лечь спать.';
      timer.hidden = true;
      button.classList.remove('stop');
      button.innerHTML = '<span class="button-icon">☾</span><span>Я ложусь спать</span>';
      hint.textContent = window.SleepNative
        ? 'На Android разблокировка экрана будет учитываться автоматически.'
        : 'В браузере запись можно завершить вручную. Для автофиксации нужен Android-компонент.';
    }
  }

  function renderCalendar() {
    $('#month-label').textContent = ruMonth.format(shownMonth);
    const first = new Date(shownMonth.getFullYear(), shownMonth.getMonth(), 1);
    const lastDay = new Date(shownMonth.getFullYear(), shownMonth.getMonth() + 1, 0).getDate();
    const mondayOffset = (first.getDay() + 6) % 7;
    const totalCells = Math.ceil((mondayOffset + lastDay) / 7) * 7;
    const today = dateKey(new Date());
    const byDate = new Map();
    for (const entry of entries) {
      const group = byDate.get(entry.nightDate) || [];
      group.push(entry);
      byDate.set(entry.nightDate, group);
    }
    const cells = [];
    for (let index = 0; index < totalCells; index += 1) {
      const dayNumber = index - mondayOffset + 1;
      const cellDate = new Date(shownMonth.getFullYear(), shownMonth.getMonth(), dayNumber, 12);
      const key = dateKey(cellDate);
      const currentMonth = cellDate.getMonth() === shownMonth.getMonth();
      const dayEntries = (byDate.get(key) || []).sort((a, b) => b.startedAt - a.startedAt);
      const entry = dayEntries[0];
      const classes = ['day-cell'];
      if (!currentMonth) classes.push('other-month');
      if (key === selectedDate) classes.push('selected');
      if (key === today) classes.push('today');
      if (entry) classes.push('has-entry');
      if (dayEntries.some(hasAnswers)) classes.push('has-answers');
      const detail = entry ? (entry.endedAt ? compactDuration(durationMs(entry)) : 'идёт') : '';
      cells.push(`<button class="${classes.join(' ')}" data-date="${key}" type="button" aria-label="${longDate(key)}${entry ? `, ${detail}` : ', нет записи'}">
        <span class="day-number">${cellDate.getDate()}</span>${detail ? `<span class="day-duration">${detail}</span>` : ''}
      </button>`);
    }
    $('#calendar-grid').innerHTML = cells.join('');
    $$('#calendar-grid [data-date]').forEach((button) => button.addEventListener('click', () => {
      selectedDate = button.dataset.date;
      if (dateFromKey(selectedDate).getMonth() !== shownMonth.getMonth()) {
        shownMonth = new Date(dateFromKey(selectedDate).getFullYear(), dateFromKey(selectedDate).getMonth(), 1);
      }
      renderCalendar();
      renderDayDetail();
    }));
  }

  function scaleOptions(name, selected) {
    return `<div class="scale-row">${[1, 2, 3, 4, 5].map((value) => `<label><input type="radio" name="${name}" value="${value}" ${Number(selected) === value ? 'checked' : ''}><span>${value}</span></label>`).join('')}</div>`;
  }

  function choiceOptions(name, selected) {
    const options = [{ value: 'yes', label: 'Да' }, { value: 'no', label: 'Нет' }, { value: 'skip', label: 'Не знаю' }];
    return `<div class="choice-row">${options.map((item) => `<label><input type="radio" name="${name}" value="${item.value}" ${selected != null && selected === (item.value === 'yes' ? true : item.value === 'no' ? false : null) ? 'checked' : ''}><span>${item.label}</span></label>`).join('')}</div>`;
  }

  function adviceFor(entry) {
    if (!entry.endedAt) return 'Запись этой ночи ещё продолжается. Ответы можно заполнить после пробуждения.';
    const hours = durationMs(entry) / HOUR;
    let text;
    if (hours < 6) text = 'Ночь получилась короткой. Посмотри, совпадает ли это с утренней энергией и самочувствием.';
    else if (hours < 7) text = 'Сна было меньше 7 часов. Сравни эту ночь с теми, после которых ты чувствовал себя лучше.';
    else if (hours <= 9) text = 'Продолжительность попадает в типичный ориентир 7–9 часов для многих взрослых. Важнее сравнить её с твоим самочувствием.';
    else text = 'Ночь была длинной. Это может быть восстановлением после недосыпа — оцени, помогло ли это самочувствию.';
    if (entry.rested === true) text += ' Ты отметил, что выспался: сохраним ночь как положительный пример.';
    else if (entry.rested === false) text += ' Ты отметил, что не выспался: полезно сравнить стресс, пробуждения и время отхода ко сну.';
    return text;
  }

  function renderEntry(entry) {
    const duration = entry.endedAt ? formatDuration(durationMs(entry)) : 'Запись продолжается';
    const endText = entry.endedAt ? clock(entry.endedAt) : '—';
    const mood = entry.mood ?? '';
    const energy = entry.energy ?? '';
    const stress = entry.stress ?? '';
    const awakenings = entry.awakenings ?? '';
    return `<article class="panel entry-card" data-entry-id="${entry.id}">
      <div class="entry-top"><div><div class="eyebrow">НОЧНАЯ ЗАПИСЬ</div><div class="entry-date">${longDate(entry.nightDate)}</div></div><div class="duration-block"><div class="duration-value">${duration}</div><div class="duration-caption">ПРОДОЛЖИТЕЛЬНОСТЬ</div></div></div>
      <div class="time-row"><div class="time-chip"><small>ОТБОЙ</small><strong>${clock(entry.startedAt)}</strong></div><div class="time-chip"><small>ПОДЪЁМ</small><strong>${endText}</strong></div></div>
      <p class="card-subtitle">${escapeHtml(adviceFor(entry))}</p>
      <form class="entry-form" data-entry-id="${entry.id}">
        <h3 class="form-heading">Как прошла ночь?</h3>
        <div class="question-grid">
          <div class="field"><span class="field-label">Самочувствие · 1–5</span>${scaleOptions('mood', mood)}</div>
          <div class="field"><span class="field-label">Выспался?</span>${choiceOptions('rested', entry.rested)}</div>
          <div class="field"><span class="field-label">Энергия · 1–5</span>${scaleOptions('energy', energy)}</div>
          <div class="field"><span class="field-label">Стресс · 1–5</span>${scaleOptions('stress', stress)}</div>
          <div class="field"><label for="awak-${entry.id}">Ночные пробуждения</label><input id="awak-${entry.id}" name="awakenings" type="number" min="0" max="30" step="1" value="${awakenings}" placeholder="Например, 2"></div>
          <div class="field"><span class="field-label">Кофеин за 6 часов до сна?</span>${choiceOptions('caffeineLate', entry.caffeineLate)}</div>
          <div class="field"><span class="field-label">Был дневной сон?</span>${choiceOptions('nap', entry.nap)}</div>
          <div class="field full"><label for="note-${entry.id}">Заметки</label><textarea id="note-${entry.id}" name="note" maxlength="2000" placeholder="Что могло повлиять на сон? ${'Например: поздний ужин, тренировка, тревожные мысли…'}">${escapeHtml(entry.note || '')}</textarea></div>
        </div>
        <div class="save-row"><button class="secondary-button" type="submit">Сохранить ответы</button></div>
      </form>
    </article>`;
  }

  function renderDayDetail() {
    const dayEntries = entries.filter((entry) => entry.nightDate === selectedDate).sort((a, b) => b.startedAt - a.startedAt);
    const container = $('#day-detail');
    if (!dayEntries.length) {
      container.innerHTML = `<div class="panel empty-day"><div class="empty-icon">☾</div><h3>${longDate(selectedDate)}</h3><p>Для этой даты пока нет записи сна. Нажми «Я ложусь спать», когда будешь готов ко сну.</p></div>`;
      return;
    }
    container.innerHTML = `<div class="stack">${dayEntries.map(renderEntry).join('')}</div>`;
    $$('.entry-form', container).forEach((form) => form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const formData = new FormData(form);
      const entry = await getEntry(form.dataset.entryId);
      if (!entry) return;
      entry.mood = formData.get('mood') ? Number(formData.get('mood')) : null;
      entry.energy = formData.get('energy') ? Number(formData.get('energy')) : null;
      entry.stress = formData.get('stress') ? Number(formData.get('stress')) : null;
      entry.rested = parseChoice(formData.get('rested'));
      entry.caffeineLate = parseChoice(formData.get('caffeineLate'));
      entry.nap = parseChoice(formData.get('nap'));
      const awakeningsValue = formData.get('awakenings');
      entry.awakenings = awakeningsValue === '' ? null : Math.min(30, Math.max(0, Number(awakeningsValue) || 0));
      entry.note = String(formData.get('note') || '').trim();
      await putEntry(entry);
      await refreshEntries();
      showToast('Ответы сохранены');
    }));
  }

  function parseChoice(value) {
    if (value === 'yes') return true;
    if (value === 'no') return false;
    return null;
  }

  function mean(values) {
    const numbers = values.filter((value) => Number.isFinite(value));
    return numbers.length ? numbers.reduce((sum, value) => sum + value, 0) / numbers.length : null;
  }

  function averageClock(timestamps) {
    if (!timestamps.length) return null;
    let x = 0;
    let y = 0;
    for (const timestamp of timestamps) {
      const date = new Date(timestamp);
      const minute = date.getHours() * 60 + date.getMinutes();
      const angle = minute * 2 * Math.PI / 1440;
      x += Math.cos(angle);
      y += Math.sin(angle);
    }
    const angle = Math.atan2(y, x);
    return ((Math.round(angle / (2 * Math.PI) * 1440) % 1440) + 1440) % 1440;
  }

  function clockMinute(value) {
    if (value == null) return '—';
    return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
  }

  function renderAnalytics() {
    const complete = entries.filter((entry) => entry.endedAt != null && entry.endedAt >= entry.startedAt);
    const restedNights = complete.filter((entry) => entry.rested === true);
    const avgDuration = mean(complete.map((entry) => durationMs(entry)));
    const target = restedNights.length >= 3 ? mean(restedNights.map((entry) => durationMs(entry))) : 8 * HOUR;
    const restedPercent = complete.length ? Math.round(restedNights.length / complete.length * 100) : null;
    const bedtime = averageClock(restedNights.map((entry) => entry.startedAt));
    const wakeTime = averageClock(restedNights.map((entry) => entry.endedAt));
    const avgAwakenings = mean(complete.map((entry) => entry.awakenings).filter((value) => value != null));
    const caffeineYes = complete.filter((entry) => entry.caffeineLate === true);
    const caffeineNo = complete.filter((entry) => entry.caffeineLate === false);
    const avgFor = (items) => mean(items.map((entry) => durationMs(entry)));
    const recent = complete.slice().sort((a, b) => a.startedAt - b.startedAt).slice(-14);
    const maxDuration = Math.max(9 * HOUR, ...recent.map((entry) => durationMs(entry)));
    const bars = recent.map((entry) => {
      const height = Math.max(2, Math.min(100, durationMs(entry) / maxDuration * 100));
      const label = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'numeric' }).format(new Date(entry.startedAt));
      return `<div class="bar-item"><span class="bar-value">${Math.round(durationMs(entry) / HOUR * 10) / 10}ч</span><div class="bar-track"><div class="bar-fill" style="height:${height}%"></div></div><span class="bar-label">${label}</span></div>`;
    }).join('');
    const positiveText = restedNights.length >= 3
      ? `По ${restedNights.length} ночам, после которых ты отметил, что выспался, личный ориентир составляет <strong>${formatDuration(target)}</strong>. Удачные ночи в среднем начинались в ${clockMinute(bedtime)} и заканчивались в ${clockMinute(wakeTime)}. Это наблюдение по твоему дневнику, а не медицинская норма.`
      : `Пока недостаточно положительных ночей для персонального ориентира. Сейчас показаны 8 часов как временная точка сравнения. Отметь «Выспался?» минимум в трёх ночах, чтобы увидеть свой паттерн.`;
    const caffeineText = caffeineYes.length && caffeineNo.length
      ? `С кофеином поздно: ${formatDuration(avgFor(caffeineYes))} · Без позднего кофеина: ${formatDuration(avgFor(caffeineNo))}. Это простое сравнение, не доказательство причинной связи.`
      : 'Для сравнения влияния позднего кофеина нужно больше ночей с заполненным ответом.';

    $('#analytics-content').innerHTML = `
      <div class="metric-grid">
        <div class="metric-card"><div class="metric-label">Записей сна</div><div class="metric-value">${complete.length}</div><div class="metric-note">завершённых ночей</div></div>
        <div class="metric-card"><div class="metric-label">Среднее</div><div class="metric-value">${avgDuration == null ? '—' : `${(avgDuration / HOUR).toFixed(1).replace('.', ',')}ч`}</div><div class="metric-note">за все завершённые ночи</div></div>
        <div class="metric-card"><div class="metric-label">Выспался</div><div class="metric-value">${restedPercent == null ? '—' : `${restedPercent}%`}</div><div class="metric-note">по твоим ответам</div></div>
        <div class="metric-card"><div class="metric-label">Личный ориентир</div><div class="metric-value">${formatDuration(target)}</div><div class="metric-note">${restedNights.length >= 3 ? 'по удачным ночам' : 'временный ориентир'}</div></div>
      </div>
      <article class="panel chart-card"><h2 class="card-title">Последние ночи</h2><p class="card-subtitle">Продолжительность завершённого сна · максимум 14 записей</p>
        ${bars ? `<div class="bar-chart">${bars}</div>` : '<p class="card-subtitle">График появится после первой завершённой записи.</p>'}
      </article>
      <article class="panel insight-card"><h2>Твой режим</h2><p>${positiveText}</p>
        <div class="insight-list"><div class="insight-row"><span>Среднее время отбоя в удачные ночи</span><strong>${clockMinute(bedtime)}</strong></div><div class="insight-row"><span>Среднее время подъёма в удачные ночи</span><strong>${clockMinute(wakeTime)}</strong></div><div class="insight-row"><span>Среднее число пробуждений</span><strong>${avgAwakenings == null ? '—' : avgAwakenings.toFixed(1).replace('.', ',')}</strong></div></div>
      </article>
      <article class="panel insight-card"><h2>Что может влиять на сон</h2><p>${caffeineText}</p><p style="margin-top:9px">Заполняй анкету регулярно. Через 2–3 недели будет проще заметить связи между режимом, кофеином, дневным сном, стрессом и утренней энергией.</p></article>
      <article class="panel insight-card"><h2>Рекомендация</h2><p>${restedNights.length >= 3 ? `Попробуй несколько дней сохранять время отбоя около ${clockMinute(bedtime)}, если оно подходит твоему расписанию. Сравни утреннее самочувствие после таких ночей.` : 'Начни с регулярного времени подъёма и оставляй достаточно времени для сна. Персональные рекомендации станут полезнее после нескольких заполненных анкет.'}</p><p style="margin-top:9px;color:var(--amber)">Аналитика отражает только сохранённые наблюдения и не является диагнозом или медицинской рекомендацией.</p></article>`;
  }

  function updateStatus() {
    const pill = $('#system-status');
    const label = pill.querySelector('span');
    const dot = pill.querySelector('i');
    if (window.SleepNative) {
      label.textContent = activeSession?.native ? 'Android · автофиксация' : 'Android · подключён';
      dot.style.background = 'var(--mint)';
    } else {
      label.textContent = 'Локальное хранение';
      dot.style.background = 'var(--accent)';
    }
    $('#android-info').textContent = window.SleepNative
      ? 'Android-модуль подключён. Пока идёт запись, система передаёт событие разблокировки: раньше 2 часов начало переносится на момент разблокировки, через 2 часа и позже ночь завершается.'
      : 'Сейчас открыт обычный браузер. Данные хранятся локально, но браузер не передаёт системное событие разблокировки. Для автоматического завершения открой дневник в Android-компоненте.';
  }

  async function startSleep() {
    if (activeSession || sessionActionBusy) return;
    sessionActionBusy = true;
    $('#sleep-toggle').disabled = true;
    try {
      const startedAt = Date.now();
      const nightDate = dateKey(new Date(startedAt));
      const entry = {
        nightDate, startedAt, endedAt: null,
        mood: null, rested: null, energy: null, stress: null,
        awakenings: null, caffeineLate: null, nap: null, note: ''
      };
      const entryId = await addEntry(entry);
      const session = { entryId, startedAt, nightDate, native: Boolean(window.SleepNative) };
      await setMeta('activeSession', session);
      if (window.SleepNative) {
        try {
          const result = JSON.parse(window.SleepNative.startSleep(startedAt, nightDate));
          if (!result.ok) throw new Error(result.error || 'Android не смог запустить автоотслеживание');
        } catch (error) {
          await deleteEntry(entryId);
          await deleteMeta('activeSession');
          activeSession = null;
          renderAll();
          showToast(error.message || 'Не удалось включить автоотслеживание');
          return;
        }
      }
      selectedDate = nightDate;
      shownMonth = new Date(dateFromKey(nightDate).getFullYear(), dateFromKey(nightDate).getMonth(), 1);
      await refreshEntries();
      showToast(window.SleepNative ? 'Запись началась · автоотслеживание включено' : 'Запись началась');
    } finally {
      sessionActionBusy = false;
      $('#sleep-toggle').disabled = false;
    }
  }

  async function stopSleep() {
    if (!activeSession || sessionActionBusy) return;
    sessionActionBusy = true;
    $('#sleep-toggle').disabled = true;
    try {
      if (activeSession.native && window.SleepNative) {
        const result = JSON.parse(window.SleepNative.stopSleep());
        if (!result.ok) throw new Error(result.error || 'Не удалось завершить запись');
        showToast('Команда завершения отправлена Android');
        setTimeout(() => syncNativeEvents().catch(() => {}), 350);
        setTimeout(() => syncNativeEvents().catch(() => {}), 1200);
        return;
      }
      const entry = await getEntry(activeSession.entryId);
      if (entry) {
        entry.endedAt = Date.now();
        await putEntry(entry);
      }
      await deleteMeta('activeSession');
      await refreshEntries();
      showToast('Запись завершена');
    } catch (error) {
      showToast(error.message || 'Не удалось завершить запись');
    } finally {
      sessionActionBusy = false;
      $('#sleep-toggle').disabled = false;
    }
  }

  async function applyNativeEvent(event) {
    let session = await getMeta('activeSession');
    let entry = session?.entryId ? await getEntry(session.entryId) : null;
    if (!entry) {
      const candidates = entries.filter((item) => item.endedAt == null && item.nightDate === (event.previousNightDate || event.nightDate));
      entry = candidates.sort((a, b) => b.startedAt - a.startedAt)[0] || null;
    }
    if (event.type === 'reset') {
      if (!entry) {
        const id = await addEntry({ nightDate: event.nightDate, startedAt: event.startedAt, endedAt: null, mood: null, rested: null, energy: null, stress: null, awakenings: null, caffeineLate: null, nap: null, note: '' });
        entry = await getEntry(id);
      } else {
        entry.nightDate = event.nightDate;
        entry.startedAt = Number(event.startedAt);
        entry.endedAt = null;
        await putEntry(entry);
      }
      await setMeta('activeSession', { entryId: entry.id, startedAt: Number(event.startedAt), nightDate: event.nightDate, native: true });
    } else if (event.type === 'finish') {
      if (entry) {
        entry.endedAt = Number(event.endedAt || event.at || Date.now());
        await putEntry(entry);
      }
      if (session?.native || session?.entryId === entry?.id) await deleteMeta('activeSession');
    }
  }

  async function syncNativeEvents() {
    if (!window.SleepNative || nativeSyncBusy) return;
    nativeSyncBusy = true;
    try {
      const pending = JSON.parse(window.SleepNative.getPendingEvents() || '[]');
      const acknowledged = [];
      for (const event of pending) {
        await applyNativeEvent(event);
        acknowledged.push(event.id);
      }
      if (acknowledged.length) window.SleepNative.acknowledgeEvents(JSON.stringify(acknowledged));

      const nativeState = JSON.parse(window.SleepNative.getTrackingState() || '{}');
      let session = await getMeta('activeSession');
      if (!nativeState.active && session?.native && pending.length === 0 &&
          Date.now() - Number(session.startedAt || 0) > 30000) {
        // If Android stopped the service without a queued completion event, keep the entry recoverable manually.
        session = { ...session, native: false };
        await setMeta('activeSession', session);
      }
      const restoredMissingSession = Boolean(nativeState.active && !session);
      if (nativeState.active && !session) {
        const candidate = entries.filter((entry) => entry.endedAt == null && entry.nightDate === nativeState.nightDate)
          .sort((a, b) => b.startedAt - a.startedAt)[0];
        const entryId = candidate?.id ?? await addEntry({
          nightDate: nativeState.nightDate, startedAt: Number(nativeState.startedAt), endedAt: null,
          mood: null, rested: null, energy: null, stress: null, awakenings: null, caffeineLate: null, nap: null, note: ''
        });
        await setMeta('activeSession', { entryId, startedAt: Number(nativeState.startedAt), nightDate: nativeState.nightDate, native: true });
        session = await getMeta('activeSession');
      }
      if (pending.length || restoredMissingSession) await refreshEntries();
      else {
        activeSession = await getMeta('activeSession');
        renderSession();
        updateStatus();
      }
      lastNativePoll = Date.now();
    } catch (error) {
      console.warn('Android bridge sync failed', error);
    } finally {
      nativeSyncBusy = false;
    }
  }

  async function exportJson() {
    const data = { format: 'sleep-journal-backup', version: 1, exportedAt: new Date().toISOString(), entries: sortedEntries() };
    await saveFile(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), `dnevnik-sna-${dateKey(new Date())}.json`, 'application/json');
  }

  async function importJson(file) {
    const parsed = JSON.parse(await file.text());
    if (parsed?.format !== 'sleep-journal-backup' || !Array.isArray(parsed.entries)) throw new Error('Это не резервная копия дневника сна');
    const allowed = ['nightDate', 'startedAt', 'endedAt', 'mood', 'rested', 'energy', 'stress', 'awakenings', 'caffeineLate', 'nap', 'note'];
    for (const source of parsed.entries) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(source.nightDate) || !Number.isFinite(Number(source.startedAt))) continue;
      const entry = {};
      for (const key of allowed) entry[key] = source[key] ?? (['note'].includes(key) ? '' : null);
      entry.startedAt = Number(source.startedAt);
      if (source.endedAt != null) entry.endedAt = Number(source.endedAt);
      const same = entries.find((item) => item.nightDate === entry.nightDate && item.startedAt === entry.startedAt);
      if (same) entry.id = same.id;
      await putEntry(entry);
    }
    await refreshEntries();
    showToast('Резервная копия импортирована');
  }

  async function saveFile(blob, filename, mime) {
    if (window.SleepNative?.saveFile) {
      const base64 = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
        reader.onerror = () => reject(reader.error || new Error('Не удалось подготовить файл'));
        reader.readAsDataURL(blob);
      });
      const result = JSON.parse(window.SleepNative.saveFile(filename, mime, base64));
      if (!result.ok) throw new Error(result.error || 'Не удалось сохранить файл');
      showToast('Выбери место сохранения файла');
      return;
    }
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
    showToast('Файл подготовлен к скачиванию');
  }

  async function exportXlsx() {
    const currentEntries = sortedEntries();
    const complete = currentEntries.filter((entry) => entry.endedAt != null);
    const avg = mean(complete.map((entry) => durationMs(entry)));
    const rested = complete.filter((entry) => entry.rested === true);
    const personal = rested.length >= 3 ? mean(rested.map((entry) => durationMs(entry))) : 8 * HOUR;
    const workbook = window.SleepXlsx.build(currentEntries, {
      exportedAt: new Date(),
      averageDuration: avg,
      personalTarget: personal,
      completeCount: complete.length,
      restedCount: rested.length
    });
    await saveFile(workbook, `dnevnik-sna-${dateKey(new Date())}.xlsx`, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  }

  function bindEvents() {
    $$('.nav-item').forEach((button) => button.addEventListener('click', () => showScreen(button.dataset.screen)));
    $('#month-prev').addEventListener('click', () => { shownMonth = new Date(shownMonth.getFullYear(), shownMonth.getMonth() - 1, 1); renderCalendar(); });
    $('#month-next').addEventListener('click', () => { shownMonth = new Date(shownMonth.getFullYear(), shownMonth.getMonth() + 1, 1); renderCalendar(); });
    $('#sleep-toggle').addEventListener('click', () => activeSession ? stopSleep() : startSleep().catch((error) => showToast(error.message)));
    $('#export-json').addEventListener('click', () => exportJson().catch((error) => showToast(error.message)));
    $('#export-xlsx').addEventListener('click', () => exportXlsx().catch((error) => showToast(error.message)));
    $('#import-json').addEventListener('change', async (event) => {
      const file = event.target.files?.[0];
      if (!file) return;
      try { await importJson(file); } catch (error) { showToast(error.message || 'Не удалось импортировать файл'); }
      event.target.value = '';
    });
    window.addEventListener('beforeinstallprompt', (event) => {
      event.preventDefault();
      deferredInstallPrompt = event;
      $('#install-button').hidden = false;
    });
    $('#install-button').addEventListener('click', async () => {
      if (!deferredInstallPrompt) return;
      deferredInstallPrompt.prompt();
      await deferredInstallPrompt.userChoice;
      deferredInstallPrompt = null;
      $('#install-button').hidden = true;
    });
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) syncNativeEvents();
    });
    window.addEventListener('focus', () => syncNativeEvents());
  }

  async function init() {
    bindEvents();
    try {
      db = await openDatabase();
      await refreshEntries();
      if (window.SleepNative) await syncNativeEvents();
      if ('serviceWorker' in navigator && !window.SleepNative && (location.protocol === 'https:' || location.hostname === 'localhost')) {
        navigator.serviceWorker.register('./sw.js').catch((error) => console.warn('Service worker registration failed', error));
      }
      setInterval(() => {
        if (activeSession) renderSession();
        if (window.SleepNative && Date.now() - lastNativePoll > 10000) syncNativeEvents();
      }, 1000);
    } catch (error) {
      console.error(error);
      showToast('Не удалось открыть хранилище. Разреши сайту сохранять данные и обнови страницу.');
    }
  }

  init();
})();
