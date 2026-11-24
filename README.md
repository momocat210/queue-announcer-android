# 叫号系统 · 安卓源码包

排队叫号系统的原生安卓实现（Java，无第三方依赖）。一台手机做**服务器端**（取号 + 打印 + 队列 + 播报），另一台或多台做**叫号端**（蓝牙遥控 + 显示），两台手机通过经典蓝牙 SPP 组网；叫号端在断连时可自动切单机模式应急。

- 版本：**v2.3**（versionCode 5）
- 包名：`com.qcaller.announcer`
- 最低系统：Android 5.0（API 21），目标 API 34
- 语言/构建：纯 Java，手工脚本构建（不依赖 Gradle）

---

## 一、快速开始

### 1. 准备环境

| 依赖 | 说明 |
|---|---|
| JDK | 8 或更高（脚本兼容 JDK 9+ 的 javac 参数差异） |
| Android SDK | 需 `platforms/android-34` 与 `build-tools/34.0.0` |
| zip | 打包 dex 用 |

SDK 安装（命令行工具）：

```bash
export ANDROID_HOME=$HOME/android-sdk
# 假设已把 commandlinetools 解压到 $ANDROID_HOME/cmdline-tools/latest
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platforms;android-34" "build-tools;34.0.0"
```

### 2. 构建

```bash
bash tools/build.sh      # 产出 build/out/aligned.apk（未签名）
bash tools/release.sh    # 构建 + 签名，产出 output/叫号系统-v2.3.apk
bash tools/test-queue.sh # 跑队列逻辑单元测试（无需真机）
```

脚本会自动探测 `$ANDROID_HOME` 下最新的 build-tools 与 platform；也可以用环境变量覆盖：

```bash
ANDROID_HOME=/opt/android-sdk bash tools/build.sh
KEYSTORE=/path/my.keystore KS_PASS=xxx KEY_PASS=xxx bash tools/release.sh
```

### 3. 签名密钥（重要）

**本仓库不包含签名密钥**（`.gitignore` 已排除 `*.keystore`），请用自己的密钥发布：

```bash
# 1. 生成自己的密钥（只需一次，务必备份）
keytool -genkeypair -keystore my.keystore -alias qcaller \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. 构建并签名
KEYSTORE=/path/to/my.keystore KS_PASS=你的库口令 KEY_PASS=你的密钥口令 \
  bash tools/release.sh
```

> **升级安装必须用同一个密钥签名**，否则手机会报「应用未安装 / 签名冲突」，只能先卸载旧版（队列数据会丢）。密钥请离线备份，切勿提交到公开仓库。

---

## 二、目录结构

```
叫号系统-源码-v2.3/
├── app/
│   ├── AndroidManifest.xml          权限、四个 Activity、竖屏锁定
│   ├── res/
│   │   ├── layout/                  四个页面布局
│   │   ├── drawable/                按钮、卡片、角标等 shape/vector
│   │   ├── values/                  颜色、字符串、主题
│   │   └── mipmap-*/                启动图标（含自适应图标）
│   └── src/com/qcaller/announcer/
│       ├── MainActivity.java        角色选择页：服务器端 / 叫号端
│       ├── ServerActivity.java      服务器端：取号、队列、叫号、打印
│       ├── CallerActivity.java      叫号端：遥控、显示、单机应急
│       ├── SettingsActivity.java    设置页：模板/语速/打印/蓝牙设备
│       ├── QueueStore.java          队列数据模型与持久化
│       ├── Cfg.java                 配置读写（SharedPreferences）
│       ├── Speaker.java             TTS 播报（含提示音、数字读法）
│       ├── BtLink.java              手机间蓝牙通道（SPP，JSON 换行分帧）
│       ├── Printer.java             喵喵机打印（位图渲染 + 协议封包）
│       └── PrintQueue.java          串行打印任务池（间隔 + 失败冷却）
├── test/
│   ├── src/QTest.java               队列逻辑测试（6 组场景 18 项断言）
│   └── stub/                        android.content / org.json 桩类
├── tools/                           build.sh / release.sh / test-queue.sh
├── art/icon-512.png                 应用图标大图
└── README.md
```

---

## 三、模块职责

