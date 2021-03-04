@file:Suppress("unused") // usages in build scripts are not tracked properly

import net.rubygrapefruit.platform.Native
import net.rubygrapefruit.platform.WindowsRegistry
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.nio.file.Paths
import java.io.File
import net.rubygrapefruit.platform.WindowsRegistry.Key.HKEY_LOCAL_MACHINE
import org.gradle.internal.os.OperatingSystem

enum class JdkMajorVersion(private val mandatory: Boolean = true) {
    JDK_16, JDK_17, JDK_18, JDK_9, JDK_10(false), JDK_11(false);

    fun isMandatory(): Boolean = mandatory
}

val jdkAlternativeVarNames = mapOf(JdkMajorVersion.JDK_9 to listOf("JDK_19"))

data class JdkId(val explicit: Boolean, val majorVersion: JdkMajorVersion, var version: String, var homeDir: File)

fun Project.getConfiguredJdks(): List<JdkId> {
    val res = arrayListOf<JdkId>()
    val explicitJdk = Paths.get(project.properties["JAVA_HOME"].toString()).toRealPath().toFile()
    val explicitJdk9 = Paths.get(project.properties["JDK_9"].toString()).toRealPath().toFile()
    res.add(JdkId(true, JdkMajorVersion.JDK_18, "X", explicitJdk))
    res.add(JdkId(true, JdkMajorVersion.JDK_9, "X", explicitJdk9))
    return res
}

// see JEP 223
private val javaMajorVersionRegex = Regex("""(?:1\.)?(\d+).*""")
private val javaVersionRegex = Regex("""(?:1\.)?(\d+)(\.\d+)?([+-_]\w+){0,3}""")

fun MutableCollection<JdkId>.addIfBetter(project: Project, version: String, id: String, homeDir: File): Boolean {
    val matchString = javaMajorVersionRegex.matchEntire(version)?.groupValues?.get(1)
    val majorJdkVersion = when (matchString) {
        "6" -> JdkMajorVersion.JDK_16
        "7" -> JdkMajorVersion.JDK_17
        "8" -> JdkMajorVersion.JDK_18
        "9" -> JdkMajorVersion.JDK_9
        else -> {
            project.logger.info("Cannot recognize version string '$version' (found version '$matchString')")
            return false
        }
    }
    val prev = find { it.majorVersion == majorJdkVersion }
    if (prev == null) {
        add(JdkId(false, majorJdkVersion, version, homeDir))
        return true
    }
    if (prev.explicit) return false
    val versionsComparisonRes = compareVersions(prev.version, version)
    if (versionsComparisonRes < 0 || (versionsComparisonRes == 0 && id.contains("64"))) { // prefer 64-bit
        prev.version = version
        prev.homeDir = homeDir
        return true
    }
    return false
}

private fun compareVersions(left: String, right: String): Int {
    if (left == right) return 0
    fun MatchResult.extractNumVer(): List<Int> =
            groups.drop(2).map {
                it?.value?.filter { it in '0'..'9' }?.toIntOrNull() ?: 0
            }
    val lmi = (javaVersionRegex.matchEntire(left)?.extractNumVer() ?: emptyList()).iterator()
    val rmi = (javaVersionRegex.matchEntire(right)?.extractNumVer() ?: emptyList()).iterator()
    while (lmi.hasNext() && rmi.hasNext()) {
        val l = lmi.next()
        val r = rmi.next()
        when {
            l < r -> return -1
            l > r -> return 1
        }
    }
    return when {
        rmi.hasNext() -> -1
        lmi.hasNext() -> 1
        else -> 0
    }
}

fun MutableCollection<JdkId>.discoverJdks(project: Project) {
    val os = OperatingSystem.current()
    when {
        os.isWindows -> discoverJdksOnWindows(project)
        os.isMacOsX -> discoverJdksOnMacOS(project)
        else -> discoverJdksOnUnix(project)
    }
}

private val macOsJavaHomeOutRegexes = listOf(Regex("""\s+(\S+),\s+(\S+):\s+".*?"\s+(.+)"""),
                                             Regex("""\s+(\S+)\s+\((.*?)\):\s+(.+)"""))

fun MutableCollection<JdkId>.discoverJdksOnMacOS(project: Project) {
    addIfBetter(project, "18","JDK_18",File(project.properties["JAVA_HOME"] as String))
    addIfBetter(project, "19","JDK_19",File(project.properties["JDK_9"] as String))

}

private val unixConventionalJdkLocations = listOf(
        "/usr/lib/jvm",       // *deb, Arch
        "/opt",               // *rpm, Gentoo, HP/UX
        "/usr/lib",           // Slackware 32
        "/usr/lib64",         // Slackware 64
        "/usr/local",         // OpenBSD, FreeBSD
        "/usr/pkg/java",      // NetBSD
        "/usr/jdk/instances") // Solaris

private val unixConventionalJdkDirRex = Regex("jdk|jre|java|zulu")

fun MutableCollection<JdkId>.discoverJdksOnUnix(project: Project) {
    addIfBetter(project, "18","JDK_18",File(project.properties["JAVA_HOME"] as String))
    addIfBetter(project, "19","JDK_19",File(project.properties["JDK_9"] as String))
}

private val windowsConventionalJdkRegistryPaths = listOf(
        "SOFTWARE\\JavaSoft\\Java Development Kit",
        "SOFTWARE\\Wow6432Node\\JavaSoft\\Java Development Kit",
        "SOFTWARE\\JavaSoft\\JDK",
        "SOFTWARE\\Wow6432Node\\JavaSoft\\JDK")

fun MutableCollection<JdkId>.discoverJdksOnWindows(project: Project) {
    val registry = Native.get(WindowsRegistry::class.java)
    for (regPath in windowsConventionalJdkRegistryPaths) {
        val jdkKeys = try {
            registry.getSubkeys(HKEY_LOCAL_MACHINE, regPath)
        } catch (e: RuntimeException) {
            // ignore missing nodes
            continue
        }
        for (jdkKey in jdkKeys) {
            try {
                val javaHome = registry.getStringValue(HKEY_LOCAL_MACHINE, regPath + "\\" + jdkKey, "JavaHome")
                val versionMatch = javaVersionRegex.find(jdkKey)
                if (versionMatch == null) {
                    project.logger.info("Unable to extract version from possible JDK location: $javaHome ($jdkKey)")
                }
                else {
                    javaHome.takeIf { it.isNotEmpty() }
                            ?.let { File(it) }
                            ?.takeIf { it.isDirectory && fileFrom(it, "bin", "java.exe").isFile }
                            ?.let {
                                addIfBetter(project, versionMatch.value, jdkKey, it)
                            }
                }
            }
            catch (e: RuntimeException) {
                // Ignore
            }
        }
    }
}
