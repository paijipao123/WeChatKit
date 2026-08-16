package simple.hook.wechat.core

import java.lang.reflect.Field
import java.util.IdentityHashMap

/**
 * 递归遍历对象字段图，查找满足 [signature] 条件的对象。
 * 用于从消息 holder 中定位微信消息对象，避免硬编码具体类名。
 */
object ReflectFieldWalker {

    private val fieldCache = HashMap<Class<*>, List<Field>>()
    private val visited = ThreadLocal.withInitial { IdentityHashMap<Any, Boolean>() }

    private fun fieldsOf(clazz: Class<*>): List<Field> {
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

    /**
     * 广度优先遍历 [root] 可达的对象图（最多 [maxDepth] 层），
     * 返回第一个满足 [signature] 的对象；找不到返回 null。
     */
    fun find(root: Any?, signature: (Any) -> Boolean, maxDepth: Int = 6): Any? {
        if (root == null) return null
        val seen = visited.get().also { it.clear() }
        return findRecursive(root, signature, 0, maxDepth, seen)
    }

    private fun findRecursive(
        obj: Any,
        signature: (Any) -> Boolean,
        depth: Int,
        maxDepth: Int,
        seen: IdentityHashMap<Any, Boolean>
    ): Any? {
        if (depth > maxDepth) return null
        if (seen.containsKey(obj)) return null
        seen[obj] = true

        if (isSkippable(obj)) return null
        if (signature(obj)) return obj

        // 优先字段遍历
        val fields = fieldsOf(obj.javaClass)
        for (f in fields) {
            val v = try { f.get(obj) } catch (_: Throwable) { continue } ?: continue
            val res = if (isCollectionOrArray(v)) {
                walkContainer(v, signature, depth + 1, maxDepth, seen)
            } else if (v.javaClass.isPrimitive || v is String || v is Number || v is Char || v is Boolean) {
                null
            } else {
                findRecursive(v, signature, depth + 1, maxDepth, seen)
            }
            if (res != null) return res
        }
        return null
    }

    private fun walkContainer(
        container: Any,
        signature: (Any) -> Boolean,
        depth: Int,
        maxDepth: Int,
        seen: IdentityHashMap<Any, Boolean>
    ): Any? {
        if (container is Collection<*>) {
            for (item in container) {
                if (item == null) continue
                val res = if (isSkippable(item)) null else findRecursive(item, signature, depth + 1, maxDepth, seen)
                if (res != null) return res
            }
        }
        return null
    }

    private fun isCollectionOrArray(v: Any): Boolean =
        v is Collection<*> || v is Map<*, *>

    private fun isSkippable(v: Any): Boolean =
        v is CharSequence || v is Number || v is Boolean || v is Character || v is android.graphics.drawable.Drawable || v is android.content.res.AssetManager
}
