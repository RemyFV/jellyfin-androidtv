package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Text

/**
 * Text with a solid black outline, for legibility over arbitrary artwork (no dark overlay). Drawn as
 * a black stroked copy behind the filled copy.
 */
@Composable
fun OutlinedText(
	text: String,
	color: Color,
	fontSize: TextUnit,
	modifier: Modifier = Modifier,
	textAlign: TextAlign? = null,
	fillWidth: Boolean = false,
	strokeWidth: Dp = 2.5.dp,
) = Box(modifier) {
	val stroke = with(LocalDensity.current) { strokeWidth.toPx() }
	val textModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier

	Text(
		text = text,
		color = Color.Black,
		fontSize = fontSize,
		textAlign = textAlign,
		style = TextStyle(drawStyle = Stroke(width = stroke, join = StrokeJoin.Round)),
		modifier = textModifier,
	)
	Text(
		text = text,
		color = color,
		fontSize = fontSize,
		textAlign = textAlign,
		modifier = textModifier,
	)
}
