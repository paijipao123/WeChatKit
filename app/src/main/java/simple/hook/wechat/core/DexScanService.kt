package simple.hook.wechat.core

/**
 * 功能适配搜索（微信进程内执行）。
 *
 * 参考 WeKit DexResolver：遍历所有搜索项，用 DexKit 定位类名/方法，
 * 成功写入 DexCache，失败记录字符串；UI 层显示进度与失败列表。
 */
object DexScanService {

    /** 搜索项。 */
    class Item(
        val name: String,
        val isMethod: Boolean,
        val strings: Array<String>,
        val declared: String? = null
    )

    /** 全部搜索项（与各 Feature 使用的查询一致）。多组候选依次尝试。 */
    fun allItems(): List<Item> = listOf(
        Item("消息绑定方法(onBindView)", true, arrayOf("[onBindView]")),
        Item("聊天数据适配器", false, arrayOf("MicroMsg.ChattingDataAdapterV3")),
        Item("圆形头像-加载方法", true, arrayOf("MicroMsg.AvatarDrawable")),
        Item("防撤回-解析方法", true, arrayOf("MicroMsg.SDK.XmlParser", "[ %s ]")),
        Item("防撤回-XmlParser 类", false, arrayOf("MicroMsg.SDK.XmlParser")),
        Item("防撤回-撤回方法", true, arrayOf("doRevokeMsg")),
        Item("自动收红包-接收类", false, arrayOf("MicroMsg.NetSceneReceiveLuckyMoney")),
        Item("自动收红包-拆包类", false, arrayOf("MicroMsg.NetSceneOpenLuckyMoney")),
        Item("自动收转账-操作类", false, arrayOf("/cgi-bin/mmpay-bin/transferoperation")),
        Item("平板模式-CgiCheckLoginAsPad", true, arrayOf("MicroMsg.CgiCheckLoginAsPad")),
        Item("平板模式-登录按钮", true, arrayOf("loginAsOtherDeviceBtn")),
    )

    class Result(
        val name: String,
        val ok: Boolean,
        val detail: String = ""
    )

    /** 执行全部搜索。回调在主线程以外的任意线程（调用方负责切线程）。 */
    fun scanAll(
        classLoader: ClassLoader,
        onProgress: (done: Int, total: Int, current: String) -> Unit,
        onItem: (Result) -> Unit
    ): List<Result> {
        val items = allItems()
        val results = mutableListOf<Result>()
        DexKitFinder.with(classLoader) { finder ->
            items.forEachIndexed { i, item ->
                onProgress(i, items.size, item.name)
                try {
                    when {
                        item.isMethod -> {
                            val methods = finder.findMethodsByStrings(
                                classLoader,
                                declaredClassName = item.declared,
                                onlyPackages = listOf("com.tencent.mm"),
                                strings = item.strings
                            )
                            if (methods.isEmpty()) {
                                Logger.w("DexScan: 失败 ${item.name} -> ${item.strings.joinToString()}")
                                val r = Result(item.name, false, "未找到方法: ${item.strings.joinToString()}")
                                results += r
                                onItem(r)
                            } else {
                                // 缓存第一条（通常唯一）
                                val m = methods.first()
                                val value = m.declaringClass.name + "|" + m.name + "|" +
                                    m.parameterTypes.joinToString(",") { it.name }
                                DexCache.put(
                                    DexCache.keyForMethod(item.strings, item.declared), value
                                )
                                Logger.i("DexScan: 命中 ${item.name} -> ${m.declaringClass.name}#${m.name}")
                                val r = Result(item.name, true, m.declaringClass.name + "#" + m.name)
                                results += r
                                onItem(r)
                            }
                        }
                        else -> {
                            val clsName = finder.findClassNameByStrings(*item.strings)
                            if (clsName == null) {
                                Logger.w("DexScan: 失败 ${item.name} -> ${item.strings.joinToString()}")
                                val r = Result(item.name, false, "未找到类: ${item.strings.joinToString()}")
                                results += r
                                onItem(r)
                            } else {
                                DexCache.put(DexCache.keyForClass(item.strings), clsName)
                                Logger.i("DexScan: 命中 ${item.name} -> $clsName")
                                val r = Result(item.name, true, clsName)
                                results += r
                                onItem(r)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    val r = Result(item.name, false, "异常: ${t.message}")
                    results += r
                    onItem(r)
                }
            }
        }
        onProgress(items.size, items.size, "完成")
        return results
    }
}
