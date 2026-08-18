# HDResourceExtractor

Android 本地网页高清资源提取器（MVP）。

## 功能

- 从浏览器/小红书等 App 的“分享”菜单接收网页链接
- 支持手动粘贴网页链接
- 使用 Android WebView 打开网页
- 收集页面加载过程中请求的图片、视频和媒体资源 URL
- 通过页面 JavaScript 补充提取 `img` / `video` / `source` / `performance` 资源
- 在列表中显示资源地址
- 点击资源调用 Android `DownloadManager` 下载
- 全程在手机本地运行，不依赖自建服务器

## 当前界面

采用方案 A：操作区位于页面上半区，状态栏下额外保留 28dp 呼吸空间；不吸顶，也不垂直居中。主操作为“打开网页”和“提取高清资源”。

> 注意：只能提取 WebView 当前会话实际能够访问到的资源；不会绕过登录、访问控制、DRM 或加密流。

## GitHub Actions

推送到 `main`、创建 PR，或手动运行 `Build APK` 工作流后，会生成 Debug APK artifact：`HDResourceExtractor-debug-apk`。

APK 构建路径：`app/build/outputs/apk/debug/app-debug.apk`
