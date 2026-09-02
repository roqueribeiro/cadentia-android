package com.levelhard.cadentia.features.stems

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * A tela de enquanto separa — port do `SeparatingView.swift` (1.16).
 *
 * O que informa numa playlist é **a fila**: o que já saiu, o que está saindo
 * e o que ainda vem. "3 de 12" não diz QUAIS três. Então a fila é o centro da
 * tela, e as quatro faixas viraram uma linha de ícones: elas dizem *o que*
 * está sendo produzido, e isso cabe numa linha.
 *
 * @param progress de 0 a 1 na música de agora, ou `null` enquanto prepara.
 * @param startedAtMillis quando o trabalho atual começou (`workStartedAt`).
 * @param batch a leva, quando são várias.
 */
@Composable
fun SeparatingView(
    progress: Double?,
    title: String,
    accent: Color,
    startedAtMillis: Long,
    batch: StemsModel.ImportBatch?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    // O relógio da estimativa: ela sai do tempo decorrido, então precisa ser
    // recalculada mesmo sem nenhuma janela nova chegar.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .testTag("stems.working"),
    ) {
        Ring(progress, accent, reduceMotion)
        Heading(progress, title)
        Tracks(progress, accent)
        if (batch != null && batch.total > 1) {
            Queue(batch, progress, accent, startedAtMillis, now, reduceMotion)
        } else {
            Remaining(progress, batch, startedAtMillis, now)
        }
        KeepOpen()
    }
}

// ── o anel ───────────────────────────────────────────────────────────────

@Composable
private fun Ring(progress: Double?, accent: Color, reduceMotion: Boolean) {
    val target = (progress ?: 0.0).coerceIn(0.0, 1.0).toFloat().coerceAtLeast(0.001f)
    val sweep by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else tween(350),
        label = "stems.ring",
    )
    // Sem porcentagem ainda, o ícone respira: é o único jeito de dizer "está
    // trabalhando" sem um número que não existe.
    val breath = rememberInfiniteTransition(label = "stems.ring.breath")
    val pulse by breath.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "stems.ring.pulse",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
        Canvas(Modifier.size(112.dp)) {
            val strokeWidth = 9.dp.toPx()
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawCircle(
                color = CzTokens.surface,
                radius = size.minDimension / 2 - inset,
                style = Stroke(strokeWidth),
            )
            // O degradê anda com o arco: começa no topo, como o `rotationEffect(-90)`.
            rotate(-90f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.5f), accent, accent.copy(alpha = 0.5f)),
                        center = Offset(size.width / 2, size.height / 2),
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        if (progress != null) {
            Text(
                text = "${(progress * 100).toInt()}%", // i18n-verbatim: porcentagem
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = CzTokens.textPrimary,
                modifier = Modifier.testTag("stems.percent"),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(26.dp)
                    .alpha(if (reduceMotion) 1f else pulse),
            )
        }
    }
}

@Composable
private fun Heading(progress: Double?, title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = CzTokens.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = stringResource(
                if (progress == null) R.string.cadentia_stems_preparing else R.string.cadentia_stems_separating,
            ),
            fontSize = 12.5.sp,
            color = CzTokens.textTertiary,
        )
    }
}

private data class StemIcon(val id: String, val label: Int, val icon: ImageVector)

private val stemIcons = listOf(
    StemIcon("drums", R.string.cadentia_stems_track_drums, Icons.Filled.Album),
    StemIcon("bass", R.string.cadentia_stems_track_bass, Icons.Filled.GraphicEq),
    StemIcon("other", R.string.cadentia_stems_track_other, Icons.Filled.MusicNote),
    StemIcon("vocals", R.string.cadentia_stems_track_vocals, Icons.Filled.Mic),
)

/**
 * As quatro faixas, como ícones. Elas dizem O QUE sai da separação — e são
 * escritas ao mesmo tempo, então quatro barras iguais eram quatro vezes o
 * mesmo número.
 */
@Composable
private fun Tracks(progress: Double?, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        for (stem in stemIcons) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = stem.icon,
                    contentDescription = null,
                    tint = if (progress == null) CzTokens.textTertiary else accent,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = stringResource(stem.label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = CzTokens.textTertiary,
                )
            }
        }
    }
}

// ── a fila ───────────────────────────────────────────────────────────────

/**
 * O que já saiu, o que está saindo, e o que ainda vem. Rola sozinha e mostra
 * no máximo umas cinco de uma vez: numa playlist de quarenta, a lista inteira
 * empurraria tudo para fora da tela.
 */
