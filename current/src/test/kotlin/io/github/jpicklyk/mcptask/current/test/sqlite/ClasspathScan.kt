package io.github.jpicklyk.mcptask.current.test.sqlite

import java.io.File
import java.net.URLDecoder
import java.util.jar.JarFile

/** Finds classes by package prefix on the classpath root (a directory or a jar) that holds an anchor class. */
object ClasspathScan {
    /**
     * Every class under [pathPrefix] (slash form, trailing slash, for example `io/github/foo/bar/`) in the
     * classpath root that contains [anchor]. Classes are loaded without initialization and unloadable ones skipped.
     */
    fun classesUnder(
        anchor: Class<*>,
        pathPrefix: String
    ): List<Class<*>> {
        val location = anchor.protectionDomain.codeSource.location
        val decoded = URLDecoder.decode(location.path, "UTF-8")
        val root = File(decoded.removePrefix("/").let { if (File(it).exists()) it else "/$it" })
        val names = mutableListOf<String>()
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                val rel = it.relativeTo(root).path.replace(File.separatorChar, '/')
                if (rel.startsWith(pathPrefix)) names += rel.removeSuffix(".class").replace('/', '.')
            }
        } else {
            JarFile(root).use { jar ->
                jar
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith(pathPrefix) && it.name.endsWith(".class") }
                    .forEach { names += it.name.removeSuffix(".class").replace('/', '.') }
            }
        }
        val loader = anchor.classLoader
        return names.mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
    }
}
