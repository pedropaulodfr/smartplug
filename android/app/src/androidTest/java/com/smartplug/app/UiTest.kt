package com.smartplug.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartplug.app.domain.model.Comparison
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TurnOnAction
import com.smartplug.app.features.automations.ConditionDialog
import com.smartplug.app.features.automations.RuleView
import com.smartplug.app.ui.theme.SmartPlugTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fluxos de interface no app real (Hilt + Room + navegação) e componentes isolados. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, millis: Long = 8_000) =
        rule.waitUntil(millis) { rule.onAllNodesWithTextCount(text) > 0 }

    /** Espera o elemento existir E estar visível (listas Lazy medem o item um quadro depois). */
    private fun waitDisplayed(text: String, millis: Long = 8_000) = rule.waitUntil(millis) {
        try {
            rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }
    }

    private fun tab(label: String) = rule.onNode(hasText(label) and hasClickAction())

    @Test fun abreNoDashboardPedindoAConfiguracaoDaTuya() {
        rule.onNodeWithText("Conecte seu Smart Plug").assertIsDisplayed()
        rule.onNodeWithText("Configurar agora").assertIsDisplayed()
        rule.onNodeWithText("Atalhos de automações").assertIsDisplayed()
    }

    @Test fun navegaPorTodasAsAbas() {
        tab("Automações").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Nova automação") > 0 }
        tab("Locais").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Permitir localização") + rule.onAllNodesWithTextCount("Nenhum local cadastrado") > 0 }
        tab("Histórico").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Todas") > 0 }
        tab("Ajustes").performClick()
        rule.onNodeWithText("Conta Tuya Cloud").assertIsDisplayed()
        rule.onNodeWithText("Testar e salvar").performScrollTo().assertIsDisplayed()
        tab("Início").performClick()
        rule.onNodeWithText("Atalhos de automações").assertIsDisplayed()
    }

    @Test fun criaEditaEExcluiUmaAutomacaoPelaInterface() {
        tab("Automações").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTextCount("Nova automação") > 0 }
        rule.onNodeWithText("Nova automação", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Desligar durante a madrugada") > 0 }
        rule.onNodeWithText("Desligar durante a madrugada").performClick()

        // etapa 1: nome vem do modelo
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Informações") > 0 }
        rule.onNodeWithText("Desligar na madrugada").assertIsDisplayed()
        waitFor("Avançar")
        rule.onNodeWithText("Avançar").performClick()
        // etapa 2: condição do modelo
        rule.onNodeWithText("Horário entre 00:00 e 06:00").assertIsDisplayed()
        waitFor("Avançar")
        rule.onNodeWithText("Avançar").performClick()
        // etapa 3: ação
        rule.onNodeWithText("Desligar Smart Plug").assertIsDisplayed()
        waitFor("Avançar")
        rule.onNodeWithText("Avançar").performClick()
        // etapa 4: revisão visual SE / ENTÃO
        rule.onNodeWithText("SE").assertIsDisplayed()
        rule.onNodeWithText("ENTÃO").assertIsDisplayed()
        waitFor("Salvar automação")
        rule.onNodeWithText("Salvar automação", useUnmergedTree = true).performClick()

        // lista: nome, SE/ENTÃO e status
        rule.waitUntil(8_000) { rule.onAllNodesWithTextCount("Desligar na madrugada") > 0 }
        waitDisplayed("Horário entre 00:00 e 06:00")
        waitDisplayed("Nunca executou")

        // excluir pelo menu
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Excluir").performClick()
        rule.onNode(hasText("Excluir") and hasClickAction()).performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTextCount("Desligar na madrugada") == 0 }
        rule.onNodeWithText("Nenhuma automação").assertIsDisplayed()
    }

    @Test fun editorValidaCamposObrigatorios() {
        tab("Automações").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTextCount("Nova automação") > 0 }
        rule.onNodeWithText("Nova automação", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Automação em branco") > 0 }
        rule.onNodeWithText("Automação em branco").performScrollTo().performClick()
        waitFor("Avançar")
        rule.onNodeWithText("Avançar").performClick()
        rule.onNodeWithText("• Dê um nome à automação.").assertIsDisplayed()
        rule.onNodeWithText("Nome").performTextInput("Minha regra")
        waitFor("Avançar")
        rule.onNodeWithText("Avançar").performClick()
        waitFor("Avançar")
        rule.onNodeWithText("Avançar").performClick()
        rule.onNodeWithText("• Adicione ao menos uma condição.").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
}

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    private val casa = SavedLocation(1, "Minha casa", -23.5, -46.6, 150f)

    @Test fun dialogoCriaCondicaoDeConsumoComPermanencia() {
        var result: Pair<Condition, Int>? = null
        rule.setContent {
            SmartPlugTheme {
                ConditionDialog(null, 0, listOf(casa), { c, hold -> result = c to hold }, {})
            }
        }
        rule.onNodeWithText("Consumo").performScrollTo().performClick()
        rule.onNodeWithText("Sem consumo significativo").performScrollTo().performClick()
        rule.onNodeWithText("Permanência mínima (minutos)").performScrollTo().performTextInput("10")
        rule.onNodeWithText("Confirmar").performClick()
        val (condition, hold) = result!!
        assertTrue(condition.toString(), condition is com.smartplug.app.domain.model.NotConsuming)
        assertEquals(600, hold) // 10 min
    }

    @Test fun dialogoDeLocalizacaoUsaOLocalCadastrado() {
        var result: Condition? = null
        rule.setContent {
            SmartPlugTheme { ConditionDialog(null, 0, listOf(casa), { c, _ -> result = c }, {}) }
        }
        rule.onNodeWithText("Localização").performScrollTo().performClick()
        rule.onNodeWithText("Dentro de um local").performScrollTo().performClick()
        rule.onNodeWithText("Minha casa").assertIsDisplayed()
        rule.onNodeWithText("Confirmar").performClick()
        assertEquals(InsideLocation(1), result)
    }

    @Test fun dialogoAvisaQuandoNaoHaLocais() {
        rule.setContent { SmartPlugTheme { ConditionDialog(null, 0, emptyList(), { _, _ -> }, {}) } }
        rule.onNodeWithText("Localização").performScrollTo().performClick()
        rule.onNodeWithText("Dentro de um local").performScrollTo().performClick()
        rule.onNodeWithText("Cadastre um local primeiro (aba Locais) para usar esta condição.").assertIsDisplayed()
    }

    @Test fun eventosNaoOferecemTempoDePermanencia() {
        rule.setContent { SmartPlugTheme { ConditionDialog(null, 0, listOf(casa), { _, _ -> }, {}) } }
        rule.onNodeWithText("Localização").performScrollTo().performClick()
        rule.onNodeWithText("Chegando em um local").performScrollTo().performClick()
        rule.onNodeWithText("Esta condição é um evento: vale apenas no instante em que acontece.").performScrollTo().assertIsDisplayed()
    }

    @Test fun revisaoMostraRegraSeEntaoComOperadores() {
        val root = ConditionGroup(
            -1, LogicOperator.AND,
            listOf(
                ConditionLeaf(-2, TimeAfter(960)),
                ConditionLeaf(-3, InsideLocation(1)),
                ConditionGroup(-4, LogicOperator.OR, listOf(
                    ConditionLeaf(-5, MetricCompare(Metric.POWER, Comparison.GREATER, 100.0)),
                    ConditionLeaf(-6, MetricCompare(Metric.VOLTAGE, Comparison.LESS, 100.0)),
                )),
            ),
        )
        rule.setContent {
            SmartPlugTheme { RuleView(root, TurnOnAction, { casa.name }) }
        }
        rule.onNodeWithText("SE").assertIsDisplayed()
        rule.onNodeWithText("Horário maior que 16:00").assertIsDisplayed()
        rule.onNodeWithText("Dentro de Minha casa").assertIsDisplayed()
        rule.onNodeWithText("Potência maior que 100 W").assertIsDisplayed()
        rule.onNodeWithText("ENTÃO").assertIsDisplayed()
        rule.onNodeWithText("Ligar Smart Plug").assertIsDisplayed()
        assertEquals(2, rule.onAllNodes(hasText("E")).fetchSemanticsNodes().size.coerceAtMost(2))
    }
}
