# Android 5.0 (API 21) 实验包

目标设备: 创维 8S70 / E6200 (MStar 6A828, Cortex-A53 ×4, 1.5 GB RAM, Mali-450 仅 ES 2.0,
Android **5.0.1 / API 21**, /data 只剩 ~485 MB)。设备侧的探索记录见同目录之外的
`REPORT-8S70.md`(仓库外)。

正式包的 `android.min.sdk` 是 27, 兼容包 (`-Pani.android.legacy=true`) 是 25, 两者都进不了这台电视。
本目录的构建把 minSdk 压到 **21**, 用来回答一个问题: **这个 App 在 Android 5 上能不能起来**。

## 一、可行性的三条硬约束 (实测)

### 1. 依赖库声明的最低版本是 23 / 24 / 26

2026 年这一代 AndroidX 的底线已经是 API 23 (Android 6.0)。把关键 AAR 的 `AndroidManifest.xml`
读出来的结果:

| 依赖 | minSdk |
|---|---|
| Compose UI / Foundation / Material3 1.12.1 | 23 |
| activity 1.12.4, lifecycle 2.11.0, media3 1.9.0, room 2.8.4, datastore 1.2.0, paging 3.4.1 | 23 |
| sketch 4.6 / zoomimage 1.6 / haze 1.7.2 / navigation3 / tv-material | 23 |
| `org.jetbrains.androidx.navigation:navigation-compose` 2.10.0-beta01 | **24** |
| `com.microsoft.onnxruntime:onnxruntime-android` 1.22.0 | **24** |
| `org.openani.anitorrent:anitorrent-native-android` | **26** |

清单里 `tools:overrideLibrary` 只是让 manifest 合并放行, 不代表代码真的能跑 —— 真正的验证只能上机。
该属性只挂在 **tv flavor** 的清单上 (`app/android/src/izukoTv/AndroidManifest.xml`),
手机包与正式包仍享受「库声明更高 minSdk 就拦住你」这层保护。

manifest 合并一次只报一个库, 要一次拿全清单就在构建前跑一遍扫描 (直接读 Gradle 缓存里的 AAR, 不额外联网):

```bash
./gradlew -I .github/workflows/scan-android-minsdk.init.gradle --no-configuration-cache \
    -Pani.android.legacy=true -Pani.android.legacyMinSdk=21 :app:android:scanAndroidMinSdk
```

输出里的 `pkg=` 就是要加进 `tools:overrideLibrary` 的包名。CI 里的 `Scan AAR minSdk (完整清单)` 步骤跑的就是它。

除 androidx 外实测还有两张: `com.google.firebase` (firebase-common 22.0.1, minSdk 23) —— 运行时不会被走到:
Firebase 只在 `settings.isAnalyticsEnabled && currentAniBuildConfig.analyticsEnabled` 为真时才初始化,
而实验包的 `ani.enable.firebase=false` 让后者恒为 false。

### 2. 两个原生库在 API 21 上 `dlopen` 必失败

用 NDK r30 的 `llvm-readelf` 把这些 `.so` 的未定义符号和 API 21 的 libc stub 逐个比对:

| 库 | 结论 |
|---|---|
| `libtorrent-rasterbar.so` (anitorrent) | `getifaddrs` / `freeifaddrs` / `__pread_chk` / `__pwrite_chk` / `__register_atfork` → **API 23/24 才有** |
| `libonnxruntime.so` | `stderr` / `stdout` / `__register_atfork` (23), `__gnu_strerror_r` (24) → **API 21 加载失败** |
| `libavcodec` / `libavformat` / `libavutil` (mediamp ffmpeg) | 唯一的新符号 `memfd_create` 是 **WEAK**, 不影响加载 |
| `libsqliteJni.so` / `libmediamp_wsola.so` / `libconscrypt_jni.so` | 全部 API 21 可用 |

