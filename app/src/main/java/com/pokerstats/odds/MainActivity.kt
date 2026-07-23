package com.pokerstats.odds

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pokerstats.odds.ui.PokerScreen
import com.pokerstats.odds.ui.theme.PokerStatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PokerStatsTheme {
                PokerScreen()
            }
        }
    }
}
