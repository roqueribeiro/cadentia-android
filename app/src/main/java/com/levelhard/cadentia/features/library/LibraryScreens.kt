package com.levelhard.cadentia.features.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * A seção RoqueOS da biblioteca — port da parte de conexão e navegação da
 * `MusicLibraryView.swift`: conectar por pareamento (código curto + QR),
 * navegar as quatro fontes e baixar uma música para separar.
 */
@Composable
fun RoqueOSSection(
    accent: Color,
    account: RoqueOSAccount,
    onPick: (RoqueOSLibrary.Item) -> Unit,
    downloadingId: String?,
) {
    val phase by account.phase.collectAsState()
    val library = remember(account) { RoqueOSLibrary(account) }

    when (val current = phase) {
        is RoqueOSAccount.Phase.Connected -> ConnectedBrowser(
            accent = accent,
            account = account,
            library = library,
            onPick = onPick,
            downloadingId = downloadingId,
        )
        is RoqueOSAccount.Phase.Waiting -> PairingCard(
            accent = accent,
            code = current.code,
            link = current.link,
            onCancel = { account.cancelConnecting() },
        )
        RoqueOSAccount.Phase.Starting -> CzCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.cadentia_library_connecting),
                    fontSize = 13.sp,
                    color = CzTokens.textSecondary,
                )
            }
        }
        else -> DisconnectedCard(
            accent = accent,
            configured = account.config.isConfigured,
            failure = (current as? RoqueOSAccount.Phase.Failed)?.reason,
            onConnect = { account.connect() },
        )
    }
}

// ---- desconectado ----

@Composable
private fun DisconnectedCard(
    accent: Color,
    configured: Boolean,
    failure: String?,
    onConnect: () -> Unit,
) {
    CzCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_library_connect_roque_o_s),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(
                    if (configured) R.string.cadentia_library_connect_hint
                    else R.string.cadentia_library_not_configured,
                ),
                fontSize = 12.sp,
                color = CzTokens.textSecondary,
            )
            if (failure != null && failure != "nao configurado") {
                Text(
                    text = stringResource(R.string.cadentia_library_connect_failed) +
                        " · " + failure, // i18n-verbatim: diagnóstico técnico curto
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CzTokens.warnAmber,
                )
            }
            if (configured) {
                Text(
                    text = stringResource(R.string.cadentia_library_connect),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                    modifier = Modifier
                        .background(accent, RoundedCornerShape(50))
                        .clickable(onClick = onConnect)
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                        .testTag("library.connect"),
                )
            }
        }
    }
}

// ---- pareamento ----

/**
 * O código curto aparece; o segredo que resgata a sessão não. O QR existe
 * porque a aprovação acontece em OUTRO aparelho e a URL é longa demais para
 * digitar; o endereço fica embaixo para quem prefere copiar.
 */
