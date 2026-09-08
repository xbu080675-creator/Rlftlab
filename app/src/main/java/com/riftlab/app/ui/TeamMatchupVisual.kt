package com.riftlab.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.EsportsTeamRef

/**
 * Global visual standard for every team-vs-team score surface in RiftLab.
 * Team logos are the primary identity; team codes are secondary labels only.
 * Every real team identity is also a global navigation entry to Team Detail.
 */
@Composable
internal fun TeamMatchupVisual(
    leftCode: String,
    rightCode: String,
    centerText: String,
    modifier: Modifier = Modifier,
    leftImageUrl: String = "",
    rightImageUrl: String = "",
    leftSubtext: String? = null,
    rightSubtext: String? = null,
    centerSubtext: String? = null,
    logoSize: Dp = 54.dp,
    centerFontSize: TextUnit = 24.sp,
    teamNameFontSize: TextUnit = 10.sp,
    centerAccent: Boolean = true
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamIdentityVisual(
            code = leftCode,
            imageUrl = leftImageUrl,
            subtext = leftSubtext,
            logoSize = logoSize,
            teamNameFontSize = teamNameFontSize,
            modifier = Modifier.weight(1f)
        )
        Column(
            modifier = Modifier.widthIn(min = 66.dp, max = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                centerText,
                color = if (centerAccent) RiftCyan else RiftText,
                fontSize = centerFontSize,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            centerSubtext?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.Center)
            }
        }
        TeamIdentityVisual(
            code = rightCode,
            imageUrl = rightImageUrl,
            subtext = rightSubtext,
            logoSize = logoSize,
            teamNameFontSize = teamNameFontSize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TeamIdentityVisual(
    code: String,
    imageUrl: String,
    subtext: String?,
    logoSize: Dp,
    teamNameFontSize: TextUnit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val label = code.ifBlank { "—" }
    val navigable = label !in setOf("—", "TBD", "N/A", "NA")
    Column(
        modifier.then(
            if (navigable) {
                Modifier.clickable {
                    EntityDetailLauncher.openTeam(
                        context,
                        EsportsTeamRef(
                            id = "",
                            code = label,
                            name = label,
                            imageUrl = imageUrl
                        )
                    )
                }
            } else Modifier
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TeamLogo(imageUrl = imageUrl, code = label, modifier = Modifier.size(logoSize))
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            color = RiftText,
            fontSize = teamNameFontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        subtext?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
