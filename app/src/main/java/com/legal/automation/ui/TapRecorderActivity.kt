package com.legal.automation.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Full-screen, translucent "record a tap" capture. The app underneath keeps
 * showing through (its last frame — it's stopped, not live), so the user can
 * see exactly what they're pointing at. The first tap anywhere on screen is
 * captured as raw (x, y) and returned as the activity result; a Cancel button
 * backs out without recording anything.
 */
class TapRecorderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TapRecorderContent(
                    onTap = { x, y -> finishWithResult(x, y) },
                    onCancel = { finishCancelled() },
                )
            }
        }
    }

    private fun finishWithResult(x: Int, y: Int) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_X, x).putExtra(EXTRA_Y, y),
        )
        finish()
    }

    private fun finishCancelled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"

        fun intent(context: Context) = Intent(context, TapRecorderActivity::class.java)
    }
}

@Composable
private fun TapRecorderContent(
    onTap: (x: Int, y: Int) -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTap(offset.x.toInt(), offset.y.toInt())
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(16.dp),
        ) {
            Text(
                "Tap where the automation should click. This just records the " +
                    "location — it won't be tapped now.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            ) { Text("Cancel") }
        }
    }
}
