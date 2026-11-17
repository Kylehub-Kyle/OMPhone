# 参与贡献

感谢愿意帮忙。这个项目很小，规则也简单。

## 环境

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | 34（compileSdk / targetSdk），最低运行 API 26 |
| Gradle | 8.7（用仓库自带的 `./gradlew`，不要用本机 Gradle） |

首次构建前复制模板并填写：

```bash
cp local.properties.example local.properties
```

```properties
sdk.dir=/path/to/android-sdk
ptp.client.guid=            # 32 位十六进制，自己的相机白名单值
```

它已被 `.gitignore` 排除，不会被提交。`ptp.client.guid` 属于设备专属凭据：
没有它也能编译、跑单测，但 App 连接相机时会提示「未配置客户端 GUID」。
**不要把这个值写进源码、文档、截图或 issue。**

## 构建与测试

```bash
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 协议线级测试 + 命名规则单测
```

提交前请保证 `testDebugUnitTest` 全绿。CI 会跑同样的两条命令。

## 代码约定

- Kotlin 官方风格（4 空格缩进），Compose 优先用无状态组件 + 上层持有状态
- 注释写「为什么」，不写「做了什么」；踩过的坑请在注释里留一句原因
- 协议层（`PtpIp.kt`）不做 IO 之外的业务判断；会话编排放 `OmCamera.kt`；UI 不直接碰 socket
- 新增行为请补单测：协议线级改动加到 `PtpWireTest.kt`，命名规则加到 `NamingTest.kt`

## 提交

```
feat: 横屏信息栏合并为一条
fix: 翻页后手势闭包仍指向上一张
docs: 补 1.6 版本说明
```

类型用 `feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `chore`。
一个提交只做一件事，说明写清「改了什么、为什么」。

## 隐私红线（重要）

以下内容**任何情况下都不要**提交到仓库，也不要贴进 issue：

- 原始抓包（`.pcapng`）、命令/事件字节流、逆向分析文档
- 客户端 GUID、相机序列号、局域网 IP、MAC、PTP 会话号
- 含真实照片的截图（先在手机里换成空白图再截）
- 签名文件、Token、个人路径

如果误提交了，不要只删文件——历史里仍然存在，需要重写历史或直接联系维护者处理。

## Pull Request

1. Fork 并新建分支：`feat/xxx`、`fix/xxx`
2. 保持改动聚焦，附上「改前 / 改后」的说明；UI 改动给截图（注意脱敏）
3. 描述里写清测试方式：跑了哪些单测、真机型号与 Android 版本
4. 等 CI 通过后由维护者合并

## 发布流程（维护者）

1. `app/build.gradle.kts`：`versionCode` +1、`versionName` 更新
2. `docs/releases/_TEMPLATE.md` 复制为 `docs/releases/v<版本>.md` 并填写
3. `CHANGELOG.md` 顶部追加该版本条目
4. 更新 `docs/releases/README.md` 目录表与 `README.md` 版本表
5. 打 tag `v<版本>`，在 GitHub Releases 附上 APK
