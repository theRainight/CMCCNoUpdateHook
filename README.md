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

## 使用方式

1. 安装 `outputs/cmcc-noupdate-api102-release.apk`。
2. 在 LSPosed 中启用模块。
3. 确认作用域包含 `com.greenpoint.android.mc10086.activity`。
4. 强行停止中国移动 App。
5. 重新打开中国移动 App 测试。

通常不需要重启手机，但必须重启目标 App 进程。

## 构建方法

需要准备：

- JDK（能执行 `javac` 命令）
- Android SDK Platform 35
- Build Tools 35.0.0（含 `aapt2`、`d8`、`zipalign`、`apksigner`）
- libxposed API 102 的 `classes.jar`（从 [LSPosed](https://github.com/LSPosed/LSPosed/releases) 官方 release 获取 `api-102.0.0.aar` 后解压取得）

构建流程（Windows `cmd` / PowerShell 通用）：

```text
1. javac -encoding UTF-8 -source 8 -target 8 ^
       -cp "<android.jar>;<libxposed-api-102.jar>" ^
       -d <classes_dir> ^
       work\src\main\java\cn\codex\lsposed\cmccnoupdate\CmccNoUpdateModule.java

2. cd <classes_dir> && jar cf ..\module-classes.jar . && cd ..

3. d8 --lib <android.jar> --min-api 23 ^
       --output <out_dir> module-classes.jar

4. aapt2 compile --dir work\src\main\res -o <out_dir>\compiled.zip

5. aapt2 link -o unsigned.apk ^
       --manifest work\src\main\AndroidManifest.xml ^
       -I <android.jar> ^
       <out_dir>\compiled.zip ^
       --min-sdk-version 23 --target-sdk-version 35 ^
       --version-code 15 --version-name 1.8.2-clean ^
       --auto-add-overlay

6. jar uf unsigned.apk -C work\src\main\resources META-INF

7. jar uf unsigned.apk -C <out_dir> classes.dex

8. zipalign -p -f 4 unsigned.apk aligned.apk

9. apksigner sign --ks <keystore> --ks-pass pass:<pwd> --key-pass pass:<pwd> ^
        --out outputs\cmcc-noupdate-api102-release.apk aligned.apk

10. apksigner verify --verbose outputs\cmcc-noupdate-api102-release.apk
```

任意 `<...>` 占位符请替换为你本地实际路径。`work\src\main\resources\META-INF\xposed\{module.prop,scope.list,java_init.list}` 决定模块在 LSPosed 中的注册信息，构建时必须包含。

构建产物统一输出到 `outputs/cmcc-noupdate-api102-release.apk`。

## 注意事项

- 本模块仅为个人学习和兼容性研究用途。
- 仅针对中国移动 `12.0.9` 做过实机验证。
- 不保证对未来版本、其他渠道包、定制包有效。
- 请不要在不了解风险的情况下批量分发或用于商业用途。
- 如果目标 App 更新后接口、弹窗布局或加固逻辑变化，本模块可能失效。
- 遇到异常时，建议先停用模块并强行停止目标 App，再重新验证。

## 项目结构

```text
outputs/
  cmcc-noupdate-api102-release.apk
work/src/main/
  AndroidManifest.xml
  java/cn/codex/lsposed/cmccnoupdate/CmccNoUpdateModule.java
  res/values/strings.xml
  resources/META-INF/xposed/
    java_init.list
    module.prop
    scope.list
```

## 许可

MIT License
