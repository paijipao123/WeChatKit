# WeChatKit

一个面向**微信 (com.tencent.mm)** 的 **LSPosed 模块**，采用 **DexKit 动态特征适配**架构，尽量跨微信版本可用。

> ⚠️ 仅供学习研究。Hook 第三方 App 可能违反其服务条款，自动收红包 / 自动收款等网络层功能存在**账号风控风险**，请谨慎评估后使用。

---

## 功能总览

| 功能 | 说明 | 默认 |
|------|------|------|
| **隐藏消息头像（紧凑）** | 去掉左右消息头像，气泡间距与微信原版一致 | 开 |
| **聊天防撤回** | 对方撤回的消息保留显示 | 开 |
| **朋友圈防删** | 阻止他人删除朋友圈后消失 | 开 |
| **已读回执** | 配合 `read-receipt-tracker` 服务显示"已读 X 人" | 关 |
| **自动抢红包** | 后台 hook 自动拆包/开包（不跳页） | 关 |
| **自动收款（转账）** | 后台 hook 自动确认收款（不跳页） | 关 |

---

## 核心设计：DexKit 动态适配 + 版本兼容框架

微信每次更新都可能混淆内部类名，硬编码类名会很快失效。本模块通过 **DexKit**
按**稳定的特征字符串**（如网络请求名 `MicroMsg.NetSceneReceiveLuckyMoney`、
日志模板 `MicroMsg.SDK.XmlParser`、`MvvmChattingItem.onBindView` 等）在 dex 中
动态检索目标类/方法，再用 XposedHelpers 完成 Hook，从而对微信小版本升级有
较强的自适应性。

### 从最新版向下兼容（多路符号定位）

核心思路：**每个需要定位的符号提供"候选特征链"，逐个尝试，第一个命中的即用**。

- **特征字符串优先**：协议名 / 日志 TAG / cgi 路径（如
  `/cgi-bin/mmpay-bin/transferoperation`）在微信多个版本中保留，是最可靠的定位锚点；
- **硬编码类名回退**：已知的旧版类名（如 `MaskLayout`、`AvatarImageView`）作为备选；
- **网络架构双模式**：
  - 新版（8.0.7x）：`NetSceneBase.doScene(dispatcher, callback)` +
    hook `dispatch` 捕获真实 dispatcher；
  - 旧版（8.0.x 之前）：`NetSceneQueue.doScene(netScene)`（legacy 模式自动回退）；
- **微信版本检测**：启动时读取版本号并输出日志，便于按版本调试；
- **功能级降级**：每个功能独立定位，某个功能在某版本定位失败不影响其他功能。

关键文件：
- `app/src/main/java/com/wechathook/core/DexKitFinder.kt`
- `app/src/main/java/com/wechathook/core/SymbolResolver.kt`

> 已实测适配：**8.0.71（平板）/ 8.0.76（手机）**。8.0.76 的核心架构与 8.0.71 一致，
> 仅红包请求类被混淆改名（i6→l6、c6→f6），DexKit 特征定位自动适配。
> 注意：红包/转账的**请求构造函数参数**在不同微信版本可能有差异，
> 模块采用"带参构造失败自动回退"策略；若某版本构造参数变化导致失效，
> 可在日志中看到定位结果，按新版本的构造函数调整。

---

## 目录结构

```
WeChatKit/
├── .github/workflows/build-apk.yml   # GitHub Actions 自动构建
├── gradle/libs.versions.toml         # 依赖版本目录
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml       # LSPosed 模块元数据 + 作用域
│       ├── assets/xposed_init        # Hook 入口声明
│       └── java/com/wechathook/
│           ├── HookEntry.kt          # LSPosed 入口，注册所有功能
│           ├── core/                 # 核心框架（DexKit、网络、反射、配置）
│           └── features/             # 各功能实现
│               ├── chat/             # 去头像、防撤回
│               ├── moments/          # 朋友圈防删
│               ├── readreceipt/      # 已读回执
│               └── money/            # 自动抢红包、自动收款
```

---

## 如何构建

### 方式一：GitHub Actions 自动构建（推荐）

1. 把本项目推送到你自己的 **GitHub 仓库**。
2. 在仓库 **Actions** 页可看到 `Build APK` 工作流：
   - 有 `push` / `pull_request` 时自动触发；
   - 也可以点 **Run workflow** 手动触发。
3. 构建完成后，在 Actions 输出的 **Artifacts** 下载 `wechathook-debug.apk` 或 `wechathook-release.apk`。

### 方式二：本地构建

需要 JDK 17 + Android SDK：

```bash
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/
```

> 若本地没有 `gradlew` / `gradle-wrapper.jar`，可执行 `gradle wrapper --gradle-version 8.2` 生成，
> 或在 Linux/Mac 上先执行（CI 里已内置自动生成逻辑）。

---

## 安装与启用

1. 将 APK 安装到已 **Root** 且装有 **LSPosed** 的设备。
2. 打开 LSPosed 管理器 → 模块 → 启用 **WeChatKit**。
3. 在模块作用域**勾选微信 `com.tencent.mm`**。
4. **强制停止微信**后重新打开。
5. 查看日志：LSPosed 管理器 → 日志，搜索 `WeChatKit`。

---

## 配置说明

### 隐藏消息头像
自动生效。如需恢复，在设置或配置文件中关闭 `feat_hide_avatar`。

### 已读回执（需部署 read-receipt-tracker 服务）
1. 部署独立的 [read-receipt-tracker](https://github.com/gaigebeckmanChristinaJames/read-receipt-tracker) 服务。
2. 在模块设置中填写服务器地址（如 `http://192.168.1.10:8080`）。
3. 启用「已读回执」，在你发出的文本消息下方会定期刷新"已读 X 人"。

### 自动抢红包 / 自动收款
默认关闭（防误开导致风控）。如确需使用：
- 在设置中启用对应开关；
- 自动抢红包可调拆包延迟（默认 800ms，可加随机抖动）。

---

## 免责声明

本项目**仅用于学习与研究** DexKit / Xposed / LSPosed 框架，请勿用于任何违反
法律法规或第三方服务条款的用途。使用自动抢红包、自动收款等功能的风险由使用者自行承担。
