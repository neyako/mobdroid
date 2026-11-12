package com.opirl.app.widgets.qrcode

import kotlinx.serialization.Serializable

@Serializable
data class QrCodeSettings(val message: String = "")
