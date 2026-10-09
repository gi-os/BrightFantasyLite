package com.gios.brightfantasylite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.util.Locale

/** The system theme (dark or light, as LightOS has it) over a filled background. */
@Composable
fun FantasySurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/** Points to one decimal, the way ESPN shows them. */
fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)

@Composable
fun CenterMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
    }
}

@Composable
fun SectionHeader(text: String) {
    LightText(
        text = text.uppercase(),
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 0.8f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp()),
    )
}

@Composable
fun Rule() {
    Box(
        Modifier
            .padding(vertical = 0.4f.gridUnitsAsDp())
            .fillMaxWidth()
            .height(1f.gridUnitsAsDp() / 12)
            .background(LightThemeTokens.colors.contentSecondary),
    )
}

/** "‹  WEEK 5  ›", each arrow only when there is somewhere to go. */
@Composable
fun WeekBar(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onStep: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 0.4f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = if (canGoBack) "‹ PREV" else "",
            variant = LightTextVariant.Detail,
            modifier = if (canGoBack) Modifier.lightClickable { onStep(-1) } else Modifier,
        )
        LightText(text = label, variant = LightTextVariant.Detail, align = TextAlign.Center)
        LightText(
            text = if (canGoForward) "NEXT ›" else "",
            variant = LightTextVariant.Detail,
            modifier = if (canGoForward) Modifier.lightClickable { onStep(1) } else Modifier,
        )
    }
}

/** Three label/value pairs side by side. */
@Composable
fun StatStrip(items: List<Pair<String, String>>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText(text = value, variant = LightTextVariant.Copy)
                LightText(text = label, variant = LightTextVariant.Fine, lighten = true)
            }
        }
    }
}

/** A message line with a filled background, for "now tap who he swaps with". */
@Composable
fun Banner(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 0.3f.gridUnitsAsDp())
            .background(LightThemeTokens.colors.content)
            .padding(horizontal = 0.5f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
    ) {
        LightText(text = text, variant = LightTextVariant.Detail, color = LightThemeTokens.colors.background)
    }
}

/** A tappable row: label on the left, a value on the right, an optional line under. */
@Composable
fun ValueRow(
    label: String,
    value: String,
    sub: String? = null,
    dim: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.lightClickable(onClick = onClick) else base
    val highlighted = if (selected) {
        clickable.background(LightThemeTokens.colors.content)
    } else {
        clickable
    }
    val textColor = if (selected) LightThemeTokens.colors.background else null
    Column(modifier = highlighted.padding(vertical = 0.4f.gridUnitsAsDp(), horizontal = 0.2f.gridUnitsAsDp())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                lighten = dim && !selected,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(0.5f.gridUnitsAsDp()))
            LightText(text = value, variant = LightTextVariant.Copy, color = textColor, maxLines = 1)
        }
        if (sub != null) {
            LightText(
                text = sub,
                variant = LightTextVariant.Fine,
                lighten = !selected,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Full-screen text entry on the LP3 keyboard. Returns the submitted text through
 * [goBack]; backing out returns nothing.
 */
class TextEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val submitLabel: String = "DONE",
) : SimpleLightScreen<String>(sealedActivity) {

    private var done = false

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        FantasySurface {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { finish(it.toString()) },
                onBack = { finish(null) },
                keyboardOptionsFlow = keyboardOptions,
                submitLabel = submitLabel,
                singleLine = true,
                initialCaps = false,
            )
        }
    }
}

/** Scans one QR code with the SDK's scanner and returns its raw text. */
class QrScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    // The scanner can report the same code on consecutive frames; only the first may pop
    // the screen, or a second goBack would pop the screen underneath too.
    private var done = false

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        FantasySurface {
            LightQrCodeScanner(
                title = "Scan setup code",
                onScanned = { finish(it) },
                onBack = { finish(null) },
            )
        }
    }
}
