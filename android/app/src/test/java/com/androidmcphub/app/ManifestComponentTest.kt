package com.androidmcphub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Gate 0.2：挡住「Manifest 里的类名与实际编译出来的类名不一致」这一类错误。
 *
 * 触发这个测试的是一次真实的闪退：Gate 0.1 的 APK 点图标立刻回桌面、界面完全不出现。
 * 原因是 `HubApplication.kt` 里写了 `package com.androidmcphub.app.ui`（文件却在 app/ 目录下），
 * 编译出来是 `com.androidmcphub.app.ui.HubApplication`，而 Manifest 要的是
 * `com.androidmcphub.app.HubApplication` —— 系统建进程时找不到类，直接 ClassNotFoundException。
 *
 * 这类错误的特点是**编译期全绿**：Kotlin 不要求目录与包名一致，`assembleDebug` 不报错，
 * 别的单测也不报错（它们都直接 import 类，不经过 Manifest 的字符串解析）。
 * 所以必须专门盯着"Manifest 里的字符串 → 真的编译出来的类"这条线。
 *
 * 这个测试查两件事：
 *  1. Manifest 里每个组件（application/activity/service/receiver/provider）的 `android:name`
 *     解析出来的类，在**本次编译产物**（测试运行时 classpath 上的 `.class` 资源）里存在。
 *     注意是查 classpath 资源而不是 `Class.forName` —— 后者会去加载父类 `android.app.Application`，
 *     在 JVM 上会 NoClassDefFoundError，那就变成"测了另一件事"。
 *  2. 每个 Kotlin 源文件声明的 package 与它所在的目录一致（这条是为了在 ① 失败时给出
 *     精确到文件的原因，而不是只报一句"类不存在"）。
 *
 * 这个测试只覆盖 `src/main` 里我们自己写的组件。**依赖库合并进来的组件**（AndroidX 的
 * provider/receiver）不看 —— 那部分交给 `tools/verify_manifest_classes.py`，
 * 它读的是**真实产物**（APK 里编译过的二进制 Manifest + dex 的 class_defs 表）。
 */
class ManifestComponentTest {

    private val componentTags = listOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider",
    )

    @Test
    fun `Manifest 里声明的组件类都真的编译出来了`() {
        val manifest = findSourceManifest()
        val appPackage = BuildConfig.APPLICATION_ID
        val missing = mutableListOf<String>()
        var checked = 0

        // 关掉 DTD / 外部实体：这个文件来自仓库，但保持"解析器不做额外 IO"的好习惯。
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(manifest)

        for (tag in componentTags) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                // Manifest 用了 android: 前缀；isNamespaceAware=false 时 getAttribute 要带前缀名。
                val raw = el.getAttribute("android:name").ifBlank { el.getAttribute("name") }
                if (raw.isBlank()) continue
                val fqcn = when {
                    raw.startsWith(".") -> appPackage + raw
                    '.' in raw -> raw // 已经是全限定名（比如依赖库合并进来的）
                    else -> "$appPackage.$raw"
                }
                checked++
                if (!classExists(fqcn)) missing += "<$tag android:name=\"$raw\"> → 期望的类 $fqcn"
            }
        }

        assertTrue("Manifest 里一个组件都没解析出来，测试本身可能坏了：$manifest", checked > 0)
        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("有 ${missing.size} 个组件类在编译产物里不存在（共检查 $checked 个）：")
                    missing.forEach { appendLine("  ✗ $it") }
                    appendLine()
                    appendLine("这类不一致会让应用**启动即闪退**：系统按 Manifest 里的字符串去 dex 里找类，")
                    appendLine("找不到就抛 ClassNotFoundException，连 Activity 都创建不出来。")
                    appendLine("最常见的成因是源文件的 `package` 声明与它所在目录不一致 ——")
                    appendLine("Kotlin 允许这样写，编译器**不会**报错。")
                    appendLine()
                    append("可用的类由 tools/verify_manifest_classes.py 从真实 APK 的 dex 里列出来。")
                },
            )
        }
    }

    @Test
    fun `每个源文件声明的 package 与所在目录一致`() {
        val javaRoot = findMainJavaRoot()
        val mismatches = mutableListOf<String>()
        var checked = 0

        javaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val declared = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)""")
                    .find(text)?.groupValues?.get(1)
                if (declared == null) {
                    mismatches += "${file.relativeTo(javaRoot)} 没有 package 声明"
                    return@forEach
                }
                val parent = requireNotNull(file.parentFile) { "$file 没有父目录" }
                val expected = parent.relativeTo(javaRoot).path.replace(File.separatorChar, '.')
                checked++
                if (declared != expected) {
                    mismatches +=
                        "${file.relativeTo(javaRoot)}\n        声明 package = $declared\n" +
                        "        目录推断     = $expected"
                }
            }

        assertTrue("一个 .kt 都没扫到，测试本身可能坏了：$javaRoot", checked > 0)
        assertEquals(
            "有源文件的 package 与目录不一致（Kotlin 不会为此报错，但 Manifest 引用它时会闪退）",
            emptyList<String>(),
            mismatches,
        )
    }

    // -----------------------------------------------------------------------

    /** 类是否存在于**本次编译产物**里：按 classpath 资源查，不触发类加载。 */
    private fun classExists(fqcn: String): Boolean {
        val resource = fqcn.replace('.', '/') + ".class"
        return javaClass.classLoader?.getResource(resource) != null
    }

    private fun findSourceManifest(): File {
        val root = findModuleRoot()
        val f = File(root, "src/main/AndroidManifest.xml")
        if (!f.isFile) fail("找不到 $f")
        return f
    }

    private fun findMainJavaRoot(): File {
        val root = findModuleRoot()
        val f = File(root, "src/main/java")
        if (!f.isDirectory) fail("找不到 $f")
        return f
    }

    /** 从 `user.dir` 往上找，直到看到一个带 `src/main/AndroidManifest.xml` 的模块目录。 */
    private fun findModuleRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return dir
            dir = dir.parentFile
        }
        fail("从 ${System.getProperty("user.dir")} 往上都没找到 src/main/AndroidManifest.xml")
        error("unreachable")
    }
}
