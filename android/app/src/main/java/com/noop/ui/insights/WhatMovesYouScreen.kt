package com.noop.ui.insights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.DosedBehavior
import com.noop.analytics.RankedEffect
import com.noop.data.MoodStore
import com.noop.ui.AppViewModel
import com.noop.ui.JOURNAL_DEVICE_ID
import com.noop.ui.journal.journalQuestionLabel
import com.noop.ui.loadJournalCatalogItems
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.MetricHue
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.color
import com.noop.ui.mergeJournalEntries
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - What Moves You (twin of iOS InsightsHubView, laid out as Health's Highlights)
//
// One card per finding: the thing on the title row, one sentence, its evidence; a habit or a dose card
// opens its details (in this page, Back returns). ⓘ in the bar says how to read it all: association, not
// cause. With nothing to show yet, the empty state opens the Journal.

/** A finding opened from its card. */
private sealed interface WmyDetail {
    data class Effect(val behavior: String) : WmyDetail
    data class Dose(val id: String) : WmyDetail
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WhatMovesYouScreen(vm: AppViewModel, onBack: () -> Unit, onOpenJournal: () -> Unit) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var snapshot by remember { mutableStateOf(WmySnapshot()) }
    var outcomeIndex by rememberSaveable { mutableStateOf(0) }
    val outcome = WmyOutcome.entries[outcomeIndex]
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    // Journal keys → the names the Journal shows (renames, translated built-ins).
    val catalog = remember { loadJournalCatalogItems(context) }
    val translate: (Int) -> String = { context.getString(it) }

    LaunchedEffect(days) {
        val imported = runCatching { vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31") }.getOrDefault(emptyList())
        val native = runCatching { vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31") }.getOrDefault(emptyList())
        val doseRows = DosedBehavior.entries.associateWith { b ->
            runCatching {
                vm.repo.metricSeries(WhatMovesYou.DOSE_SOURCE, WhatMovesYou.doseKey(b), "0000-01-01", "9999-12-31")
                    .associate { it.day to it.value }
            }.getOrDefault(emptyMap())
        }
        val mood = runCatching { MoodStore(vm.repo).moodSeries() }.getOrDefault(emptyList())
        snapshot = WhatMovesYou.build(mergeJournalEntries(imported, native), days, doseRows, mood)
    }
    val ranked = remember(snapshot, outcome) { snapshot.rank(outcome) }

    val detail: WmyDetail? = detailKey?.let { k ->
        if (k.startsWith("e:")) WmyDetail.Effect(k.removePrefix("e:")) else WmyDetail.Dose(k.removePrefix("d:"))
    }
    BackHandler(enabled = detail != null) { detailKey = null }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.wmy_association_title)) },
            text = { Text(stringResource(R.string.wmy_association_body)) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.journal_done)) } },
        )
    }

    when (detail) {
        is WmyDetail.Effect -> {
            val r = ranked.firstOrNull { it.behavior == detail.behavior }
            if (r != null) {
                EffectDetail(r, outcome, journalQuestionLabel(r.behavior, catalog, translate), onBack = { detailKey = null })
                return
            }
        }
        is WmyDetail.Dose -> {
            val card = snapshot.doseCards.firstOrNull { it.id == detail.id }
            if (card != null) {
                DoseDetail(card, onBack = { detailKey = null })
                return
            }
        }
        null -> Unit
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.wmy_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.wmy_how_to_read))
                }
            },
        )
        if (!snapshot.loaded) {
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (ranked.isEmpty() && snapshot.doseCards.isEmpty() && snapshot.relationships.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(R.string.wmy_empty_title),
                message = stringResource(R.string.wmy_empty_message),
                action = stringResource(R.string.wmy_open_journal),
                onAction = onOpenJournal,
                modifier = Modifier.padding(top = 60.dp),
            )
            return@Column
        }
        val outcomeLabels = WmyOutcome.entries.map { stringResource(it.labelRes) }
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                top = 8.dp,
                bottom = M3Dimens.bottomBarClearance + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "outcome") {
                // Chips, not a segmented control: "Resting HR" is long in most languages («Пульс в покое»),
                // and a quarter of the width cuts it.
                val outcomeA11y = stringResource(R.string.wmy_outcome)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = outcomeA11y },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    outcomeLabels.forEachIndexed { i, label ->
                        FilterChip(
                            selected = i == outcomeIndex,
                            onClick = { outcomeIndex = i },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }
            if (ranked.isEmpty()) {
                item(key = "habits-empty") {
                    HealthCard {
                        Text(
                            stringResource(R.string.wmy_not_enough_days),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                for (r in ranked) {
                    item(key = "e:" + r.behavior) {
                        HabitCard(r, outcome, journalQuestionLabel(r.behavior, catalog, translate)) {
                            detailKey = "e:" + r.behavior
                        }
                    }
                }
            }
            if (snapshot.doseCards.isNotEmpty()) {
                item(key = "dose-title") { SectionHeader(stringResource(R.string.wmy_alcohol_caffeine)) }
                for (card in snapshot.doseCards) {
                    item(key = "d:" + card.id) { DoseCard(card) { detailKey = "d:" + card.id } }
                }
            }
            if (snapshot.relationships.isNotEmpty()) {
                item(key = "rel-title") { SectionHeader(stringResource(R.string.wmy_metrics)) }
                for (rel in snapshot.relationships) {
                    item(key = "r:" + rel.id) { RelationshipCard(rel) }
                }
            }
            if (snapshot.moodLines.isNotEmpty()) {
                item(key = "mood-title") { SectionHeader(stringResource(R.string.wmy_mood)) }
                for (line in snapshot.moodLines) {
                    item(key = "m:" + line.id) { MoodCard(line) }
                }
            }
        }
    }
}

// MARK: - Copy

/** "~8 %" when the engine gives a relative change, the outcome's own unit otherwise. */
@Composable
private fun magnitude(r: RankedEffect, outcome: WmyOutcome): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val pct = r.effect.pctChange
    if (pct != null) {
        val f = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
        return "~" + f.format(abs(pct) / 100)
    }
    return "~" + figure(abs(r.effect.delta), outcome)
}

