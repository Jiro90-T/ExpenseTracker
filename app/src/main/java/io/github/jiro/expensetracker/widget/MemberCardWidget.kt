// app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidget.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.BitmapImageProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.R
import java.io.File

class MemberCardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            MemberCardWidgetEntryPoint::class.java,
        )
        val cards = entry.widgetCardSource().loadAll(context)
        val rawIndex = MemberCardWidgetState.readCycleIndex(context)
        val index = rawIndex.coerceInRange(cards.size)
        if (rawIndex != index) {
            MemberCardWidgetState.setCycleIndex(context, index)
        }
        provideContent {
            GlanceTheme {
                when {
                    cards.isEmpty() -> EmptyState(context)
                    cards.size == 1 -> PopulatedCard(cards[0], index = 0, total = 1, context = context)
                    else -> PopulatedCard(cards[index], index = index, total = cards.size, context = context)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(context: Context) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.cards_widget_empty_title),
            style = TextStyle(fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(text = context.getString(R.string.cards_widget_empty_subtitle))
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = context.getString(R.string.cards_widget_add_card),
            modifier = GlanceModifier
                .padding(8.dp)
                .cornerRadius(8.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(
                    actionRunCallback<EmptyStateAddAction>(
                        actionParametersOf(ActionParamsKeys.CARD_ID to 0L)
                    )
                ),
        )
    }
}

@Composable
private fun PopulatedCard(card: WidgetCard, index: Int, total: Int, context: Context) {
    val photoClick = actionRunCallback<OpenDetailAction>(
        actionParametersOf(ActionParamsKeys.CARD_ID to card.id)
    )
    val bodyClick = actionRunCallback<CycleCardsAction>()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(16.dp)
            .clickable(bodyClick),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = context.getString(R.string.cards_widget_label),
                style = TextStyle(fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = context.getString(R.string.cards_widget_counter_format, index + 1, total),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(140.dp)
                .cornerRadius(8.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .clickable(photoClick),
            contentAlignment = Alignment.Center,
        ) {
            val file = card.imagePath?.let { File(File(context.filesDir, "cards"), it) }
            if (card.imageMissing || file == null || !file.isFile) {
                Text(text = context.getString(R.string.cards_image_missing))
            } else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    Image(
                        provider = BitmapImageProvider(bitmap),
                        contentDescription = context.getString(R.string.cards_widget_image_content_desc),
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                } else {
                    Text(text = context.getString(R.string.cards_image_missing))
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.name,
                style = TextStyle(fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (card.isExpired) {
                Text(
                    text = " " + context.getString(R.string.cards_expired),
                    style = TextStyle(color = GlanceTheme.colors.error),
                )
            }
        }
        card.expiresAtEpochMillis?.let { ms ->
            val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                .format(java.util.Date(ms))
            Spacer(GlanceModifier.height(2.dp))
            Text(text = date)
        }
        if (total > 1) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.cards_widget_next) + " →",
                modifier = GlanceModifier.clickable(bodyClick),
            )
        }
    }
}

object ActionParamsKeys {
    val CARD_ID = androidx.glance.action.ActionParameters.Key<Long>("card_id")
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemberCardWidgetEntryPoint {
    fun widgetCardSource(): WidgetCardSource
}