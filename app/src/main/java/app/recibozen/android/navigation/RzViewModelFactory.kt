package app.recibozen.android.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Every feature ViewModel takes a [app.recibozen.android.demo.repository.ReciboZenRepository] in its
 * constructor (no DI framework at this scale). This is the one place that
 * boilerplate lives, instead of a hand-written Factory class per screen.
 */
@Composable
inline fun <reified VM : ViewModel> rzViewModel(crossinline create: () -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { create() } })
