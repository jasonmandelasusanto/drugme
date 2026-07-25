package com.drugme.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.auth.AuthUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector?,
)

val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(Routes.HOME, "Home", Icons.Default.Home),
    TopLevelDestination(Routes.SCHEDULE, "Schedule", Icons.Default.Schedule),
    TopLevelDestination(Routes.MEDICATIONS, "Medications", Icons.Default.Medication),
    TopLevelDestination(Routes.PROFILE, "Profile", null),
)

fun isTopLevelRoute(route: String?): Boolean =
    TOP_LEVEL_DESTINATIONS.any { it.route == route }

fun bottomDestinationForRoute(route: String?): String =
    TOP_LEVEL_DESTINATIONS.firstOrNull { it.route == route }?.route ?: Routes.HOME

fun profileInitials(displayName: String?, email: String?): String? {
    val words = displayName.orEmpty()
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    if (words.isNotEmpty()) {
        return when (words.size) {
            1 -> words.first().take(2).uppercase()
            else -> "${words.first().first()}${words.last().first()}".uppercase()
        }
    }
    return email?.trim()?.firstOrNull()?.uppercase()
}

@HiltViewModel
class AppNavigationViewModel @Inject constructor(auth: AuthRepository) : ViewModel() {
    val user: StateFlow<AuthUser?> = auth.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = auth.currentUser,
    )
}

@Composable
fun ProfileNavigationIcon(
    selected: Boolean,
    viewModel: AppNavigationViewModel = hiltViewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    ProfileNavigationIcon(user = user, selected = selected)
}

@Composable
internal fun ProfileNavigationIcon(user: AuthUser?, selected: Boolean) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val modifier = Modifier
        .size(30.dp)
        .border(2.dp, ring, CircleShape)
        .clip(CircleShape)

    when {
        user?.photoUrl != null -> AsyncImage(
            model = user.photoUrl,
            contentDescription = "Profile picture",
            modifier = modifier,
        )

        profileInitials(user?.displayName, user?.email) != null -> Box(
            modifier = modifier.semantics {
                contentDescription =
                    "Profile ${profileInitials(user?.displayName, user?.email)}"
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profileInitials(user?.displayName, user?.email).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }

        else -> Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = "Profile",
            modifier = modifier,
        )
    }
}
