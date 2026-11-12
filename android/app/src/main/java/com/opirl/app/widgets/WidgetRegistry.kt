package com.opirl.app.widgets

import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.alerts.AlertsWidget
import com.opirl.app.widgets.bingo.BingoWidget
import com.opirl.app.widgets.crop.CropWidget
import com.opirl.app.widgets.map.MapWidget
import com.opirl.app.widgets.pngtuber.PngTuberWidget
import com.opirl.app.widgets.pomodoro.PomodoroWidget
import com.opirl.app.widgets.qrcode.QrCodeWidget
import com.opirl.app.widgets.scoreboard.ScoreboardWidget
import com.opirl.app.widgets.slideshow.SlideshowWidget
import com.opirl.app.widgets.snapshot.SnapshotWidget
import com.opirl.app.widgets.text.TextWidget
import com.opirl.app.widgets.videosource.VideoSourceWidget
import com.opirl.app.widgets.vtuber.VTuberWidget
import com.opirl.app.widgets.wheel.WheelWidget

private object SceneWidgetDrawer : WidgetDrawer {
    override val sized get() = false

    override fun placement(widget: Widget) = SceneWidget(widget.id, x = 0, y = 0)

    override fun draw(context: WidgetContext, widget: Widget, placement: SceneWidget): WidgetFrame? = null
}

val WidgetType.drawer: WidgetDrawer
    get() = when (this) {
        WidgetType.TEXT -> TextWidget
        WidgetType.BROWSER -> BrowserWidget
        WidgetType.VIDEO_SOURCE -> VideoSourceWidget
        WidgetType.IMAGE -> ImageWidget
        WidgetType.ALERTS -> AlertsWidget
        WidgetType.MAP -> MapWidget
        WidgetType.SNAPSHOT -> SnapshotWidget
        WidgetType.CHAT -> ChatWidget
        WidgetType.MUSIC -> MusicWidget
        WidgetType.SCENE -> SceneWidgetDrawer
        WidgetType.SLIDESHOW -> SlideshowWidget
        WidgetType.VTUBER -> VTuberWidget
        WidgetType.PNGTUBER -> PngTuberWidget
        WidgetType.QR_CODE -> QrCodeWidget
        WidgetType.SCOREBOARD -> ScoreboardWidget
        WidgetType.WHEEL_OF_LUCK -> WheelWidget
        WidgetType.BINGO_CARD -> BingoWidget
        WidgetType.CROP -> CropWidget
        WidgetType.POMODORO -> PomodoroWidget
    }