/** A figure with its unit, as the outcome prints it ("62%", "48 ms", "52 bpm"). */
@Composable
private fun figure(v: Double, outcome: WmyOutcome): String {
    val n = v.roundToInt()
    return when {
        outcome.isPercent -> "$n%"
        else -> "$n " + stringResource(outcome.unitRes ?: R.string.metric_unit_ms)
    }
}

/** "Charge is ~8 % lower the next day." — whole-sentence variants per direction and lag. */
@Composable
private fun effectSentence(r: RankedEffect, outcome: WmyOutcome): String {
    val name = stringResource(outcome.labelRes)
    val res = WhatMovesYou.effectSentenceRes(r.effect.delta, r.lag)
    return when (res) {
        R.string.wmy_effect_none -> stringResource(res, name)
        R.string.wmy_effect_lower_later, R.string.wmy_effect_higher_later -> stringResource(res, name, magnitude(r, outcome), r.lag)
        else -> stringResource(res, name, magnitude(r, outcome))
    }
}

@Composable
private fun doseSentence(card: WmyDoseCard): String =
    stringResource(
        WhatMovesYou.doseSentenceRes(card.behavior, card.response.perUnit),
        stringResource(WhatMovesYou.engineOutcomeRes(card.response.outcome)),
    )

@Composable
private fun doseTint(card: WmyDoseCard): Color =
    if (card.response.outcome == "HRV") MetricHue.Heart.color else MetricHue.Charge.color

@Composable
private fun outcomeTint(outcome: WmyOutcome): Color = when (outcome) {
    WmyOutcome.Recovery -> MetricHue.Charge.color
    WmyOutcome.Sleep -> MetricHue.Rest.color
    WmyOutcome.Hrv, WmyOutcome.Rhr -> MetricHue.Heart.color
}

// MARK: - Cards

/** A habit finding: the behaviour, the sentence, and Yes / No means with bars. */
@Composable
private fun HabitCard(r: RankedEffect, outcome: WmyOutcome, title: String, onClick: () -> Unit) {
    HealthCard(onClick = onClick, verticalSpacing = 10.dp) {
        CardTitleRow(icon = Icons.Filled.Checklist, title = title, tint = Health.colors.mind)
        Sentence(effectSentence(r, outcome))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Figures(r, outcome)
    }
}

