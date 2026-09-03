package com.fortis.wallet.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.wallet.ui.theme.Fx

/** Full-screen ambient: gradient base + slowly-drifting blurred colour blobs. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ambient")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(44_000), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(56_000), RepeatMode.Reverse), label = "b")

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Fx.bg1, Fx.bg2)))
            .drawBehind {
                fun blob(color: Color, cx: Float, cy: Float, rad: Float) {
                    val c = Offset(size.width * cx, size.height * cy)
                    val r = size.minDimension * rad
                    drawCircle(Brush.radialGradient(0f to color, 1f to Color.Transparent, center = c, radius = r), r, c)
                }
                blob(Fx.blobA, 0.5f + 0.08f * (a - 0.5f), 0.10f + 0.10f * a, 1.05f)
                blob(Fx.blobB, 0.95f - 0.10f * b, 0.92f - 0.08f * b, 0.95f)
                blob(Fx.blobC, 0.16f, 0.86f, 0.5f)
            },
    )
}

/** A frosted-glass panel. (Compose has no live backdrop blur below API 33; a
 *  translucent fill + hairline over the ambient reads as glass regardless.) */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    fill: Color = Fx.glass1,
    corner: Dp = Fx.r,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(corner))
            .border(1.dp, Fx.hair, RoundedCornerShape(corner))
            .padding(Fx.s4),
        verticalArrangement = Arrangement.spacedBy(Fx.s3),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Fx.accent, Fx.accent2)), RoundedCornerShape(Fx.pill))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF0A0C16), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, tint: Color = Fx.text, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Fx.glass2, RoundedCornerShape(Fx.pill))
            .border(1.dp, Fx.hair, RoundedCornerShape(Fx.pill))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tint)
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    password: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Fx.s1)) {
        if (label != null) Text(label, color = Fx.textDim, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = !mono,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = LocalTextStyle.current.copy(fontFamily = if (mono) FontFamily.Monospace else null),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Screen scaffold: ambient behind, centred column, bar insets.
 *  `scroll = true` for long forms; keep it false when the content uses weights. */
@Composable
fun Screen(scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        val base = Modifier
            .fillMaxSize()
            .widthIn(max = 460.dp)
            .align(Alignment.TopCenter)
            .systemBarsPadding()
            .imePadding()
        Column(
            (if (scroll) base.verticalScroll(rememberScrollState()) else base).padding(Fx.s4),
            verticalArrangement = Arrangement.spacedBy(Fx.s4),
            content = content,
        )
    }
}

@Composable
fun ErrorText(msg: String?) {
    if (!msg.isNullOrBlank()) Text(msg, color = Fx.bad, textAlign = TextAlign.Start)
}

@Composable
fun BrandMark(tagline: String? = null) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Fx.s3),
    ) {
        Text("fortis", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Fx.text)
        if (tagline != null) Text(tagline, color = Fx.textDim, textAlign = TextAlign.Center)
    }
}
