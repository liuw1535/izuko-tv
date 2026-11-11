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
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 给老系统补根证书的信任库.
 *
 * 系统信任库随系统版本冻结: Android 5/6 (API 21~23) 里没有 ISRG Root X1/X2, 也没有 GTS 的根,
 * 而 Let's Encrypt 用 DST Root CA X3 做的那份交叉签名 2021-09 就过期了 —— 结果是这些站点握手直接失败.
 * `network_security_config.xml` 那条路走不通: 那个属性是 API 24 才有的, 老系统整个忽略它.
 *
 * 所以这里在**应用内**建一个信任库 = 系统根 ∪ [BundledRootCertificates], 交给 OkHttp 的
 * `sslSocketFactory` 用. 主机名校验仍由 OkHttp 自己做 (证书链校验走标准 PKIX), 与系统的行为一致,
 * 只是多认了几张根.
 *
 * API 24 及以上返回 null: 那些版本走清单里的网络安全配置, 不该被这里改写.
 */
object LegacyTrustAnchors {
    private val logger = logger("LegacyTrustAnchors")

    class Trust(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    /** 只有 Android API 21~23 才有值; 其余平台与版本为 null. 建失败也返回 null (退回系统信任库, 与没有这段代码时一样). */
    val value: Trust? by lazy { create() }

    private fun create(): Trust? {
        val sdkInt = (currentPlatform() as? Platform.Android)?.sdkInt ?: return null
        if (sdkInt >= 24) return null
        return runCatching {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

            // Android 的系统根证书库是一个独立的 KeyStore; 平铺进我们这份, 这样一张信任库里同时有系统根和随包的根.
            val systemStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            for (alias in systemStore.aliases()) {
                val certificate = systemStore.getCertificate(alias) ?: continue
                store.setCertificateEntry("system:$alias", certificate)
            }

            val factory = CertificateFactory.getInstance("X.509")
            BundledRootCertificates.All.forEachIndexed { index, pem ->
                val certificate = factory.generateCertificate(pem.byteInputStream(Charsets.US_ASCII)) as X509Certificate
                store.setCertificateEntry("bundled:$index", certificate)
            }

            val trustManager = TrustManagerFactory.getInstance("X509")
                .apply { init(store) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
            val socketFactory = SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf(trustManager), null) }
                .socketFactory
            logger.info {
                "Using system trust store plus ${BundledRootCertificates.All.size} bundled roots (API $sdkInt)"
            }
            Trust(socketFactory, trustManager)
        }.onFailure {
            logger.warn(it) { "Failed to build the extended trust store, falling back to the system one" }
        }.getOrNull()
    }
}

/**
 * 直接 `OkHttpClient.Builder()` 建出来的客户端 (比如 DoH 用的那个) 也要带上老系统的根证书补充.
 * 见 [LegacyTrustAnchors].
 */
fun OkHttpClient.Builder.legacyTrustAnchors(): OkHttpClient.Builder = apply {
    LegacyTrustAnchors.value?.let { sslSocketFactory(it.socketFactory, it.trustManager) }
}

actual fun HttpClientConfig<*>.engineLegacyTrustAnchors() {
    val trust = LegacyTrustAnchors.value ?: return
    @Suppress("UNCHECKED_CAST") // engine 块只会作用于实际的引擎配置, 类型在块内判断
    (this as HttpClientConfig<HttpClientEngineConfig>).engine {
        if (this !is OkHttpConfig) return@engine
        config {
            sslSocketFactory(trust.socketFactory, trust.trustManager)
        }
    }
}
