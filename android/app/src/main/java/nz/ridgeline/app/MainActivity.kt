package nz.ridgeline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.ridgeline.app.ui.PlannerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(
                primary=Color(0xFF315346),onPrimary=Color.White,secondary=Color(0xFFB75D36),
                background=Color(0xFFF6F4ED),surface=Color(0xFFFAF8F2),surfaceVariant=Color(0xFFE8EAE0),
                onSurface=Color(0xFF253A32),onSurfaceVariant=Color(0xFF5D6C63),outline=Color(0xFF849288),
            )) { val model:PlannerViewModel=viewModel(); PlannerScreen(model) }
        }
    }
}