@Composable
private fun PairingCard(
    accent: Color,
    code: String,
    link: String,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val qr = remember(link) { qrBitmap(link) }

    CzCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_library_pairing_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier
                        .size(168.dp)
                        .background(Color.White, RoundedCornerShape(CzTokens.radiusMD))
                        .padding(10.dp)
                        .testTag("library.pairingQR"),
                )
            }
            Text(
                text = code,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 5.sp,
                color = accent,
                modifier = Modifier.testTag("library.pairingCode"),
            )
            Text(
                text = stringResource(R.string.cadentia_library_pairing_hint),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = CzTokens.textSecondary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("roqueos", link))
                        copied = true
                    }
                    .padding(4.dp)
                    .testTag("library.copyLink"),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = if (copied) accent else CzTokens.textTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(
                        if (copied) R.string.cadentia_library_copied else R.string.cadentia_library_copy_link,
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (copied) accent else CzTokens.textTertiary,
                )
            }
            Text(
                text = link,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                color = CzTokens.textTertiary,
            )
            Text(
                text = stringResource(R.string.cadentia_library_cancel),
                fontSize = 12.sp,
                color = CzTokens.textTertiary,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * QR gerado NO APARELHO, sem rede e sem serviço externo (zxing-core):
 * mandar a URL de pareamento para um gerador na internet seria vazar o
 * convite de acesso — o mesmo princípio do CIQRCodeGenerator do iOS.
 */
private fun qrBitmap(text: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
    )
    val size = matrix.width
    val pixels = IntArray(size * size) { index ->
        if (matrix.get(index % size, index / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

// ---- conectado: fontes e navegação ----

private data class Crumb(
    val title: String,
    val folder: String,
    /** Ids SEPARADOS (juntar e reparsear quebra com dois-pontos no id). */
    val server: String? = null,
    val disk: String? = null,
    val isStorageRoot: Boolean = false,
    val isSharedRoot: Boolean = false,
    val isShared: Boolean = false,
    val isDrive: Boolean = false,
)

@Composable
private fun ConnectedBrowser(
    accent: Color,
    account: RoqueOSAccount,
    library: RoqueOSLibrary,
    onPick: (RoqueOSLibrary.Item) -> Unit,
    downloadingId: String?,
) {
    val scope = rememberCoroutineScope()
    var crumbs by remember { mutableStateOf(listOf<Crumb>()) }
    var items by remember { mutableStateOf(listOf<RoqueOSLibrary.Item>()) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun reload() {
        val crumb = crumbs.lastOrNull() ?: return
        loading = true
        problem = null
        items = emptyList()
        scope.launch {
            try {
                items = when {
                    crumb.isStorageRoot -> library.storages()
                    crumb.isSharedRoot -> library.sharedRoots()
                    crumb.isShared && crumb.server != null ->
                        library.sharedItems(crumb.server, crumb.folder)
                    crumb.isDrive -> library.driveItems(crumb.folder)
                    crumb.server != null && crumb.disk != null ->
                        library.storageItems(crumb.server, crumb.disk, crumb.folder)
                    else -> library.firebaseItems(crumb.folder)
                }
            } catch (error: RoqueOSException) {
                problem = error.display
            } catch (error: Exception) {
                problem = error.message ?: error.javaClass.simpleName
            }
            loading = false
        }
    }

    fun enter(crumb: Crumb) {
        crumbs = crumbs + crumb
        reload()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (crumbs.isEmpty()) {
            // As fontes, cada uma com o próprio nome (um print de erro precisa
            // dizer QUAL falhou).
            CzCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SourceRow(
                        icon = Icons.Filled.Cloud,
                        title = stringResource(R.string.cadentia_library_roqueos_files),
                        detail = stringResource(R.string.cadentia_library_roqueos_files_hint),
                        accent = accent,
                    ) { enter(Crumb(title = it, folder = "/")) }
                    SourceRow(
                        icon = Icons.Filled.Storage,
                        title = stringResource(R.string.cadentia_library_mapped_disks),
                        detail = stringResource(R.string.cadentia_library_mapped_disks_hint),
                        accent = accent,
                    ) { enter(Crumb(title = it, folder = "/", isStorageRoot = true)) }
                    SourceRow(
                        icon = Icons.Filled.FolderShared,
                        title = stringResource(R.string.cadentia_library_shared_folder),
                        detail = stringResource(R.string.cadentia_library_shared_folder_hint),
                        accent = accent,
                    ) { enter(Crumb(title = it, folder = RoqueOSLibrary.SHARED_ROOT, isSharedRoot = true)) }
                    SourceRow(
                        icon = Icons.Filled.MusicNote,
                        title = stringResource(R.string.cadentia_library_google_drive),
                        detail = stringResource(R.string.cadentia_library_google_drive_hint),
                        accent = accent,
                    ) { enter(Crumb(title = it, folder = "root", isDrive = true)) }
                }
            }
            Text(
                text = stringResource(R.string.cadentia_library_disconnect),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
                modifier = Modifier
                    .clickable { account.disconnect() }
                    .padding(6.dp)
                    .testTag("library.disconnect"),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .background(CzTokens.surface, CircleShape)
                        .clickable {
                            crumbs = crumbs.dropLast(1)
                            if (crumbs.isEmpty()) items = emptyList() else reload()
                        }
                        .testTag("library.back"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = crumbs.last().title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = CzTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        color = accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            val currentProblem = problem
            when {
                currentProblem != null -> CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cadentia_library_load_failed),
                            fontSize = 13.sp,
                            color = CzTokens.warnAmber,
                        )
                        // O motivo técnico curto: distingue permissão de formato.
                        Text(
                            text = currentProblem,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            color = CzTokens.textTertiary,
                            modifier = Modifier.testTag("library.folderFailureReason"),
                        )
                    }
                }
                items.isEmpty() && !loading -> CzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            if (crumbs.last().isStorageRoot) R.string.cadentia_library_no_disks
                            else R.string.cadentia_library_empty_folder,
                        ),
                        fontSize = 13.sp,
                        color = CzTokens.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    )
                }
                else -> CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        for (item in items) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (item.isFolder) {
                                            when (val source = item.source) {
                                                is RoqueOSLibrary.Item.Source.Storage -> enter(
                                                    Crumb(item.name, item.path, source.server, source.disk),
                                                )
                                                is RoqueOSLibrary.Item.Source.ServerShared -> enter(
                                                    Crumb(item.name, item.path, source.server, isShared = true),
                                                )
                                                RoqueOSLibrary.Item.Source.GoogleDrive -> enter(
                                                    Crumb(item.name, item.path, isDrive = true),
                                                )
                                                RoqueOSLibrary.Item.Source.Firebase -> enter(
                                                    Crumb(item.name, item.path),
                                                )
                                            }
                                        } else if (downloadingId == null) {
                                            onPick(item)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Icon(
                                    imageVector = if (item.isFolder) Icons.Filled.Folder else Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = if (item.isFolder) CzTokens.textTertiary else accent,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    text = item.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (item.isFolder) FontWeight.Medium else FontWeight.SemiBold,
                                    maxLines = 1,
                                    color = CzTokens.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                when {
                                    item.isFolder -> Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = CzTokens.textTertiary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    downloadingId == item.id -> CircularProgressIndicator(
                                        color = accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    item.sizeBytes != null -> Text(
                                        text = byteLabel(item.sizeBytes),
                                        fontSize = 11.sp,
                                        color = CzTokens.textTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    detail: String,
    accent: Color,
    onClick: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(title) }
            .padding(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = CzTokens.textTertiary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun byteLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}
