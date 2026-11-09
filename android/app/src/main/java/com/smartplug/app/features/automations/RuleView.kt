package com.smartplug.app.features.automations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionNode
import com.smartplug.app.domain.model.ConditionText

/** Representação visual da regra: SE (condições com E/OU) → ENTÃO (ação). */
@Composable
fun RuleView(
    root: ConditionGroup,
    action: ActionSpec,
    locationName: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SE", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (root.children.isEmpty()) {
                    Text("Nenhuma condição definida.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    NodeList(root, locationName)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ENTÃO", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    ConditionText.describe(action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun NodeList(group: ConditionGroup, locationName: (Long) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.children.forEachIndexed { index, node ->
            if (index > 0) OperatorChip(group.operator.label)
            NodeRow(node, locationName)
        }
    }
}

@Composable
private fun NodeRow(node: ConditionNode, locationName: (Long) -> String) {
    when (node) {
        is ConditionLeaf -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                ConditionText.describe(node, locationName),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        is ConditionGroup -> Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp)) { NodeList(node, locationName) }
        }
    }
}

@Composable
private fun OperatorChip(label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}
