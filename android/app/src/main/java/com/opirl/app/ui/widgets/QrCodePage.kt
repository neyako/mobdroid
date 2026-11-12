package com.opirl.app.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.ui.Group
import com.opirl.app.ui.TextRow

@Composable
internal fun QrCodePage(scope: WidgetScope) {
    Group {
        row { shapes ->
            TextRow(shapes, stringResource(R.string.qrcode_message), scope.widget.qrCode.message) { message ->
                scope.update { it.copy(qrCode = it.qrCode.copy(message = message)) }
            }
        }
    }
}
