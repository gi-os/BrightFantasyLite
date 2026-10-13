package com.gios.brightfantasylite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A bottom bar of text buttons, drawn like LightBottomBar (same height, side padding and
 * button text), where each label takes the width it needs. LightBottomBar gives each of
 * three text items an equal third of the width, and a label wider than a third is cut
 * with an ellipsis on the LP3's 1080-pixel screen.
 */
@Composable
fun FitBottomBar(items: List<LightBarButton.Text>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 2f.gridUnitsAsDp()),
        horizontalArrangement = if (items.size == 1) Arrangement.Center else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val click = item.onClick
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(if (click != null) Modifier.lightClickable(onClick = click) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = item.text, variant = LightTextVariant.Button, maxLines = 1)
            }
        }
    }
}
