package com.nathanblazek.scripturememory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nathanblazek.scripturememory.ui.App
import com.nathanblazek.scripturememory.ui.ScriptureMemoryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScriptureMemoryTheme {
                App()
            }
        }
    }
}
