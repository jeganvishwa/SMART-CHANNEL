package com.example.freetv.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.freetv.presentation.home.HomeScreen

@Composable
fun FreeTvNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = FreeTvRoute.Home,
        modifier = modifier,
    ) {
        composable<FreeTvRoute.Home> {
            HomeScreen()
        }
    }
}
