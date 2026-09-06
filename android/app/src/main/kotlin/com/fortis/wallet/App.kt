package com.fortis.wallet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortis.wallet.ui.AmbientBackground
import com.fortis.wallet.ui.screens.*
import com.fortis.wallet.ui.theme.FortisTheme

@Composable
fun FortisApp(vm: WalletViewModel = viewModel()) {
    FortisTheme {
        AnimatedContent(
            targetState = vm.phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "phase",
        ) { phase ->
            when (phase) {
                Phase.Loading -> Box(Modifier.fillMaxSize()) {
                    AmbientBackground()
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                Phase.Onboard -> OnboardScreen(vm)
                Phase.Gen -> GenScreen(vm)
                Phase.Create -> CreateScreen(vm)
                Phase.Restore -> RestoreScreen(vm)
                Phase.Locked -> UnlockScreen(vm)
                Phase.Home -> HomeScreen(vm)
                Phase.Settings -> SettingsScreen(vm)
            }
        }
    }
}
