/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android

import android.os.Build
import me.him188.ani.app.domain.mediasource.web.AndroidOnnxImageCaptchaRecognizer
import me.him188.ani.app.domain.mediasource.web.captcha.ImageCaptchaRecognizer
import me.him188.ani.app.domain.mediasource.web.captcha.UnsupportedImageCaptchaRecognizer

/**
 * 图片验证码识别器的选型.
 *
 * onnxruntime 的 `libonnxruntime.so` 未定义符号里有 `stderr` / `stdout` / `__register_atfork` (API 23)
 * 与 `__gnu_strerror_r` (API 24), Android 5/6 上加载必失败. 兼容包 (minSdk 21) 在这两版里退化成
 * [UnsupportedImageCaptchaRecognizer] —— 需要图片验证码的网页数据源在选源时直接当「不支持」, 不会崩.
 *
 * API 23 及以上与之前完全一致.
 */
internal fun createImageCaptchaRecognizer(): ImageCaptchaRecognizer =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        AndroidOnnxImageCaptchaRecognizer()
    } else {
        UnsupportedImageCaptchaRecognizer
    }
