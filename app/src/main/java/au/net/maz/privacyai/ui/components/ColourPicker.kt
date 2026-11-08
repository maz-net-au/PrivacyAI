package au.net.maz.privacyai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import au.net.maz.privacyai.ui.theme.CharacterColours
import kotlin.random.Random

/**
 * Character colour picker (a modal, as agreed): saturation/brightness square, hue strip,
 * and previews of a message in both light and dark themes using the derived colours.
 */
@Composable
fun ColourPickerDialog(initial: Int, name: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val colour = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 420.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Colour", style = MaterialTheme.typography.titleLarge)
                // Saturation (x) / brightness (y) square.
                Canvas(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            fun set(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures { set(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    drawCircle(
                        Color.White, radius = 9.dp.toPx(),
                        center = Offset(sat * size.width, (1 - value) * size.height), style = Stroke(3.dp.toPx()),
                    )
                }
                // Hue strip.
                Canvas(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f } }
                        .pointerInput(Unit) {
                            detectDragGestures { c, _ -> hue = (c.position.x / size.width).coerceIn(0f, 1f) * 360f }
                        },
                ) {
                    drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(hue / 360f * size.width, size.height / 2), style = Stroke(3.dp.toPx()))
                }
                Text("Preview", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Preview(colour, name, dark = false, modifier = Modifier.weight(1f))
                    Preview(colour, name, dark = true, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        hue = Random.nextFloat() * 360f; sat = 0.45f + Random.nextFloat() * 0.45f; value = 0.6f + Random.nextFloat() * 0.35f
                    }) { Text("Random") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(colour) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun Preview(colour: Int, name: String, dark: Boolean, modifier: Modifier) {
    val c = CharacterColours.forTheme(colour, dark)
    Column(
        modifier
            .background(if (dark) Color(0xFF121318) else Color(0xFFFBF8FF), RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text(if (dark) "Dark" else "Light", fontSize = 11.sp, color = if (dark) Color(0xFFE4E1E6) else Color(0xFF1B1B1F))
        Column(Modifier.padding(top = 4.dp).background(c.background, RoundedCornerShape(12.dp)).padding(8.dp)) {
            Text(name.ifBlank { "Name" }, color = c.name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Hello there.", color = c.text, fontSize = 14.sp)
        }
    }
}
