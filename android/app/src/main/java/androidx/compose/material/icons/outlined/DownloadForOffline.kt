/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.DownloadForOffline: ImageVector
    get() {
        if (_downloadForOffline != null) {
            return _downloadForOffline!!
        }
        _downloadForOffline = materialIcon(name = "Outlined.DownloadForOffline") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.49f, 2.0f, 2.0f, 6.49f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.49f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.49f, 10.0f, -10.0f)
                reflectiveCurveTo(17.51f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
                reflectiveCurveTo(16.41f, 20.0f, 12.0f, 20.0f)
                close()
                moveTo(14.59f, 8.59f)
                lineTo(16.0f, 10.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(11.0f, 10.17f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(4.17f)
                lineTo(14.59f, 8.59f)
                close()
                moveTo(17.0f, 17.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineTo(17.0f)
                close()
            }
        }
        return _downloadForOffline!!
    }

private var _downloadForOffline: ImageVector? = null
