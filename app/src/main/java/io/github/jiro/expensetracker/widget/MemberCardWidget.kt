package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class MemberCardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            MemberCardWidgetEntryPoint::class.java,
        )
        provideContent {
            GlanceTheme {
                PlaceholderContent()
            }
        }
    }

    @Composable
    private fun PlaceholderContent() {
        Text("Loading…")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemberCardWidgetEntryPoint {
    fun widgetCardSource(): WidgetCardSource
}
