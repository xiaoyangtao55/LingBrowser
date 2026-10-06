# 签名密钥说明

本目录用于放置 release 签名密钥库，**该目录内容不会提交到仓库**
（见根目录 `.gitignore`）。

## 本地构建

把密钥库放到 `keystore/ling-release.jks` 即可，`app/build.gradle.kts`
会自动发现并签名。口令默认取演示值，可用 Gradle 属性覆盖：

```bash
./gradlew :app:assembleRelease \
  -PLING_STORE_PASSWORD=你的口令 \
  -PLING_KEY_ALIAS=你的别名 \
  -PLING_KEY_PASSWORD=你的口令
```

## CI 构建（GitHub Actions）

在仓库 **Settings → Secrets and variables → Actions** 里配置：

| Secret | 说明 | 必填 |
|---|---|---|
| `LING_KEYSTORE_BASE64` | 密钥库文件的 Base64 编码 | 否* |
| `LING_STORE_PASSWORD` | 密钥库口令 | 否* |
| `LING_KEY_ALIAS` | 密钥别名 | 否* |
| `LING_KEY_PASSWORD` | 密钥口令 | 否* |

\* 不配置也能跑通：CI 会产出未签名（unsigned）的 APK，可用于验证编译。

生成 Base64（Windows PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\ling-release.jks")) | Set-Clipboard
```

生成 Base64（Linux / macOS）：

```bash
base64 -w0 keystore/ling-release.jks
```

## 生成新密钥库

```bash
keytool -genkeypair -v \
  -keystore keystore/ling-release.jks \
  -alias ling \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <口令> -keypass <口令> \
  -dname "CN=Ling Browser, O=Ling, C=CN"
```

> ⚠️ 密钥库请另行备份。丢失后就无法为已发布的应用推送更新了。