@Composable
private fun Queue(
    batch: StemsModel.ImportBatch,
    progress: Double?,
    accent: Color,
    startedAtMillis: Long,
    now: Long,
    reduceMotion: Boolean,
) {
    val listState = rememberLazyListState()
    // A música de agora no meio da janela visível, como o `scrollTo(done, anchor: .center)`.
    LaunchedEffect(batch.done) {
        val target = (batch.done - 2).coerceAtLeast(0)
        if (reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("stems.queue")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.cadentia_stems_batch_position,
                    minOf(batch.done + 1, batch.total), batch.total,
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accent,
                modifier = Modifier.testTag("stems.queuePosition"),
            )
            Spacer(Modifier.weight(1f))
            Remaining(progress, batch, startedAtMillis, now)
        }
        // O andamento da LEVA, e não da música: a que está saindo conta como
        // uma fração dela mesma.
        val overall = (batch.done + (progress ?: 0.0)) / maxOf(batch.total, 1).toDouble()
        LinearProgressIndicator(
            progress = { overall.coerceIn(0.0, 1.0).toFloat() },
            color = accent,
            trackColor = CzTokens.surface,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        )
        // Mais alto, e com a borda de baixo esmaecendo: uma linha cortada no
        // meio parece defeito de desenho, e a mesma linha desaparecendo num
        // degradê diz "tem mais aqui embaixo".
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp)
                .padding(horizontal = 14.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(batch.titles) { index, name -> QueueRow(index, name, batch, accent, reduceMotion) }
        }
    }
}

@Composable
private fun QueueRow(index: Int, name: String, batch: StemsModel.ImportBatch, accent: Color, reduceMotion: Boolean) {
    val failed = name in batch.failed
    val done = index < batch.done
    val current = index == batch.done
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (current) accent.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(18.dp)) {
            when {
                failed -> Icon(Icons.Filled.Warning, null, tint = CzTokens.warnAmber, modifier = Modifier.size(15.dp))
                done -> Icon(Icons.Filled.CheckCircle, null, tint = accent, modifier = Modifier.size(15.dp))
                // O ponto que pulsa é o único jeito de dizer "esta agora" sem
                // repetir a porcentagem que já está no anel.
                current -> Box(
                    Modifier
                        .size(15.dp)
                        .border(1.5.dp, accent, CircleShape),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(8.dp)
                            .alpha(if (reduceMotion) 1f else 0.35f)
                            .background(accent, CircleShape),
                    )
                }
                else -> Box(
                    Modifier
                        .size(13.dp)
                        .border(1.dp, CzTokens.textTertiary.copy(alpha = 0.5f), CircleShape),
                )
            }
        }
        Text(
            text = name,
            fontSize = 12.5.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                current -> CzTokens.textPrimary
                done -> CzTokens.textSecondary
                else -> CzTokens.textTertiary
            },
            modifier = Modifier.weight(1f),
        )
    }
}

// ── quanto falta, e o convite ────────────────────────────────────────────

/**
 * A estimativa sai do que JÁ aconteceu nesta execução: o tempo por janela
 * varia com o aparelho, com a temperatura e com o que mais estiver rodando.
 * Abaixo de 6% ela não aparece — com duas janelas de amostra o número pula
 * demais, e mentir rápido é pior que não dizer.
 *
 * Numa leva, o que falta é da LEVA: saber que faltam 8 s para a música de
 * agora não ajuda quem tem doze pela frente.
 */
@Composable
private fun Remaining(progress: Double?, batch: StemsModel.ImportBatch?, startedAtMillis: Long, now: Long) {
    if (progress == null || progress <= 0.06) return
    val elapsed = (now - startedAtMillis).coerceAtLeast(0L) / 1000.0
    val perSong = elapsed / progress
    val left = perSong - elapsed
    val songsAhead = batch?.let { maxOf(it.total - it.done - 1, 0).toDouble() } ?: 0.0
    val total = (left + songsAhead * perSong).roundToInt()
    if (total <= 0 || total >= 24 * 3600) return
    // "faltam uns 6 s" ou "faltam uns 4 min": "faltam uns 380 s" ninguém converte de cabeça.
    val text = if (total < 90) {
        stringResource(R.string.cadentia_stems_remaining, total)
    } else {
        stringResource(R.string.cadentia_stems_remaining_minutes, (total / 60.0).roundToInt())
    }
    Text(
        text = text,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = CzTokens.textTertiary,
        modifier = Modifier.testTag("stems.remaining"),
    )
}

/**
 * O convite, no lugar do antigo aviso "deixe o app aberto": com o serviço de
 * primeiro plano a separação continua com o app atrás e aparece na
 * notificação. Pedir para a pessoa ficar olhando seria pedir por nada.
 */
@Composable
private fun KeepOpen() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 28.dp).padding(top = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = stringResource(R.string.cadentia_stems_leave_free),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = CzTokens.textTertiary,
        )
    }
}
