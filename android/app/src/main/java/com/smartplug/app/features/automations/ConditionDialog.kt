package com.smartplug.app.features.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.Comparison
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.Consuming
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.DistanceFromLocation
import com.smartplug.app.domain.model.EnteringLocation
import com.smartplug.app.domain.model.ExitingLocation
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.OutsideLocation
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.SpecificDate
import com.smartplug.app.domain.model.StateChanged
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBefore
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.TimeEquals
import com.smartplug.app.ui.components.DropdownField
import com.smartplug.app.ui.components.TimeField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val weekdayLabels = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")

/**
 * Cria/edita uma condição: escolha da categoria e do tipo, valores e tempo de permanência.
 * [initial] nulo = nova condição.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConditionDialog(
    initial: Condition?,
    initialHoldSeconds: Int,
    locations: List<SavedLocation>,
    onConfirm: (Condition, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(initial?.kind()?.category ?: ConditionCategory.TIME) }
    var condition by remember { mutableStateOf(initial ?: ConditionKind.TIME_AFTER.default(locations)) }
    var holdMinutes by remember { mutableStateOf(if (initialHoldSeconds > 0) (initialHoldSeconds / 60).toString() else "") }
    val kind = condition.kind()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Adicionar condição" else "Editar condição") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConditionCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = {
                                category = c
                                if (condition.kind().category != c) {
                                    condition = ConditionKind.entries.first { it.category == c }.default(locations)
                                }
                            },
                            label = { Text(c.label) },
                        )
                    }
                }

                ConditionKind.entries.filter { it.category == category }.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = kind == option,
                                role = Role.RadioButton,
                                onClick = { condition = option.default(locations) },
                            ),
                    ) {
                        RadioButton(selected = kind == option, onClick = null)
                        Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                    }
                }

                ConditionFields(condition, locations) { condition = it }

                if (!kind.isEvent) {
                    OutlinedTextField(
                        value = holdMinutes,
                        onValueChange = { holdMinutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Permanência mínima (minutos)") },
                        placeholder = { Text("0") },
                        supportingText = { Text("Vazio ou 0 = vale assim que a condição for verdadeira") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Esta condição é um evento: vale apenas no instante em que acontece.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hold = if (kind.isEvent) 0 else (holdMinutes.toIntOrNull() ?: 0) * 60
                onConfirm(condition, hold)
            }) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionFields(condition: Condition, locations: List<SavedLocation>, onChange: (Condition) -> Unit) {
    when (condition) {
        is TimeAfter -> TimeField("Depois de", condition.minuteOfDay) { onChange(TimeAfter(it)) }
        is TimeBefore -> TimeField("Antes de", condition.minuteOfDay) { onChange(TimeBefore(it)) }
        is TimeBetween -> {
            TimeField("De", condition.fromMinute) { onChange(condition.copy(fromMinute = it)) }
            TimeField("Até", condition.toMinute) { onChange(condition.copy(toMinute = it)) }
            Text("Se o fim for menor que o início, o intervalo atravessa a meia-noite.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is TimeEquals -> {
            TimeField("Às", condition.minuteOfDay) { onChange(condition.copy(minuteOfDay = it)) }
            var tolerance by remember(condition.toleranceMinutes) { mutableStateOf(condition.toleranceMinutes.toString()) }
            OutlinedTextField(
                value = tolerance,
                onValueChange = {
                    tolerance = it.filter(Char::isDigit).take(3)
                    onChange(condition.copy(toleranceMinutes = tolerance.toIntOrNull()?.coerceAtLeast(1) ?: 15))
                },
                label = { Text("Janela de tolerância (min)") },
                supportingText = { Text("O Android verifica em intervalos; a condição vale durante esta janela a partir do horário.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is DaysOfWeek -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..4).forEach { d -> DayChip(d, condition, onChange) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (5..7).forEach { d -> DayChip(d, condition, onChange) }
            }
        }
        is SpecificDate -> {
            var open by remember { mutableStateOf(false) }
            val date = LocalDate.ofEpochDay(condition.epochDay)
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Data: %02d/%02d/%d".format(date.dayOfMonth, date.monthValue, date.year))
            }
            if (open) {
                val state = rememberDatePickerState(
                    initialSelectedDateMillis = condition.epochDay * 86_400_000L,
                )
                DatePickerDialog(
                    onDismissRequest = { open = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let {
                                onChange(SpecificDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()))
                            }
                            open = false
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
                ) { DatePicker(state = state) }
            }
        }
        is InsideLocation -> LocationPicker(locations, condition.locationId) { onChange(InsideLocation(it)) }
        is OutsideLocation -> LocationPicker(locations, condition.locationId) { onChange(OutsideLocation(it)) }
        is EnteringLocation -> LocationPicker(locations, condition.locationId) { onChange(EnteringLocation(it)) }
        is ExitingLocation -> LocationPicker(locations, condition.locationId) { onChange(ExitingLocation(it)) }
        is DistanceFromLocation -> {
            LocationPicker(locations, condition.locationId) { onChange(condition.copy(locationId = it)) }
            DropdownField("Comparação", Comparison.entries.toList(), condition.comparison, { it.label.replaceFirstChar(Char::uppercase) }) {
                onChange(condition.copy(comparison = it))
            }
            NumberField("Distância (metros)", condition.meters) { onChange(condition.copy(meters = it)) }
        }
        is MetricCompare -> {
            DropdownField("Grandeza", Metric.entries.toList(), condition.metric, { "${it.label} (${it.unit})" }) {
                onChange(condition.copy(metric = it))
            }
            DropdownField("Comparação", Comparison.entries.toList(), condition.comparison, { it.label.replaceFirstChar(Char::uppercase) }) {
                onChange(condition.copy(comparison = it))
            }
            NumberField("Valor (${condition.metric.unit})", condition.threshold) { onChange(condition.copy(threshold = it)) }
        }
        is Consuming -> NumberField("Considerar consumo a partir de (W)", condition.thresholdWatts) { onChange(Consuming(it)) }
        is NotConsuming -> NumberField("Considerar sem consumo abaixo de (W)", condition.thresholdWatts) { onChange(NotConsuming(it)) }
        is StateChanged -> DropdownField(
            "Mudança",
            listOf<Boolean?>(null, true, false),
            condition.to,
            { when (it) { true -> "Passou a ligado"; false -> "Passou a desligado"; null -> "Qualquer mudança" } },
        ) { onChange(StateChanged(it)) }
        else -> Unit
    }
}

@Composable
private fun DayChip(day: Int, condition: DaysOfWeek, onChange: (Condition) -> Unit) {
    FilterChip(
        selected = day in condition.days,
        onClick = {
            val next = if (day in condition.days) condition.days - day else condition.days + day
            onChange(DaysOfWeek(next))
        },
        label = { Text(weekdayLabels[day - 1]) },
    )
}

@Composable
private fun LocationPicker(locations: List<SavedLocation>, selectedId: Long, onSelect: (Long) -> Unit) {
    if (locations.isEmpty()) {
        Text(
            "Cadastre um local primeiro (aba Locais) para usar esta condição.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    DropdownField(
        label = "Local",
        options = locations,
        selected = locations.firstOrNull { it.id == selectedId },
        display = { it.name },
        onSelect = { onSelect(it.id) },
    )
}

@Composable
private fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(label) { mutableStateOf(ConditionText.number(value).replace(',', '.')) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.replace(',', '.')
            text.toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}
