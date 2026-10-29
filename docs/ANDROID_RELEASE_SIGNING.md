# Android 交付 APK 的固定签名（V1.6）

## 为什么需要它

安卓只允许**同一 applicationId + 同一签名证书**的 APK 覆盖安装。
用 debug keystore 签出来的 APK：

* 每台开发机各一把，换一台机器就装不上；
* 有效期只有 30 天（`~/.android/debug.keystore` 默认），过期后签名校验会出问题；
* 名字和交付 APK 一样，用户分不清装的是哪一个。

所以日常交付必须由**同一把长期密钥**签。本文档说明这把密钥放在哪、怎么用、
以及"不放进仓库"由什么保证。

## 密钥在哪

密钥**不在仓库里**，也永远不会在：

| 位置 | 内容 |
| --- | --- |
| `~/.android-keys/android-mcp-hub-release.jks` | PKCS12 keystore，别名 `androidmcp`，RSA 2048，10000 天 |
| `~/.android-keys/android-mcp-hub-release.properties` | 四个环境变量（`chmod 600`） |
| GitHub Secrets | `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` |

证书 SHA-256：

```
88:AAB7:10FA:AC4B:E855:F93D:7FB9:FBD7:D854:861E:8F7A:1930:D262:5F4A:0034:4FFC:4C
```

> 备份提醒：这把密钥**丢了就再也签不出能覆盖安装的 APK**（只能让用户卸载重装）。
> 请把 keystore 与口令离线备份（密码管理器 / 加密 U 盘），但**不要**提交到任何仓库。

## 本地构建交付 APK

```bash
cd android
set -a; . "$HOME/.android-keys/android-mcp-hub-release.properties"; set +a
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`build.gradle.kts` 读取四个环境变量：

| 变量 | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_FILE` | keystore 绝对路径（与下一个二选一） |
| `ANDROID_KEYSTORE_BASE64` | keystore 的 base64（CI 用；解到 `build/signing/release.jks`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 别名 |
| `ANDROID_KEY_PASSWORD` | 密钥口令，缺省时与 keystore 口令相同 |

**没配置签名时 `assembleRelease` 会直接失败**，而不是产出一个 unsigned APK ——
后者装到手机上只会得到"应用未安装"，失败被推迟到用户手里。

核对签名：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## CI 构建交付 APK

`.github/workflows/android-release-apk.yml`，**只能手动触发**：

1. 从 Secret 解出 keystore 到 `$RUNNER_TEMP`（不进入工作区，不会被 artifact 收走）；
2. 构建一次 → 记录证书 SHA-256；
3. `clean` 后**再构建一次** → 记录证书 SHA-256；
4. 两次证书不一致就**让整次运行变红**；
5. 在模拟器上先装第一份、再 `adb install -r` 第二份，证明可覆盖安装；
6. 上传 `Android-MCP-Hub-release-apk` artifact（含两份 APK 与 SHA-256）。

## Debug 与交付 APK 的区别

| | Debug | Release（交付） |
| --- | --- | --- |
| 应用名 | `MCP Hub DEV` | `Android MCP Hub` |
| versionName | `1.6.0-debug` | `1.6.0` |
| 签名 | 本机 debug keystore | 固定发布密钥 |
| 能否互相覆盖 | **不能** | — |

从旧的 debug 签名版本切换到交付 APK **必须先在系统里卸载**（数据会丢），
因为两者签名不同，安卓不允许覆盖。之后所有版本都用同一把密钥，可以一直覆盖升级。

## 用户安装

```bash
adb install -r Android-MCP-Hub-release.apk
```

或在手机上直接打开 APK 安装（同一签名会提示"更新"而不是"重新安装"）。
