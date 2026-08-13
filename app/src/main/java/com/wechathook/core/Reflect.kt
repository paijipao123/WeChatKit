package com.wechathook.core

import java.lang.reflect.Field

/**
 * 轻量反射辅助：遍历对象字段、查找字段/方法。
 * 用于替代对特定反射框架（如 reflekt）的依赖。
 */
object Reflect {

    private val fieldCache = HashMap<Class<*>, List<Field>>()

    private fun allFields(clazz: Class<*>): List<Field> {
        fieldCache[clazz]?.let { return it }
        val list = ArrayList<Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            try {
                c.declaredFields.forEach { f ->
                    try { f.isAccessible = true } catch (_: Throwable) {}
                    list.add(f)
                }
            } catch (_: Throwable) {}
            c = c.superclass
        }
        fieldCache[clazz] = list
        return list
    }

    /** 在 [root] 的字段树里找第一个类型为其子类型的字段值。 */
    fun findFieldByType(root: Any, assignableType: Class<*>): Any? {
        allFields(root.javaClass).forEach { f ->
            try {
                val v = f.get(root) ?: return@forEach
                if (assignableType.isAssignableFrom(v.javaClass)) return v
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 按字段名在树中查找字段值（递归父类）。 */
    fun findFieldByName(root: Any, name: String): Any? {
        allFields(root.javaClass).forEach { f ->
            if (f.name == name) {
                try { return f.get(root) } catch (_: Throwable) { return null }
            }
        }
        return null
    }
}
