package com.example.calibredrive.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF245B7A),
    onPrimary = Color.White,
    secondary = Color(0xFF4D7C6C),
    onSecondary = Color.White,
    tertiary = Color(0xFFB05C2C),
    background = Color(0xFFF4F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EDF3),
    onBackground = Color(0xFF18232B),
    onSurface = Color(0xFF18232B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BC0DD),
    onPrimary = Color(0xFF0C2230),
    secondary = Color(0xFF8FD0BC),
    onSecondary = Color(0xFF082017),
    tertiary = Color(0xFFF0B38A),
    background = Color(0xFF0E151B),
    surface = Color(0xFF121C24),
    surfaceVariant = Color(0xFF20303B),
    onBackground = Color(0xFFE4EDF3),
    onSurface = Color(0xFFE4EDF3),
)

@Composable
fun CalibreDriveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
