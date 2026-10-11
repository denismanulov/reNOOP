package com.noop.ui.friends

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendsInviteCode
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import kotlin.math.floor

// MARK: - Friends tab: handing an invite over
//
// One invite, laid out to be handed over: the QR a friend's camera reads, the code to type instead, and
// the system's Share. All three are the same ten characters. The QR holds the invite page's address on
// the friends server, which opens the app on the friend's phone.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsInviteScreen(vm: FriendsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val shown by vm.shownInvite.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()

    // Nothing to show (the page was restored after the app was closed): back to where invites are made.
    LaunchedEffect(shown) { if (shown == null) onBack() }
    val invite = shown ?: return

    // The invite page on the friends server: what the QR holds and what a message carries.
    val link = remember(serverUrl, invite.code) {
        FriendsInviteCode.pageLink(serverUrl, FriendsInviteCode.normalized(invite.code) ?: invite.code)
    }
    val qr = remember(link) { FriendsQrCode.of(link) }
    val shareText = stringResource(R.string.friends_invite_share_text, link, invite.code)
    val qrLabel = stringResource(R.string.friends_invite_qr)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.friends_invite), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = M3Dimens.bottomBarClearance),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (qr != null) {
                // White behind black in either theme: a camera reads this, a theme must not.
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(M3Dimens.cardRadius))
                        .background(QrPaper)
                        .padding(16.dp),
                ) {
                    Canvas(Modifier.size(220.dp).semantics { contentDescription = qrLabel }) {
                        // Whole pixels to a module, so the edges stay sharp.
                        val cell = floor(size.minDimension / qr.side)
                        val inset = (size.minDimension - cell * qr.side) / 2f
                        for (y in 0 until qr.side) {
                            for (x in 0 until qr.side) {
                                if (qr.isDark(x, y)) {
                                    drawRect(QrInk, topLeft = Offset(inset + x * cell, inset + y * cell), size = Size(cell, cell))
                                }
                            }
                        }
                    }
                }
            }
            SelectionContainer {
                Text(
                    invite.code,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.friends_invite_valid_once, friendsLongDay(invite.invite.expiresAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.friends_invite_share), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The two colours of a QR code. Fixed on purpose: it is read by a camera, in light and in dark theme alike. */
private val QrPaper = Color.White
private val QrInk = Color.Black
