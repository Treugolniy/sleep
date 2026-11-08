package com.example.sleepjournal

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Bg = Color(0xFF0D0F14)
private val SurfaceColor = Color(0xFF171A22)
private val SurfaceRaised = Color(0xFF202431)
private val Accent = Color(0xFFB8A7FF)
private val TextPrimary = Color(0xFFF4F2FA)
private val TextSecondary = Color(0xFFA6A9B7)
private val Green = Color(0xFF86D6B0)
private val Orange = Color(0xFFFFC783)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = SleepDatabase(this)
        setContent {
            SleepJournalApp(database = database)
        }
    }
}

@Composable
private fun SleepJournalApp(database: SleepDatabase) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(database.getAll()) }
    var activeStartedAt by remember { mutableLongStateOf(SleepPrefs.startedAt(context)) }
    var activeNightDate by remember { mutableStateOf(SleepPrefs.nightDate(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var month by remember { mutableStateOf(YearMonth.now()) }

    val refresh = {
        entries = database.getAll()
        activeStartedAt = SleepPrefs.startedAt(context)
        activeNightDate = SleepPrefs.nightDate(context)
    }

    LaunchedEffect(activeStartedAt) {
        while (activeStartedAt > 0L) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    SleepTrackerService.ACTION_SLEEP_ENDED -> refresh()
                    SleepTrackerService.ACTION_SLEEP_RESTARTED -> {
                        refresh()
                        val activeDate = SleepPrefs.nightDate(context ?: return)
                        if (activeDate != null) {
                            selectedDate = LocalDate.parse(activeDate)
                            month = YearMonth.from(selectedDate)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(SleepTrackerService.ACTION_SLEEP_ENDED).apply {
            addAction(SleepTrackerService.ACTION_SLEEP_RESTARTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    XlsxExporter.write(entries, output)
                } ?: error("Не удалось открыть файл для записи")
                Toast.makeText(context, "Excel-файл сохранён", Toast.LENGTH_LONG).show()
            } catch (exception: Exception) {
                Toast.makeText(context, "Ошибка экспорта: ${exception.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val startSleep = {
        val startedAt = System.currentTimeMillis()
        val date = LocalDate.now().toString()
        SleepPrefs.start(context, startedAt, date)
        database.startNight(date, startedAt)
        val intent = Intent(context, SleepTrackerService::class.java).apply {
            action = SleepTrackerService.ACTION_START
            putExtra(SleepTrackerService.EXTRA_STARTED_AT, startedAt)
            putExtra(SleepTrackerService.EXTRA_NIGHT_DATE, date)
        }
        ContextCompat.startForegroundService(context, intent)
        selectedDate = LocalDate.now()
        month = YearMonth.now()
        refresh()
    }

    val stopSleep = {
        val date = SleepPrefs.nightDate(context)
        if (date != null && SleepPrefs.startedAt(context) > 0L) {
            database.finishNight(date, System.currentTimeMillis())
        }
        SleepPrefs.clear(context)
        try {
            context.startService(
                Intent(context, SleepTrackerService::class.java).setAction(SleepTrackerService.ACTION_STOP)
            )
        } catch (_: Exception) {
        }
        refresh()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF171326),
        secondary = Green,
        background = Bg,
        surface = SurfaceColor,
        onSurface = TextPrimary,
        onBackground = TextPrimary
    )) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = SurfaceColor, contentColor = TextPrimary) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        label = { Text("Календарь") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                        label = { Text("Анализ") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Настройки") }
                    )
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> CalendarScreen(
                    modifier = Modifier.padding(padding),
                    entries = entries,
                    activeStartedAt = activeStartedAt,
                    activeNightDate = activeNightDate,
                    now = now,
                    selectedDate = selectedDate,
                    month = month,
                    onDateSelected = { selectedDate = it },
                    onMonthChanged = { month = it },
                    onStartSleep = startSleep,
                    onStopSleep = stopSleep,
                    onAnswersSaved = refresh,
                    database = database
                )
                1 -> AnalyticsScreen(Modifier.padding(padding), entries)
                else -> SettingsScreen(
                    modifier = Modifier.padding(padding),
                    onExport = { exportLauncher.launch("sleep-journal-${LocalDate.now()}.xlsx") }
                )
            }
        }
    }
}

@Composable
private fun CalendarScreen(
    modifier: Modifier,
    entries: List<SleepEntry>,
    activeStartedAt: Long,
    activeNightDate: String?,
    now: Long,
    selectedDate: LocalDate,
    month: YearMonth,
    onDateSelected: (LocalDate) -> Unit,
    onMonthChanged: (YearMonth) -> Unit,
    onStartSleep: () -> Unit,
    onStopSleep: () -> Unit,
    onAnswersSaved: () -> Unit,
    database: SleepDatabase
) {
    val entryByDate = remember(entries) { entries.associateBy { it.nightDate } }
    val selectedEntry = entryByDate[selectedDate.toString()]
    val active = activeStartedAt > 0L

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Мой сон", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Каждая ночь — шаг к более понятному режиму", color = TextSecondary, fontSize = 14.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.uppercase() }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Row {
                        Icon(Icons.Default.ChevronLeft, null, modifier = Modifier.clip(CircleShape).clickable { onMonthChanged(month.minusMonths(1)) }.padding(6.dp), tint = TextSecondary)
                        Icon(Icons.Default.ChevronRight, null, modifier = Modifier.clip(CircleShape).clickable { onMonthChanged(month.plusMonths(1)) }.padding(6.dp), tint = TextSecondary)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС").forEach {
                        Text(it, modifier = Modifier.weight(1f), color = TextSecondary, textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                val firstOffset = month.atDay(1).dayOfWeek.value - 1
                val cellCount = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
                val days = (0 until cellCount).map { index ->
                    val day = index - firstOffset + 1
                    if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
                }
                days.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            if (date == null) {
                                Spacer(Modifier.weight(1f).aspectRatio(0.9f))
                            } else {
                                val dayEntry = entryByDate[date.toString()]
                                val isActiveDay = active && activeNightDate == date.toString()
                                val isSelected = date == selectedDate
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) Accent.copy(alpha = 0.20f) else SurfaceRaised.copy(alpha = 0.65f))
                                        .clickable { onDateSelected(date) }
                                        .padding(vertical = 7.dp)
                                        .height(42.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(date.dayOfMonth.toString(), fontSize = 13.sp, color = if (isSelected) Accent else TextPrimary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    val duration = dayEntry?.durationMillis
                                    Text(
                                        text = when {
                                            isActiveDay -> "●"
                                            duration != null -> compactDuration(duration)
                                            else -> ""
                                        },
                                        fontSize = 8.sp,
                                        color = if (isActiveDay) Green else Accent,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
                    Text("Записана ночь", color = TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Green))
                    Text("Сон отслеживается", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")).withLocale(Locale("ru"))).replaceFirstChar { it.uppercase() }, color = TextSecondary, fontSize = 13.sp)
                when {
                    active && activeNightDate == selectedDate.toString() -> {
                        Text("Сон записывается", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                        Text(formatDuration(now - activeStartedAt), fontSize = 32.sp, color = Green, fontWeight = FontWeight.Bold)
                        Text("Начало: ${formatClock(activeStartedAt)}. Если разблокируешь телефон раньше 2 часов — начало сбросится на этот момент. После 2 часов следующая разблокировка завершит запись.", color = TextSecondary, fontSize = 12.sp)
                        OutlinedButton(onClick = onStopSleep, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Snooze, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Завершить сон вручную")
                        }
                    }
                    selectedEntry?.endedAt != null -> {
                        Text(formatDuration(selectedEntry.durationMillis ?: 0L), fontSize = 32.sp, color = Accent, fontWeight = FontWeight.Bold)
                        Text("Отбой ${formatClock(selectedEntry.startedAt)} · подъём ${formatClock(selectedEntry.endedAt)}", color = TextSecondary, fontSize = 13.sp)
                        Text(adviceFor(selectedEntry), color = TextPrimary, fontSize = 14.sp)
                        var showQuestionnaire by remember(selectedEntry.nightDate) { mutableStateOf(false) }
                        Button(onClick = { showQuestionnaire = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (selectedEntry.mood == null && selectedEntry.rested == null) "Заполнить вопросы о ночи" else "Изменить ответы")
                        }
                        if (showQuestionnaire) {
                            QuestionnaireDialog(
                                entry = selectedEntry,
                                onDismiss = { showQuestionnaire = false },
                                onSave = { updated ->
                                    database.saveAnswers(updated)
                                    showQuestionnaire = false
                                    onAnswersSaved()
                                }
                            )
                        }
                    }
                    else -> {
                        Text("Ночь пока не записана", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                        Text("Перед тем как отложить телефон, нажми кнопку ниже. Если разблокируешь его в первые 2 часа, отсчёт начнётся заново с момента разблокировки. Первая разблокировка спустя 2 часа или позже завершит запись.", color = TextSecondary, fontSize = 14.sp)
                        Button(
                            onClick = onStartSleep,
                            enabled = !active,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF171326))
                        ) {
                            Icon(Icons.Default.NightsStay, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (active) "Уже идёт другая запись" else "Я ложусь спать")
                        }
                    }
                }
            }
        }
        Text("Подсказка: для автоостановки включи блокировку экрана. Событие срабатывает после снятия экрана блокировки, а не только после ввода пароля.", color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun QuestionnaireDialog(
    entry: SleepEntry,
    onDismiss: () -> Unit,
    onSave: (SleepEntry) -> Unit
) {
    var mood by remember(entry.nightDate) { mutableIntStateOf(entry.mood ?: 3) }
    var energy by remember(entry.nightDate) { mutableIntStateOf(entry.energy ?: 3) }
    var stress by remember(entry.nightDate) { mutableIntStateOf(entry.stress ?: 3) }
    var rested by remember(entry.nightDate) { mutableStateOf(entry.rested) }
    var awakenings by remember(entry.nightDate) { mutableIntStateOf(entry.awakenings ?: 0) }
    var caffeineLate by remember(entry.nightDate) { mutableStateOf(entry.caffeineLate) }
    var nap by remember(entry.nightDate) { mutableStateOf(entry.nap) }
    var note by remember(entry.nightDate) { mutableStateOf(entry.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = { Text("Как прошла ночь?", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().height(500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                ScaleQuestion("Самочувствие после пробуждения", mood, { mood = it }, listOf("Плохо", "Так себе", "Нормально", "Хорошо", "Отлично"))
                BooleanQuestion("Выспался?", rested, { rested = it })
                ScaleQuestion("Уровень энергии утром", energy, { energy = it }, listOf("Нет сил", "Низкий", "Средний", "Хороший", "Много сил"))
                ScaleQuestion("Стресс перед сном", stress, { stress = it }, listOf("Нет", "Низкий", "Умеренный", "Высокий", "Очень высокий"))
                ChoiceQuestion("Сколько раз просыпался ночью?", awakenings, { awakenings = it }, listOf("0", "1", "2", "3", "4+"))
                BooleanQuestion("Кофеин за 6 часов до сна?", caffeineLate, { caffeineLate = it })
                BooleanQuestion("Был дневной сон?", nap, { nap = it })
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Заметки (сны, самочувствие, причины пробуждений…)") },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(entry.copy(
                    mood = mood,
                    rested = rested,
                    energy = energy,
                    stress = stress,
                    awakenings = awakenings,
                    caffeineLate = caffeineLate,
                    nap = nap,
                    note = note.trim()
                ))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ScaleQuestion(title: String, value: Int, onChange: (Int) -> Unit, labels: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            (1..5).forEach { number ->
                FilterChip(
                    selected = value == number,
                    onClick = { onChange(number) },
                    label = { Text(number.toString(), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(labels[(value - 1).coerceIn(0, 4)], color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun BooleanQuestion(title: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Да" to true, "Нет" to false, "Не знаю" to null).forEach { (label, answer) ->
                FilterChip(selected = value == answer, onClick = { onChange(answer) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
    }
}

@Composable
private fun ChoiceQuestion(title: String, value: Int, onChange: (Int) -> Unit, labels: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            labels.forEachIndexed { index, label ->
                FilterChip(selected = value == index, onClick = { onChange(index) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
    }
}

@Composable
private fun AnalyticsScreen(modifier: Modifier, entries: List<SleepEntry>) {
    val complete = remember(entries) { entries.filter { it.endedAt != null }.sortedBy { it.startedAt } }
    val durations = complete.mapNotNull { it.durationMillis }
    val average = durations.takeIf { it.isNotEmpty() }?.average()?.div(60_000.0)?.roundToInt()
    val goodNights = complete.filter { it.rested == true && (it.mood ?: 0) >= 4 }
    val restedNights = complete.filter { it.rested == true }
    val personalSamples = when {
        goodNights.size >= 3 -> goodNights
        restedNights.size >= 3 -> restedNights
        else -> emptyList()
    }
    val personalTarget = personalSamples.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: (8 * 60 * 60 * 1000)
    val bestForRhythm = if (goodNights.size >= 3) goodNights else restedNights
    val usualBedtime = bestForRhythm.map { it.startedAt }.takeIf { it.size >= 3 }?.let(::averageClockTime)
    val usualWake = bestForRhythm.mapNotNull { it.endedAt }.takeIf { it.size >= 3 }?.let(::averageClockTime)
    val restedPercent = if (complete.isNotEmpty()) (restedNights.size * 100f / complete.size).roundToInt() else 0

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Анализ сна", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Выводы будут точнее, когда накопится больше ночей и ответов.", color = TextSecondary, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Ночей", complete.size.toString(), Modifier.weight(1f))
            MetricCard("Среднее", average?.let(::formatMinutes) ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Выспался", if (complete.isEmpty()) "—" else "$restedPercent%", Modifier.weight(1f))
            MetricCard("Личный ориентир", formatDuration(personalTarget.toLong()), Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Твой режим", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                if (usualBedtime != null && usualWake != null) {
                    InfoLine("Отбой в удачные ночи", formatClockMinutes(usualBedtime))
                    InfoLine("Подъём в удачные ночи", formatClockMinutes(usualWake))
                    Text("Это среднее по ночам, когда ты отмечал хорошее самочувствие и/или что выспался. Это наблюдаемый режим, а не медицинское определение хронотипа.", color = TextSecondary, fontSize = 12.sp)
                } else {
                    Text("Для оценки привычного времени отбоя и подъёма нужно хотя бы 3 ночи с ответами о самочувствии.", color = TextSecondary, fontSize = 14.sp)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Персональная продолжительность", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    if (personalSamples.size >= 3) {
                        "Средняя продолжительность по ${personalSamples.size} ночам, когда ты отметил, что выспался${if (goodNights.size >= 3) " и оценил самочувствие как хорошее" else ""}, составила ${formatDuration(personalTarget.toLong())}. Приложение использует её как личный ориентир и будет пересчитывать при новых ответах."
                    } else {
                        "Пока нет 3 ночей с положительной оценкой. Временно показан ориентир 8 часов; это не персональная медицинская норма и потребность во сне зависит, в том числе, от возраста. Заполни ответы за несколько ночей, чтобы приложение могло сравнить их."
                    },
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                if (complete.isNotEmpty()) {
                    val averageMood = complete.mapNotNull { it.mood }.takeIf { it.isNotEmpty() }?.average()
                    val averageEnergy = complete.mapNotNull { it.energy }.takeIf { it.isNotEmpty() }?.average()
                    if (averageMood != null) InfoLine("Среднее самочувствие", "${String.format(Locale("ru"), "%.1f", averageMood)} / 5")
                    if (averageEnergy != null) InfoLine("Средняя энергия", "${String.format(Locale("ru"), "%.1f", averageEnergy)} / 5")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Как улучшать точность", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Отмечай самочувствие после каждой ночи и старайся не пропускать дни. Через 2–3 недели будет проще увидеть связь продолжительности сна, времени отбоя, кофеина, дневного сна и утренней энергии.", color = TextSecondary, fontSize = 14.sp)
                Text("Аналитика описывает твои записи и не заменяет консультацию специалиста.", color = Orange, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text(value, color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, onExport: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileDownload, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Экспорт данных", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Сохрани дневник и ответы в настоящую книгу Excel (.xlsx). Внутри будут отдельные листы «Дневник» и «Анализ».", color = TextSecondary, fontSize = 14.sp)
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FileDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Экспортировать в Excel")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Как работает автоотслеживание", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("После нажатия «Я ложусь спать» приложение запускает видимый системный сервис. Когда Android сообщает, что экран блокировки снят, таймер останавливается. Ввод пароля отдельно не определяется: событие может прийти после PIN-кода, пароля, отпечатка, лица или простого свайпа.", color = TextSecondary, fontSize = 13.sp)
                Text("Для стабильной работы не отключай уведомление отслеживания сна и разреши уведомления приложению.", color = TextSecondary, fontSize = 13.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Конфиденциальность", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("В этой версии данные сохраняются локально в памяти приложения. Сетевой отправки данных нет. Удаление приложения удалит локальный дневник, поэтому периодически экспортируй Excel-файл.", color = TextSecondary, fontSize = 13.sp)
            }
        }
        Text("Дневник сна · версия 1.1", color = TextSecondary, fontSize = 11.sp)
    }
}

private fun adviceFor(entry: SleepEntry): String {
    val hours = (entry.durationMillis ?: 0L) / 3_600_000.0
    val base = when {
        hours < 6.0 -> "Эта ночь получилась короткой. Сегодня попробуй выделить на сон больше времени и понаблюдай, изменится ли утреннее самочувствие."
        hours < 7.0 -> "Сна было меньше 7 часов. Посмотри, совпадает ли это с низкой энергией или ощущением, что не выспался."
        hours <= 9.0 -> "Продолжительность попадает в типичный диапазон 7–9 часов для взрослых. Сравни её со своим самочувствием и привычным режимом."
        else -> "Ночь была длинной. Иногда это бывает после недосыпа; оцени, стало ли тебе легче проснуться и чувствовать себя бодрее."
    }
    val personal = when {
        entry.rested == true -> " Ты отметил, что выспался — сохраним эту ночь как положительный пример."
        entry.rested == false -> " Ты отметил, что не выспался; полезно посмотреть на стресс, пробуждения и время отбоя."
        else -> " Заполни вопросы ниже, чтобы учитывать эту ночь в персональном анализе."
    }
    return base + personal
}

private fun formatDuration(milliseconds: Long): String {
    val totalMinutes = (milliseconds.coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours} ч ${minutes.toString().padStart(2, '0')} мин"
}

private fun compactDuration(milliseconds: Long): String {
    val minutes = (milliseconds.coerceAtLeast(0L) / 60_000L)
    return "${minutes / 60}ч${(minutes % 60).toString().padStart(2, '0')}"
}

private fun formatMinutes(minutes: Int): String = "${minutes / 60} ч ${minutes % 60} мин"

private fun formatClock(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun averageClockTime(timestamps: List<Long>): Int {
    val values = timestamps.map { timestamp ->
        val local = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        local.hour * 60 + local.minute
    }
    val x = values.sumOf { cos(it * 2.0 * Math.PI / 1440.0) }
    val y = values.sumOf { sin(it * 2.0 * Math.PI / 1440.0) }
    val angle = atan2(y, x)
    return (((angle / (2.0 * Math.PI) * 1440.0).roundToInt() % 1440) + 1440) % 1440
}

private fun formatClockMinutes(minutes: Int): String =
    "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
