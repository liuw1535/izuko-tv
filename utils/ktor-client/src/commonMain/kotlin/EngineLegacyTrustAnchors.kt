/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.HttpClientConfig

/**
 * 给这个客户端用「系统根证书 ∪ 随包的根证书」的信任库.
 *
 * 只有 Android 5/6 (API 21~23) 会真的改: 那几个版本的系统信任库里没有 Let's Encrypt / GTS 的根,
 * 而 `network_security_config.xml` 又是 API 24 才生效的. 其他平台与版本是空操作.
 * 见 [LegacyTrustAnchors].
 */
expect fun HttpClientConfig<*>.engineLegacyTrustAnchors()
