package com.example.sewingmachinedashboardwebview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.Scaffold

import androidx.compose.ui.Modifier

import com.example.sewingmachinedashboardwebview.ui.theme.SewingMachineDashboardWebviewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        setContent {
            SewingMachineDashboardWebviewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashBoardScreen()
                }
            }
        }
    }
}

