package dev.sasikanth.rss.reader.resources.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Butt
import androidx.compose.ui.graphics.StrokeJoin.Companion.Miter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val TwineIcons.Nextcloud: ImageVector
  get() {
    if (_nextcloud != null) {
      return _nextcloud!!
    }
    _nextcloud =
      Builder(
          name = "Nextcloud",
          defaultWidth = 24.0.dp,
          defaultHeight = 24.0.dp,
          viewportWidth = 24.0f,
          viewportHeight = 24.0f,
        )
        .apply {
          path(
            fill = SolidColor(Color(0xFF000000)),
            stroke = null,
            strokeLineWidth = 0.0f,
            strokeLineCap = Butt,
            strokeLineJoin = Miter,
            strokeLineMiter = 4.0f,
            pathFillType = NonZero,
          ) {
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            curveTo(2.0f, 17.52f, 6.48f, 22.0f, 12.0f, 22.0f)
            curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
            curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 4.0f)
            curveTo(16.42f, 4.0f, 20.0f, 7.58f, 20.0f, 12.0f)
            curveTo(20.0f, 16.42f, 16.42f, 20.0f, 12.0f, 20.0f)
            curveTo(7.58f, 20.0f, 4.0f, 16.42f, 4.0f, 12.0f)
            curveTo(4.0f, 7.58f, 7.58f, 4.0f, 12.0f, 4.0f)
            close()
            moveTo(12.0f, 6.0f)
            curveTo(8.69f, 6.0f, 6.0f, 8.69f, 6.0f, 12.0f)
            curveTo(6.0f, 15.31f, 8.69f, 18.0f, 12.0f, 18.0f)
            curveTo(15.31f, 18.0f, 18.0f, 15.31f, 18.0f, 12.0f)
            curveTo(18.0f, 8.69f, 15.31f, 6.0f, 12.0f, 6.0f)
            close()
            moveTo(12.0f, 8.0f)
            curveTo(14.21f, 8.0f, 16.0f, 9.79f, 16.0f, 12.0f)
            curveTo(16.0f, 14.21f, 14.21f, 16.0f, 12.0f, 16.0f)
            curveTo(9.79f, 16.0f, 8.0f, 14.21f, 8.0f, 12.0f)
            curveTo(8.0f, 9.79f, 9.79f, 8.0f, 12.0f, 8.0f)
            close()
          }
        }
        .build()
    return _nextcloud!!
  }

private var _nextcloud: ImageVector? = null