所以这两条功能在 `SDK_INT < 23` (onnx) / `< 24` (anitorrent) 时按「这台设备没有」处理:

- 图片验证码识别 → `UnsupportedImageCaptchaRecognizer` (`app/android/src/default/kotlin/ImageCaptchaRecognizerCompat.kt`)
- BT 下载/播放在 `AnitorrentLibraryLoader.loadDependencies()` 里提前抛 `IOException`, 给出可归因的原因

要真正恢复它们, 得用 `torrent/anitorrent/native/build.sh` 以 `ANDROID_PLATFORM=android-21` 重编
(libtorrent 在 21 上走 netlink 枚举网卡, Android 5 允许; 但 Android 11 起应用不许绑 netlink,
所以那份产物只能给老设备用, 不能替换正式包里的)。onnxruntime 只能换更老的版本或不用。

### 3. Java 侧: dex 版本必须由源码重编决定

**拿 apktool 把 APK 的 minSdkVersion 改成 21 装不进 Android 5** —— 那种包的 dex 还是按 minSdk 27
编出来的 **DEX 039** (含 `invoke-custom` / `invoke-polymorphic`), Android 5.0 的 ART/dex2oat 不认,
安装时直接报「无法进行 dex2oat 编译」。只有以 minSdk 21 重新构建, D8 才会出 **DEX 035** 并把
lambda / 默认方法 / `java.time` / `java.nio.file` 全部脱糖 (见 `isCoreLibraryDesugaringEnabled`)。

CI 的 `Verify minSdk / dex version / v1 signature` 步骤就是校验这三件事; 另外 Android 5 只认
v1 (JAR) 签名, 打包后的 APK 必须带 v1 才会被接受。

对 23→21 的运行时风险, 抽查了 compose-ui / foundation / activity / media3 / material3 的
`classes.jar` 常量池: 只用到 API 23 方法的地方都在 `*ImplApi23` / `*HelperMethodsO` / autofill
这类按 `SDK_INT` 分派的类里, 没有裸调用。这不构成保证 —— navigation/lifecycle/room 没能全量证明。

## 二、怎么出包

```
.github/workflows/fork-android5-build.yml   # Actions 页面手动 Run workflow
```

产物: `izuko-tv-android5-apk` (artifact), 只含 arm64-v8a。签名密钥在 runner 上现生成,
与正式包不同 —— 上机前先 `pm uninstall io.github.grahamzen.anime.tv`。

### 两段式 minSdk (为什么要事后改清单)

manifest 合并只会拿**声明的** minSdk 去和每个库比, 一个库报一次错、一轮 CI 六分钟。而 D8 出的 dex
版本只取决于 `minSdk < 24` 与否 —— 21 和 23 都出 **DEX 035**, 默认方法 / lambda / `java.time` /
`java.nio.file` 的脱糖完全一样。所以 CI 里走两段:

1. Gradle 按 `buildMinApi` (默认 **23**) 构建 —— 依赖库里 99% 的底线就在这一层, 合并一次就过;
2. `patch-apk-min-sdk.py` 把清单里的 `minSdkVersion` 二进制改成 `minApi` (默认 **21**),
   `zipalign` 对齐后 `apksigner` 重新签 (v1 + v2, Android 5 只认 v1)。

两段产出的代码与「直接按 21 构建」逐字节等价, 只是绕开了合并期的逐个比对。
清单里的 `tools:overrideLibrary` 仍然保留: `navigation-compose` / `onnxruntime` 是 24,
`anitorrent` 是 26, 23 也过不去。

## 三、上机怎么判定

```bash
adb connect 192.168.0.120:5555
adb install -r -g izuko-tv-android5-api21-arm64.apk
adb logcat -c && adb shell am start -n io.github.grahamzen.anime.tv/me.him188.ani.android.MainActivity
adb logcat -v epoch | grep -E "AndroidRuntime|FATAL|UnsatisfiedLinkError|NoSuchMethodError|NoClassDefFoundError|Verification|bundled roots"
```

