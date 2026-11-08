(function (root) {
  'use strict';
  const encoder = new TextEncoder();
  const MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

  function xml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char]);
  }

  function columnName(index) {
    let name = '';
    let value = index + 1;
    while (value > 0) {
      const remainder = (value - 1) % 26;
      name = String.fromCharCode(65 + remainder) + name;
      value = Math.floor((value - 1) / 26);
    }
    return name;
  }

  function worksheet(rows) {
    const body = rows.map((row, rowIndex) => {
      const cells = row.map((value, columnIndex) => {
        const ref = `${columnName(columnIndex)}${rowIndex + 1}`;
        if (typeof value === 'number' && Number.isFinite(value)) return `<c r="${ref}"><v>${value}</v></c>`;
        if (value == null || value === '') return `<c r="${ref}"/>`;
        return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${xml(value)}</t></is></c>`;
      }).join('');
      return `<row r="${rowIndex + 1}">${cells}</row>`;
    }).join('');
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${body}</sheetData></worksheet>`;
  }

  function crc32(bytes) {
    let crc = 0xffffffff;
    for (let i = 0; i < bytes.length; i += 1) {
      crc ^= bytes[i];
      for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
  }

  function le(size, writer) {
    const buffer = new ArrayBuffer(size);
    const view = new DataView(buffer);
    writer(view);
    return new Uint8Array(buffer);
  }

  function zipStore(files) {
    const localParts = [];
    const centralParts = [];
    let localOffset = 0;
    const now = new Date();
    const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | Math.floor(now.getSeconds() / 2);
    const dosDate = ((Math.max(1980, now.getFullYear()) - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();

    for (const [filename, content] of Object.entries(files)) {
      const name = encoder.encode(filename);
      const data = typeof content === 'string' ? encoder.encode(content) : content;
      const checksum = crc32(data);
      const localHeader = le(30, (view) => {
        view.setUint32(0, 0x04034b50, true); view.setUint16(4, 20, true); view.setUint16(6, 0x0800, true);
        view.setUint16(8, 0, true); view.setUint16(10, dosTime, true); view.setUint16(12, dosDate, true);
        view.setUint32(14, checksum, true); view.setUint32(18, data.length, true); view.setUint32(22, data.length, true);
        view.setUint16(26, name.length, true); view.setUint16(28, 0, true);
      });
      localParts.push(localHeader, name, data);

      const centralHeader = le(46, (view) => {
        view.setUint32(0, 0x02014b50, true); view.setUint16(4, 20, true); view.setUint16(6, 20, true);
        view.setUint16(8, 0x0800, true); view.setUint16(10, 0, true); view.setUint16(12, dosTime, true);
        view.setUint16(14, dosDate, true); view.setUint32(16, checksum, true);
        view.setUint32(20, data.length, true); view.setUint32(24, data.length, true);
        view.setUint16(28, name.length, true); view.setUint16(30, 0, true); view.setUint16(32, 0, true);
        view.setUint16(34, 0, true); view.setUint16(36, 0, true); view.setUint32(38, 0, true);
        view.setUint32(42, localOffset, true);
      });
      centralParts.push(centralHeader, name);
      localOffset += localHeader.length + name.length + data.length;
    }

    const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
    const fileCount = Object.keys(files).length;
    const end = le(22, (view) => {
      view.setUint32(0, 0x06054b50, true); view.setUint16(4, 0, true); view.setUint16(6, 0, true);
      view.setUint16(8, fileCount, true); view.setUint16(10, fileCount, true);
      view.setUint32(12, centralSize, true); view.setUint32(16, localOffset, true); view.setUint16(20, 0, true);
    });
    return new Blob([...localParts, ...centralParts, end], { type: MIME });
  }

  function build(entries, summary = {}) {
    const sorted = [...entries].sort((a, b) => a.startedAt - b.startedAt);
    const rows = [[
      'Дата ночи', 'Отбой', 'Подъём', 'Продолжительность, ч', 'Самочувствие (1–5)', 'Выспался',
      'Энергия (1–5)', 'Стресс (1–5)', 'Пробуждения', 'Поздний кофеин', 'Дневной сон', 'Заметки'
    ]];
    for (const entry of sorted) {
      const duration = entry.endedAt == null ? null : Math.max(0, entry.endedAt - entry.startedAt) / 3600000;
      rows.push([
        entry.nightDate,
        new Date(entry.startedAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }),
        entry.endedAt == null ? '' : new Date(entry.endedAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }),
        duration == null ? '' : Math.round(duration * 100) / 100,
        entry.mood ?? '',
        entry.rested == null ? '' : entry.rested ? 'Да' : 'Нет',
        entry.energy ?? '', entry.stress ?? '', entry.awakenings ?? '',
        entry.caffeineLate == null ? '' : entry.caffeineLate ? 'Да' : 'Нет',
        entry.nap == null ? '' : entry.nap ? 'Да' : 'Нет', entry.note || ''
      ]);
    }
    const analysis = [
      ['Показатель', 'Значение'],
      ['Экспортировано', summary.exportedAt ? summary.exportedAt.toLocaleString('ru-RU') : new Date().toLocaleString('ru-RU')],
      ['Завершённых ночей', summary.completeCount ?? 0],
      ['Средняя продолжительность, ч', summary.averageDuration == null ? '' : Math.round(summary.averageDuration / 3600000 * 100) / 100],
      ['Ночей с ответом «выспался»', summary.restedCount ?? 0],
      ['Личный ориентир, ч', summary.personalTarget == null ? '' : Math.round(summary.personalTarget / 3600000 * 100) / 100],
      ['Примечание', 'Аналитика описывает сохранённые наблюдения и не является медицинской рекомендацией.']
    ];
    return zipStore({
      '[Content_Types].xml': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
      '_rels/.rels': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
      'xl/workbook.xml': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Дневник" sheetId="1" r:id="rId1"/><sheet name="Анализ" sheetId="2" r:id="rId2"/></sheets></workbook>',
      'xl/_rels/workbook.xml.rels': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>',
      'xl/worksheets/sheet1.xml': worksheet(rows),
      'xl/worksheets/sheet2.xml': worksheet(analysis)
    });
  }

  root.SleepXlsx = { build };
  if (typeof module !== 'undefined' && module.exports) module.exports = { build };
})(typeof globalThis !== 'undefined' ? globalThis : window);
