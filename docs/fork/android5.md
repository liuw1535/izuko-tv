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

## 三、上机怎么判定

```bash
adb connect 192.168.0.120:5555
adb install -r -g izuko-tv-android5-api21-arm64.apk
adb logcat -c && adb shell am start -n io.github.grahamzen.anime.tv/me.him188.ani.android.MainActivity
adb logcat -v epoch | grep -E "AndroidRuntime|FATAL|UnsatisfiedLinkError|NoSuchMethodError|NoClassDefFoundError|Verification"
```

| 现象 | 含义 |
|---|---|
| `dex2oat` 失败 / 安装报错 | dex 不是 035 或签名缺 v1, 回 CI 看校验步骤 |
| `UnsatisfiedLinkError` | 命中了没被 SDK_INT 挡住的原生库, 按上面的表补门 |
| `NoSuchMethodError` / `NoClassDefFoundError` | 某个库在 21 上裸调了 23/24 的 API, 看堆栈定位, 单点可以补 |
| 能进首页但卡顿/OOM | 硬件上限问题 (1.5 GB / Mali-450 ES 2.0 / eMMC), 换设备比改代码便宜 |

## 四、还没做的两件事

1. **HTTPS 根证书**: `android:networkSecurityConfig` 是 API 24 才生效的, 在 Android 5 上被忽略。
   系统信任库没有 ISRG Root X1/X2, 而 Let's Encrypt 的老交叉签名 (DST Root CA X3) 已过期 ——
   直接后果是大量站点握手失败。可行方向: 把根证书打进 APK, 用自定义 `X509TrustManager`
   装到 Ktor 的 OkHttp 引擎上; 或者在已 root 的机器上往 `/system/etc/security/cacerts/` 里塞
   (该机上有 Dirty COW, 能覆盖已有文件但建不了新文件)。
2. **BT / 图片验证码**: 见上面第 2 条, 需要按 `android-21` 重编原生库。
