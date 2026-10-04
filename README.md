# CMCC No Update

一个用于阻止中国移动 App 升级弹窗的 LSPosed 模块。

## 适配范围

- 仅适配：中国移动 `12.0.9`
- 目标包名：`com.greenpoint.android.mc10086.activity`
- 模块 API：LSPosed / libxposed API 102
- 当前验证版本：`1.8.2-clean`

其他版本的中国移动 App 没有测试，不保证可用，也不建议直接套用。

## 功能说明

模块通过 LSPosed 静态作用域加载到中国移动 App 进程中，针对已知升级接口返回安全的“无需更新”数据，并保留极窄范围的升级弹窗文本兜底处理。

当前版本已移除所有日志输出功能，不会写入 `/sdcard/Download/CmccNoUpdateHook.log`，也不会主动输出 Android Log 或 LSPosed 日志。

## 使用方式

1. 安装 `outputs/cmcc-noupdate-api102-release.apk`。
2. 在 LSPosed 中启用模块。
3. 确认作用域包含 `com.greenpoint.android.mc10086.activity`。
4. 强行停止中国移动 App。
5. 重新打开中国移动 App 测试。

通常不需要重启手机，但必须重启目标 App 进程。

## 构建方式

本项目使用 Windows 本地 Android SDK 工具链构建。直接运行：

```powershell
.\build-diagnostic.ps1
```

构建产物输出到：

```text
outputs/cmcc-noupdate-api102-release.apk
```

脚本默认使用：

- Android SDK：`F:\flutterSDK\android-sdk-windows`
- Build Tools：`35.0.0`
- libxposed API：`C:\Users\theRainight\.codex\skills\lsposed-api102-module\assets\libxposed-api-102.0.0-classes.jar`

如你的环境路径不同，需要自行修改 `build-diagnostic.ps1`。

## 注意事项

- 本模块仅为个人学习和兼容性研究用途。
- 仅针对中国移动 `12.0.9` 做过实机验证。
- 不保证对未来版本、其他渠道包、定制包有效。
- 请不要在不了解风险的情况下批量分发或用于商业用途。
- 如果目标 App 更新后接口、弹窗布局或加固逻辑变化，本模块可能失效。
- 遇到异常时，建议先停用模块并强行停止目标 App，再重新验证。

## 项目结构

```text
build-diagnostic.ps1
outputs/
  cmcc-noupdate-api102-release.apk
work/src/main/
  AndroidManifest.xml
  java/cn/codex/lsposed/cmccnoupdate/CmccNoUpdateModule.java
  resources/META-INF/xposed/
    java_init.list
    module.prop
    scope.list
```

## 许可

MIT License