| 现象 | 含义 |
|---|---|
| `dex2oat` 失败 / 安装报错 | dex 不是 035 或签名缺 v1, 回 CI 看校验步骤 |
| `UnsatisfiedLinkError` | 命中了没被 SDK_INT 挡住的原生库, 按上面的表补门 |
| `NoSuchMethodError` / `NoClassDefFoundError` | 某个库在 21 上裸调了 23/24 的 API, 看堆栈定位, 单点可以补 |
| `SSLHandshakeException` / `CertPathValidatorException` | 先看日志里有没有 `Using system trust store plus 8 bundled roots` —— 没有就是补充信任库没装上; 有则说明对方用的根不在那 8 张里, 加进去即可 |
| 能进首页但卡顿/OOM | 硬件上限问题 (1.5 GB / Mali-450 ES 2.0 / eMMC), 换设备比改代码便宜 |

## 四、HTTPS 根证书 (已做)

`android:networkSecurityConfig` 是 **API 24** 才生效的, 在 Android 5/6 上系统整个忽略它 ——
所以清单里 `@xml/network_security_config` 那两张 SSL.com 根在 API 21~23 上等于不存在。
而 Android 5.0.1 的系统信任库里没有 ISRG Root X1/X2 (Android 7.1.1 才进), Let's Encrypt 用
DST Root CA X3 做的那份交叉签名 2021-09 已过期 —— 不补这一块, 大量站点直接握手失败, App 起来了也拉不到数据。

做法是在**应用内**建信任库 = 系统根 ∪ 随包的 8 张根证书, 交给 OkHttp:

| 位置 | 作用 |
|---|---|
| `utils/ktor-client` `.../BundledRootCertificates.kt` | 8 张根证书 (ISRG X1/X2、GTS R1/R4、Amazon Root CA 1、DigiCert Global Root G2、SSL.com RSA/ECC 2022), 每张都标了 SHA-256 指纹, 均取自 Mozilla 根证书库 |
| `utils/ktor-client` `.../LegacyTrustAnchors.jvm.kt` | 把 `AndroidCAStore` 里的系统根和上面 8 张平铺进同一个 KeyStore, 建出 `SSLSocketFactory` + `X509TrustManager`. **只在 Android API 21~23 生效**, 其余平台/版本返回 `null`, 行为与之前完全一致 |
| `.../EngineLegacyTrustAnchors.kt` (common + apple) | expect/actual: common 声明, jvm 落实, apple 空实现 |
| `DefaultClient.kt` | 一行钩子 `engineLegacyTrustAnchors()`, 紧跟 `engineFallbackDns()` |
| `PublicDns.kt` (DoH 客户端) | `.legacyTrustAnchors()` |
| `PlaybackHttpDataSource.kt` / `RangeHttpClient.kt` (播放与下载) | 同上 —— 这两个客户端自己建 OkHttp, 不走 Ktor |

主机名校验仍由 OkHttp 自己做, 证书链走标准 PKIX, 只是多认几张根。API 24+ 走原来的网络安全配置, 不受影响。

**WebView 覆盖不到**: 那台电视的 WebView 是 37 版, 它用系统信任库, 应用没法替它加根。
需要 WebView 的规则源在系统层面装证书之前会失败 (机器已 root, 可以往 `/system/etc/security/cacerts/`
里覆盖一个闲置根证书的文件 —— Dirty COW 能改写不能新建)。

## 五、还没做的

**BT / 图片验证码**: 见上面第一节第 2 条, 需要按 `android-21` 重编原生库 (`torrent/anitorrent/native/build.sh`
传 `ANDROID_PLATFORM=android-21`; 21 上 libtorrent 走 netlink 枚举网卡, Android 5 允许, 但 Android 11 起
应用不许绑 netlink, 所以那份产物只能给老设备用)。onnxruntime 只能换更老的版本或继续不用。
