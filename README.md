# Frigate WebView App

将 [Frigate](https://frigate.video/) NVR 监控系统的网页版封装为 Android 应用，通过内置 WebView 打开 Frigate 服务器页面，支持 HTTPS 自签名证书、自动登录、服务器切换等功能。

## 功能特性

- **服务器地址输入页** — 首次打开输入 Frigate 服务器地址（默认 `https://192.168.6.14:8971/`），地址自动保存
- **自动连接** — 再次打开 App 自动跳转到最后一次连接的服务器，无需重复输入
- **HTTPS 自签名证书** — 自动信任自签名证书，内网 HTTPS 服务器（如 `https://192.168.x.x`）直接访问
- **自动登录** — 自动记住用户名和密码，下次打开 App 自动填充并登录
- **切换服务器按钮** — 在 Frigate 登录页面注入"切换服务器"按钮，点击后清除所有数据（Cookie、凭据、缓存）返回地址输入页
- **网络错误处理** — 服务器连不上时弹窗提示，可选择重试或切换服务器
- **完整浏览器行为** — JavaScript、Cookie、DOM Storage、文件上传/下载、全屏视频、地理位置等全部支持

## 环境要求

- Android 7.0 (API 24) 及以上
- 编译需要：
  - JDK 17
  - Android SDK 34 (Android 14)
  - Gradle 8.9+

## 编译

### 命令行编译

```bash
cd ServerWebViewApp
./gradlew.bat assembleDebug       # Windows
./gradlew assembleDebug           # macOS / Linux
```

生成的 APK 在：

```
app/build/outputs/apk/debug/app-debug.apk
```

> Debug APK 使用 debug 签名，可以直接安装，但不能上架应用商店。

### Android Studio 编译（推荐）

1. 下载安装 [Android Studio](https://developer.android.com/studio)
2. `File → Open` 选择 `ServerWebViewApp` 文件夹
3. 等待 Gradle 同步完成
4. 连接 Android 手机（开启 USB 调试）或启动模拟器
5. 点击 ▶ Run 按钮编译并安装

## 使用说明

### 首次使用

1. 安装 APK 到手机
2. 打开 App，输入 Frigate 服务器地址（如 `https://192.168.1.100:8971`）
3. 点击「连接」
4. 在打开的 Frigate 登录页输入用户名和密码，点击登录
5. 登录成功后，App 自动记住服务器地址和登录凭据

### 日常使用

打开 App 即自动连接服务器并自动登录，直接进入 Frigate 主界面。

### 切换服务器

在 Frigate 登录页面，登录按钮下方有一个「切换服务器」按钮：
- 点击后清除所有保存的数据（服务器地址、用户名、密码、Cookie、缓存）
- 返回服务器地址输入页，可输入新的服务器地址

### 网络不通时

如果服务器无法连接（关机、网络不通等），15 秒后弹窗提示：
- **重试** — 重新连接当前服务器地址
- **切换服务器** — 清除数据，返回地址输入页

## 项目结构

```
ServerWebViewApp/
├── app/
│   ├── build.gradle                          # 应用构建配置
│   └── src/main/
│       ├── AndroidManifest.xml               # 清单文件（权限、网络配置）
│       ├── java/com/serverwebview/app/
│       │   ├── MainActivity.java             # 服务器地址输入页
│       │   └── WebViewActivity.java          # WebView 主页面
│       └── res/
│           ├── drawable/
│           │   ├── ic_launcher_foreground.xml # Frigate 军舰鸟图标
│           │   └── ic_launcher_background.xml # 图标背景
│           ├── values/
│           │   ├── strings.xml               # 应用名称
│           │   └── styles.xml                # 主题样式
│           └── xml/
│               └── network_security_config.xml # HTTPS 证书信任配置
├── build.gradle                              # 项目构建配置
├── settings.gradle
├── gradle/wrapper/                           # Gradle Wrapper
└── gradlew / gradlew.bat                     # Gradle 启动脚本
```

## 技术要点

### HTTPS 自签名证书处理

- `WebViewClient.onReceivedSslError()` 中调用 `handler.proceed()` 放行所有 SSL 错误
- `network_security_config.xml` 信任用户安装的 CA 证书，允许内网 IP 明文流量
- `AndroidManifest.xml` 中 `android:usesCleartextTraffic="true"`

### 自动登录实现

- 页面加载完成后（`onPageFinished`）注入 JavaScript
- JS 使用 `setInterval` 轮询（每 500ms）+ `MutationObserver` 监听 DOM 变化，适配 React 异步渲染
- 找到 `input[type=password]` 后自动填充用户名密码并提交
- 通过 `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set` 设置值，兼容 React/Vue
- 自动登录只在首次连接时触发，登出后不会重复自动登录

### 凭据捕获

- JS 监听表单 `submit` 事件、按钮 `click` 事件、回车键
- 通过 `@JavascriptInterface` 的 `AndroidBridge.saveCredentials()` 回调 Java 层
- 凭据保存在 `SharedPreferences` 中

### 切换服务器按钮注入

- 在 Frigate 登录页面，通过 JS 动态创建 `<button>` 元素
- 按钮样式从登录按钮复制（圆角、字体大小、padding），做成轮廓型次要按钮
- 追加到表单容器末尾，不影响登录按钮的布局
- 点击后调用 `AndroidBridge.switchServer()` 触发 Java 层清除操作

## 修改默认服务器地址

编辑 `MainActivity.java` 第 23 行：

```java
private static final String DEFAULT_URL = "https://你的服务器地址:端口/";
```

## 许可

本项目仅供个人使用。Frigate 及其 Logo 归 [Frigate, Inc](https://frigate.video/) 所有。
