package com.twtrubiks.stockdesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 系統列透明；圖示的深淺由 StockDeskTheme 跟著 App 的深色模式設定
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { StockDeskApp() }
    }
}