| 模块 | 职责 |
|---|---|
| `QueueStore` | `take()` 发号、`callNext()` 顺序叫号、`callNumber()` 指定插队、`backToQueue()` 过号回队首、`complete()` 办结；`issued/current/waiting/doneCount` 持久化到 SharedPreferences |
| `Cfg` | 播报模板、起始号、位数、语速、音量、重复次数、提示音、屏幕常亮、播报端（服务器/叫号端/两端）、打印机 MAC、打印浓度、纸型、打印间隔、兼容模式 |
| `Speaker` | 系统 TTS 中文播报；数字默认逐位读（001 → 零零一），可切按数值读；播报前合成「叮咚」提示音 |
| `BtLink` | 服务器端 `listenUsingRfcommWithServiceRecord` 监听，叫号端 `createRfcommSocketToServiceRecord` 连接；收发改后台队列，写失败不阻塞 UI；服务器端支持**最多 4 台**叫号端接入并广播状态 |
| `Printer` | 把号单渲染成 384 点宽单色位图，按 `02|cmd|序号|长度|数据|CRC32|03` 封包发送；含 CRC 密钥握手、纸型、浓度、走纸 |
| `PrintQueue` | 单线程串行打印，两单之间间隔默认 5 秒（`printGapMs`），等走纸完成再断开；连续失败 30 秒冷却，避免每单都等连接超时 |
| `ServerActivity` | **上半屏为操作区**（取号 / 下一位 / 重播 / 办结 / 过号 / 队列点击），**下半屏为纯显示区**（当前叫号大字 + 统计），适配下半屏触摸损坏的旧手机 |
| `CallerActivity` | 按「下一个」发送 `{"t":"next"}`；显示服务器同步的队列列表；断连自动切单机模式，本机计数播报 |

### 蓝牙消息协议（JSON，按 `\n` 分帧）

| 方向 | 报文 | 含义 |
|---|---|---|
| 叫号端 → 服务器 | `{"t":"next"}` | 叫下一位 |
| 服务器 → 叫号端 | `{"t":"state","cur":2,"wait":3,"issued":5,"queue":[3,4,5],"ann":1}` | 同步当前号、等待人数、已发号、队列明细；`ann=1` 表示由叫号端出声 |

连接 UUID：`8f6a1b20-7c4d-4e93-9e51-2b7a5c9d1e33`（两机需先在系统蓝牙里配对）。

---

## 四、使用流程

1. 两台手机系统蓝牙互相配对；喵喵机也与服务器端手机配对
2. 服务器端：打开 App → 选「服务器端」→ 设置里「选择打印机」→ 打印测试页确认
3. 叫号端：打开 App → 选「叫号端」→ 设置里「选择对方手机」选服务器 → 返回主页自动连接
4. 取号台连点「取号」发号并出号单；任意叫号端按「下一个」由服务器统一顺序叫号

**多终端**：第二台叫号端装同一个 APK，同样连接同一台服务器即可；服务器顶部显示「叫号端 N 台在线」。若只希望一处出声，把设置里的播报端改成「服务器端」。

---

## 五、已知限制

- 喵喵机协议为逆向实现（SPP 通道，非 BLE），只适用于喵喵机 / Paperang 系列；不通用小票打印机。型号不接受密钥握手时，在设置里打开**兼容模式**
- 服务器端需保持前台运行（可锁屏，但别被系统清理），建议加入电池优化白名单
- 蓝牙组网与打印依赖真机环境，代码按 Android SPP 规范编写，未在真实设备上做自动化验证
- 目录结构为手工构建布局（`app/res` + `app/src`），不是 Gradle 标准布局

---

## 六、用 Android Studio 打开（可选）

本工程不含 Gradle 配置。若想用 IDE 开发：

1. 新建一个 Empty Views Activity 工程，包名填 `com.qcaller.announcer`，minSdk 21
2. 把 `app/res/` 内容拷到新工程 `app/src/main/res/`
3. 把 `app/src/com/qcaller/announcer/*.java` 拷到 `app/src/main/java/com/qcaller/announcer/`
4. 用本包 `AndroidManifest.xml` 里的 `<uses-permission>`、`<application>` 属性与 `<activity>` 声明覆盖新工程的清单（保留 `package` 由 Gradle 的 namespace 接管）
5. 编译即可；发布时用你自己的 keystore 签名以支持覆盖安装（见第一节第 3 点）