/** With vs without, as the card and the details page both draw it. */
@Composable
private fun Figures(r: RankedEffect, outcome: WmyOutcome) {
    val e = r.effect
    val tint = outcomeTint(outcome)
    val top = maxOf(e.meanWith, e.meanWithout, 1e-6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FigureBar(stringResource(R.string.wmy_yes), figure(e.meanWith, outcome), (e.meanWith / top).toFloat(), tint, tint)
        FigureBar(
            stringResource(R.string.wmy_no), figure(e.meanWithout, outcome), (e.meanWithout / top).toFloat(),
            MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun FigureBar(title: String, value: String, fraction: Float, textColor: Color, barColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = textColor,
                modifier = Modifier.widthIn(min = 84.dp),
                maxLines = 1,
            )
            Box(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
        }
    }
}

@Composable
private fun Sentence(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun DoseCard(card: WmyDoseCard, onClick: () -> Unit) {
    val tint = doseTint(card)
    HealthCard(onClick = onClick, verticalSpacing = 8.dp) {
        CardTitleRow(
            icon = if (card.behavior == DosedBehavior.ALCOHOL) Icons.Filled.LocalBar else Icons.Filled.Coffee,
            title = stringResource(if (card.behavior == DosedBehavior.ALCOHOL) R.string.wmy_alcohol else R.string.wmy_caffeine),
            tint = tint,
        )
        Sentence(doseSentence(card))
        DoseCurveChart(card, tint, Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp))
    }
}

@Composable
private fun RelationshipCard(rel: WmyRelationship) {
    val tint = MetricHue.Charge.color
    HealthCard(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalSpacing = 8.dp) {
        CardTitleRow(icon = Icons.AutoMirrored.Filled.CompareArrows, title = stringResource(rel.titleRes), tint = tint, chevron = false)
        Sentence(stringResource(WhatMovesYou.relationshipSentenceRes(rel.r)))
        RBar(rel.r, tint, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun MoodCard(line: WmyMoodLine) {
    val tint = Health.colors.mind
    val metric = stringResource(line.metricRes)
    val title = metric.replaceFirstChar { it.titlecase() }
    HealthCard(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalSpacing = 8.dp) {
        CardTitleRow(icon = Icons.Outlined.EmojiEmotions, title = title, tint = tint, chevron = false)
        Sentence(stringResource(if (line.r > 0) R.string.wmy_mood_better else R.string.wmy_mood_lower, metric))
        RBar(line.r, tint, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

// MARK: - Small charts

/** A correlation as a centred bar: zero in the middle, filling right (together) or left (inverse) by |r|. */
@Composable
private fun RBar(r: Double, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val mid = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.height(8.dp).clearAndSetSemantics {}) {
        val h = size.height
        val half = size.width / 2
        val mag = (minOf(abs(r), 1.0) * half).toFloat().coerceAtLeast(4.dp.toPx())
        drawRoundRect(track, cornerRadius = CornerRadius(h / 2, h / 2))
        val left = if (r >= 0) half else half - mag
        drawRoundRect(color, topLeft = Offset(left, 0f), size = Size(mag, h), cornerRadius = CornerRadius(h / 2, h / 2))
        drawRect(mid, topLeft = Offset(half - 0.75.dp.toPx(), 0f), size = Size(1.5.dp.toPx(), h))
    }
}

/** The prior-shrunk dose curve: dose along, the modelled change up, around a dashed zero line. */
@Composable
private fun DoseCurveChart(card: WmyDoseCard, accent: Color, modifier: Modifier = Modifier) {
    val zero = MaterialTheme.colorScheme.outline
    val hole = MaterialTheme.colorScheme.surfaceContainerLow
    val points = card.response.curve
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val maxAbs = maxOf(1.0, points.maxOfOrNull { abs(it.outcomeDelta) } ?: 1.0)
        fun y(d: Double) = (h - ((d / maxAbs + 1) / 2) * h).toFloat()
        val n = maxOf(1, points.size - 1)
        fun x(i: Int) = i.toFloat() / n * w
        drawLine(zero, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        if (points.size > 1) {
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.outcomeDelta)) else path.lineTo(x(i), y(p.outcomeDelta)) }
            drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEachIndexed { i, p ->
            val c = Offset(x(i).coerceIn(4.dp.toPx(), w - 4.dp.toPx()), y(p.outcomeDelta).coerceIn(4.dp.toPx(), h - 4.dp.toPx()))
            drawCircle(hole, radius = 4.dp.toPx(), center = c)
            drawCircle(accent, radius = 4.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

// MARK: - Details

/** One habit finding: the sentence and figures from its card, then how the reading was made. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EffectDetail(r: RankedEffect, outcome: WmyOutcome, title: String, onBack: () -> Unit) {
    val e = r.effect
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = title, onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item {
                HealthCard(verticalSpacing = 12.dp) {
                    Sentence(effectSentence(r, outcome))
                    Figures(r, outcome)
                }
            }
            item {
                val lag = when (r.lag) {
                    0 -> stringResource(R.string.wmy_lag_same_day)
                    1 -> stringResource(R.string.wmy_lag_next_day)
                    else -> stringResource(R.string.wmy_lag_days_later, r.lag)
                }
                val rows = listOf(
                    stringResource(R.string.wmy_days_with) to "${e.nWith}",
                    stringResource(R.string.wmy_days_without) to "${e.nWithout}",
                    stringResource(R.string.wmy_shows_up) to lag,
                    stringResource(R.string.wmy_effect_size) to stringResource(WhatMovesYou.effectSizeRes(e.cohensD)),
                    stringResource(R.string.wmy_confidence) to stringResource(WhatMovesYou.confidenceRes(r.confidence)),
                )
                ValueRows(rows)
            }
        }
    }
}

/** Alcohol or caffeine: the personal curve and how sure the reading is. No forecast. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DoseDetail(card: WmyDoseCard, onBack: () -> Unit) {
    val tint = doseTint(card)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(if (card.behavior == DosedBehavior.ALCOHOL) R.string.wmy_alcohol else R.string.wmy_caffeine),
            onBack = onBack,
        )
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item {
                HealthCard(verticalSpacing = 12.dp) {
                    Sentence(doseSentence(card))
                    DoseCurveChart(card, tint, Modifier.fillMaxWidth().height(120.dp))
                }
            }
            if (card.timingProxy) {
                item {
                    Text(
                        stringResource(R.string.wmy_dose_timing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item {
                ValueRows(listOf(stringResource(R.string.wmy_confidence) to stringResource(WhatMovesYou.confidenceRes(card.response.confidence))))
            }
        }
    }
}

/** Label / value rows in one segmented list group. */
@Composable
private fun ValueRows(rows: List<Pair<String, String>>) {
    ListGroup {
        for ((label, value) in rows) {
            item { shape ->
                ListRow(
                    shape = shape,
                    title = label,
                    trailing = {
                        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}
