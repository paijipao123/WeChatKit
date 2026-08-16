# WeChatSimple(简易微信助手)

仅兼容 **微信 8.0.76** 的 LSPosed 模块,包名 `simple.hook.wechat`。

## 功能

- 隐藏消息头像(可选隐藏对方/自己/全部,可调消息间距)
- 聊天防撤回(自定义撤回提示)
- 朋友圈防删
- 圆形头像(全局,可调圆角)
- 消息时间显示(头像下方 / 气泡下方两种模式)
- 自动抢红包 / 自动收转账
- 签名绕过 / 平板模式(高风险,请小号测试)

设置页直接注入微信,配置自动保存。

## 上游(Upstream)

本项目的实现大量参考以下开源项目,特此致谢:

- [Ujhhgtg/WeKit](https://github.com/Ujhhgtg/WeKit) —— 圆角头像(tn1.e 圆角参数)、消息时间(onBindView + ChattingDataAdapter.getItem + MsgInfo.field_createTime)、消息绑定方法定位("MicroMsg.MvvmChattingItem"/"[onBindView]")
- [cwuom/WeKit](https://github.com/cwuom/WeKit) —— 整体结构参考
- [HdShare/WAuxiliary_Plugin](https://github.com/HdShare/WAuxiliary_Plugin) —— 插件生态参考
- [Ujhhgtg/funbox_deobf](https://github.com/Ujhhgtg/funbox_deobf) —— 微信反混淆参考
- [gaigebeckmanChristinaJames/read-receipt-tracker](https://github.com/gaigebeckmanChristinaJames/read-receipt-tracker) —— 已读回执参考

## 构建

GitHub Actions 自动构建 APK(debug/release),产物在 Actions 的 artifacts。

## 免责声明

仅供学习交流使用。自动抢红包/转账、签名绕过、平板模式等涉及高风险行为,请自行承担后果,建议小号测试。
