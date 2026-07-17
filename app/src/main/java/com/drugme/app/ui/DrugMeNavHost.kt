package com.drugme.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.drugme.app.ui.addmed.AddMedicationScreen
import com.drugme.app.ui.history.HistoryScreen
import com.drugme.app.ui.home.HomeScreen

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val ADD_MEDICATION = "medication"

    /** Edit reuses the add screen; a null id means "new". */
    const val EDIT_MEDICATION = "medication?id={id}"
    fun editMedication(id: String) = "medication?id=$id"
}

@Composable
fun DrugMeNavHost(
    onFixExactAlarms: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onAddMedication = { navController.navigate(Routes.ADD_MEDICATION) },
                onEditMedication = { id -> navController.navigate(Routes.editMedication(id)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onFixExactAlarms = onFixExactAlarms,
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

        composable(Routes.HISTORY) {
            HistoryScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
