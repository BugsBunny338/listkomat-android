package cz.flipcom.listkomat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.flipcom.listkomat.data.LiveSources
import cz.flipcom.listkomat.ui.ListkomatApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LiveSources.install(this)
        setContent {
            ListkomatApp(viewModel = viewModel<AppViewModel>())
        }
    }
}
