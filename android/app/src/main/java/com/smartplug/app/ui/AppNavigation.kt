package com.smartplug.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartplug.app.features.automations.AutomationsScreen
import com.smartplug.app.features.automations.EditorScreen
import com.smartplug.app.features.dashboard.DashboardScreen
import com.smartplug.app.features.history.HistoryScreen
import com.smartplug.app.features.locations.LocationEditorScreen
import com.smartplug.app.features.locations.LocationsScreen
import com.smartplug.app.features.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Início", Icons.Rounded.Home),
    Automations("automations", "Automações", Icons.Rounded.AutoAwesome),
    Locations("locations", "Locais", Icons.Rounded.Place),
    History("history", "Histórico", Icons.Rounded.History),
    Settings("settings", "Ajustes", Icons.Rounded.Settings),
}

private const val EDITOR_ROUTE = "automation/edit?id={id}&template={template}"
private const val LOCATION_EDITOR_ROUTE = "location/edit?id={id}"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Dashboard.route) {
                DashboardScreen(
                    onOpenAutomations = { navController.navigate(Tab.Automations.route) },
                    onOpenSettings = { navController.navigate(Tab.Settings.route) },
                    onEditAutomation = { navController.navigate("automation/edit?id=$it") },
                )
            }
            composable(Tab.Automations.route) {
                AutomationsScreen(
                    onCreate = { template ->
                        navController.navigate(if (template == null) "automation/edit" else "automation/edit?template=$template")
                    },
                    onEdit = { navController.navigate("automation/edit?id=$it") },
                )
            }
            composable(
                EDITOR_ROUTE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("template") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { EditorScreen(onClose = { navController.popBackStack() }) }

            composable(Tab.Locations.route) {
                LocationsScreen(
                    onAdd = { navController.navigate("location/edit") },
                    onEdit = { navController.navigate("location/edit?id=$it") },
                )
            }
            composable(
                LOCATION_EDITOR_ROUTE,
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L }),
            ) { LocationEditorScreen(onClose = { navController.popBackStack() }) }

            composable(Tab.History.route) { HistoryScreen() }
            composable(Tab.Settings.route) { SettingsScreen() }
        }
    }
}
