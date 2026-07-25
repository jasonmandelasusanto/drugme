package com.drugme.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.drugme.app.ui.addmed.AddMedicationScreen
import com.drugme.app.ui.history.HistoryScreen
import com.drugme.app.ui.home.HomeScreen
import com.drugme.app.ui.medications.MedicationDetailsScreen
import com.drugme.app.ui.medications.MedicationsScreen
import com.drugme.app.ui.profile.ProfileScreen
import com.drugme.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SCHEDULE = "schedule"
    const val MEDICATIONS = "medications"
    const val ADD_MEDICATION = "medication"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"

    const val EDIT_MEDICATION = "medication?id={id}"
    fun editMedication(id: String) = "medication?id=$id"

    const val MEDICATION_DETAILS = "medication-details/{id}"
    fun medicationDetails(id: String) = "medication-details/$id"
}

@Composable
fun DrugMeNavHost(
    onFixExactAlarms: () -> Unit,
    onSignIn: () -> Unit,
    onSignedOut: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = isTopLevelRoute(currentRoute)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL_DESTINATIONS.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (destination.route == Routes.PROFILE) {
                                    ProfileNavigationIcon(selected = selected)
                                } else {
                                    Icon(
                                        imageVector = requireNotNull(destination.icon),
                                        contentDescription = destination.label,
                                    )
                                }
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { shellPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = if (showBottomBar) Modifier.padding(shellPadding) else Modifier,
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onAddMedication = { navController.navigate(Routes.ADD_MEDICATION) },
                    onEditMedication = { navController.navigate(Routes.editMedication(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.SCHEDULE) {
                HistoryScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }

            composable(Routes.MEDICATIONS) {
                MedicationsScreen(
                    onAddMedication = { navController.navigate(Routes.ADD_MEDICATION) },
                    onEditMedication = { navController.navigate(Routes.editMedication(it)) },
                    onOpenDetails = { navController.navigate(Routes.medicationDetails(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    onOpenMedications = { navController.navigate(Routes.MEDICATIONS) },
                    onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onSignIn = onSignIn,
                    onSignedOut = onSignedOut,
                )
            }

            composable(
                route = Routes.EDIT_MEDICATION,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                AddMedicationScreen(
                    medicationId = entry.arguments?.getString("id"),
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.MEDICATION_DETAILS,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                MedicationDetailsScreen(
                    medicationId = requireNotNull(entry.arguments?.getString("id")),
                    onNavigateBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.editMedication(it)) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onFixExactAlarms = onFixExactAlarms,
                )
            }
        }
    }
}
