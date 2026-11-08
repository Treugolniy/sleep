package com.example.sleepjournal

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

object XlsxExporter {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun write(entries: List<SleepEntry>, output: OutputStream) {
        val complete = entries.filter { it.endedAt != null }.sortedBy { it.nightDate }
        val avgMinutes = complete.mapNotNull { it.durationMillis?.div(60_000L) }.takeIf { it.isNotEmpty() }
            ?.average()?.roundToInt()
        val rested = complete.filter { it.rested == true }
        val good = complete.filter { it.rested == true && (it.mood ?: 0) >= 4 }
        val personalSamples = when {
            good.size >= 3 -> good
            rested.size >= 3 -> rested
            else -> emptyList()
        }
        val recommended = personalSamples.mapNotNull { it.durationMillis?.div(60_000L) }
            .takeIf { it.isNotEmpty() }?.average()?.roundToInt()

        val logRows = mutableListOf<List<Any?>>()
        logRows += listOf(
            "Дата ночи", "Лёг спать", "Проснулся", "Продолжительность, мин",
            "Самочувствие (1–5)", "Выспался", "Энергия (1–5)", "Стресс (1–5)",
            "Пробуждения за ночь", "Кофеин поздно", "Дневной сон", "Заметки"
        )
        complete.forEach { entry ->
            logRows += listOf(
                entry.nightDate,
                formatDateTime(entry.startedAt),
                entry.endedAt?.let(::formatDateTime) ?: "",
                entry.durationMillis?.div(60_000L) ?: "",
                entry.mood ?: "",
                entry.rested?.let { if (it) "Да" else "Нет" } ?: "",
                entry.energy ?: "",
                entry.stress ?: "",
                entry.awakenings ?: "",
                entry.caffeineLate?.let { if (it) "Да" else "Нет" } ?: "",
                entry.nap?.let { if (it) "Да" else "Нет" } ?: "",
                entry.note
            )
        }

        val summaryRows = listOf(
            listOf("Показатель", "Значение"),
            listOf("Всего завершённых ночей", complete.size),
            listOf("Средняя продолжительность, мин", avgMinutes ?: "Нет данных"),
            listOf("Ночей с ответом «выспался»", rested.size),
            listOf("Ночей с хорошим самочувствием и ответом «выспался»", good.size),
            listOf("Личный ориентир сна, мин", recommended ?: "Пока мало данных: нужно 3+ удачных ночи"),
            listOf("Примечание", "Личный ориентир — среднее по ночам, когда отмечено хорошее самочувствие. Это наблюдение по дневнику, не медицинская рекомендация.")
        )

        ZipOutputStream(output).use { zip ->
            put(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
            """.trimIndent())
            put(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
            """.trimIndent())
            put(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Дневник" sheetId="1" r:id="rId1"/>
                    <sheet name="Анализ" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
            """.trimIndent())
            put(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>
            """.trimIndent())
            put(zip, "xl/worksheets/sheet1.xml", worksheetXml(logRows))
            put(zip, "xl/worksheets/sheet2.xml", worksheetXml(summaryRows))
        }
    }

    private fun formatDateTime(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

    private fun worksheetXml(rows: List<List<Any?>>): String {
        val xmlRows = rows.mapIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            val cells = row.mapIndexedNotNull { colIndex, value ->
                if (value == null || value == "") null else cellXml(columnName(colIndex + 1) + rowNumber, value)
            }.joinToString("")
            "<row r=\"$rowNumber\">$cells</row>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$xmlRows</sheetData>
            </worksheet>""".trimIndent()
    }

    private fun cellXml(reference: String, value: Any): String {
        return when (value) {
            is Byte, is Short, is Int, is Long, is Float, is Double ->
                "<c r=\"$reference\" t=\"n\"><v>$value</v></c>"
            else -> "<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(value.toString())}</t></is></c>"
        }
    }

    private fun columnName(number: Int): String {
        var n = number
        val result = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            result.insert(0, ('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return result.toString()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
