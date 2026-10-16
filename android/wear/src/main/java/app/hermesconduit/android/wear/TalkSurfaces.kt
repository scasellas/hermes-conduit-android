package app.hermesconduit.android.wear

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.ListenableFuture

/**
 * The "Talk to Hermes" tile (the counterpart of the iOS Control widget for
 * the Action button, Control Center and the Smart Stack): one press opens
 * Conduit straight into a call.
 */
class TalkTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val device = requestParams.deviceConfiguration
            val talk = ModifiersBuilders.Clickable.Builder()
                .setId("talk")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(packageName)
                                .setClassName(MainActivity::class.java.name)
                                .addKeyToExtraMapping(MainActivity.EXTRA_SOURCE, ActionBuilders.AndroidStringExtra.Builder().setValue("tile").build())
                                .build(),
                        )
                        .build(),
                )
                .build()
            val layout = PrimaryLayout.Builder(device)
                .setResponsiveContentInsetEnabled(true)
                .setPrimaryLabelTextContent(Text.Builder(this@TalkTileService, "Conduit").setTypography(Typography.TYPOGRAPHY_CAPTION1).build())
                .setContent(
                    Button.Builder(this@TalkTileService, talk)
                        .setIconContent(ICON)
                        .setSize(ButtonDefaults.LARGE_SIZE)
                        .setContentDescription(L10n.tr("Talk to Hermes"))
                        .build(),
                )
                .setPrimaryChipContent(CompactChip.Builder(this@TalkTileService, L10n.tr("Talk to Hermes"), talk, device).build())
                .build()
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    ICON,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_talk).build())
                        .build(),
                )
                .build()
        }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val ICON = "talk"
    }
}

/**
 * The watch face complication (iOS TalkToHermesComplication): a tap opens
 * Conduit into a call. Nothing on it changes, so it is never updated.
 */
class TalkComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 1,
            MainActivity.talkIntent(this, "complication"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return data(request.complicationType, tap)
    }

    private fun data(type: ComplicationType, tap: PendingIntent?): ComplicationData? {
        val label = L10n.tr("Talk to Hermes")
        val description = PlainComplicationText.Builder(label).build()
        val icon = Icon.createWithResource(this, R.drawable.ic_talk)
        val mono = MonochromaticImage.Builder(icon).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder("Hermes").build(), description)
                .setMonochromaticImage(mono).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(label).build(), description)
                .setTitle(PlainComplicationText.Builder("Conduit").build())
                .setMonochromaticImage(mono).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(mono, description).setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), description).setTapAction(tap).build()
            else -> null
        }
    }
}
