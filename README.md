# -app · 酒馆 Android App

把 **SillyTavern 1.19.0** 完整装进**单个 APK**：应用内 WebView 直连进程内 Node 服务，不跳外部浏览器、不用电脑常开、卸载零残留。

## 特性

- **单 APK 安装即用**：内置酒馆服务端 + 前端 + 扩展，以及 Android 版 Node.js 22 运行时（arm64-v8a / x86_64），由自研 JNI 桥在**同一进程**启动
- **数据全部私有**：角色卡、聊天记录、设置、`config.yaml` 都写在应用私有 `noBackupFilesDir`；仅申请 `INTERNET` / `ACCESS_NETWORK_STATE`，`allowBackup=false`，卸载由系统自动清除
- **不占本地电脑**：模型推理走酒馆设置里配置的云端 API（DeepSeek / OpenAI / OpenRouter 等），手机只负责界面与轻量服务
- **已适配 Android 16（SDK 36）**：纯 C JNI 桥 + `dlsym` 调用 `node::Start`，零 IE 模型 TLS 重定位
- **跳过启动期 webpack 编译**：复用 npm 发布包自带的预编译 `lib.js`，避免手机上编译卡死

## 安装

Android 10+ / arm64（另含 x86_64），建议 6GB 内存、可用存储 ≥ 1.5GB；允许「未知来源安装」。
**首次启动需解压约 2.6 万个文件到私有目录，约 10~30 秒**，停在启动屏等待即可。

> `SillyTavern.apk`（约 230MB）是构建产物，超过 GitHub 单文件 100MB 上限，**不纳入 git**。
> 安装包直达：[Releases / v1.0.4 / SillyTavern.apk](https://github.com/cdxDNRF/-app/releases/download/v1.0.4/SillyTavern.apk)，也可用 `build.sh` 自行构建。

## 目录

| 路径 | 说明 |
|---|---|
| `build.sh` | 纯手工工具链构建：aapt2 → javac → d8 → NDK clang → zip → zipalign → apksigner（不用 Gradle） |
| `app/src/com/xdu/tavern/MainActivity.java` | 原生壳：启动屏/日志、WebView、可拖动悬浮菜单、文件选择、下载接管、资源版本管理、数据隔离 |
| `app/src/com/xdu/tavern/NodeBridge.java` | Java 侧加载 `libnode.so` 与 JNI 桥 |
| `app/jni/node_bridge.c` | 纯 C JNI 桥，运行时 `dlsym` 调用 `node::Start` |
| `app/assets/bootstrap.cjs` | 移动端启动引导：固定 cwd/环境变量、屏蔽 `execSync`、加载 ESM 的 `server.js` |
| `app/patches/webpack-serve.js` | 覆盖官方中间件，跳过启动前的 webpack 编译 |
| `app/res/`、`app/icon-src/` | 全套应用图标（五档密度 + 圆形 + Android 8 自适应图标）与素材原图 |
| `app/AndroidManifest.xml` | 权限、备份与卸载策略、明文流量配置 |
| `使用说明.md` | 安装、API 配置、更新机制、排障与合规说明 |
| `00-规划方案.md` | 最初的技术调研与分阶段实施方案 |
| `图标预览.png` | 三种桌面遮罩下的图标效果预览 |

## 复现构建

依赖：JDK 17+、Android SDK（`platforms;android-34`、`build-tools;34.0.0`、`ndk;26.3.11579264`）、zip、keytool。

准备素材并放到 `.stbuild/`（或用 `STBUILD_DIR` 指向别的目录）：

1. `st/` —— SillyTavern 1.19.0 发布包（`npm pack sillytavern@1.19.0` 解包后 `npm install --omit=dev`），需含 `dist/_webpack/*/output/lib.js`
2. `runtime/libnode/bin/{arm64-v8a,x86_64}/libnode.so` —— Android 版 Node 22 预编译运行时

```bash
./build.sh
# 可选覆盖：STBUILD_DIR=/path/to/materials ANDROID_HOME=/opt/android-sdk OUT_DIR=/tmp/out ./build.sh
```

产物为 `SillyTavern.apk`。首次构建会用 `keytool` 自动生成 `app/tavern.jks`（**已被 .gitignore 排除**）；后续升级必须用同一把密钥签名才能覆盖安装并保留数据。

## 版本

`v1.0.4`：更换应用图标全套资源；此前依次修复了服务启动超时（跳过 webpack）、Android 16 的 IE TLS 加载失败、悬浮菜单遮挡顶栏等问题。详见 `使用说明.md`。

## 许可与合规

SillyTavern 本体为 **AGPL-3.0**，本项目壳代码同样以 AGPL-3.0 开源。若分发 APK，需同时提供对应源码。

