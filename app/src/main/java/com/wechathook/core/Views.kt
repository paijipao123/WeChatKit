package com.wechathook.core

import android.view.View
import android.view.ViewGroup

/**
 * View 树工具。
 */
object Views {

    /** 深度优先遍历 View 树，对每个 View 调用 [visit]（含根）。 */
    @JvmStatic
    fun walk(root: View?, visit: (View) -> Boolean) {
        var v: View? = root ?: return
        if (visit(v)) return
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), visit)
            }
        }
    }

    /** 在 [root] 子树中查找第一个指定 name 的 View。 */
    @JvmStatic
    fun findByName(root: View, name: String): View? {
        var found: View? = null
        walk(root) { v ->
            if (v.javaClass.name == name) { found = v; true } else false
        }
        return found
    }
}
