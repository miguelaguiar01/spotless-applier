package com.github.miguelaguiar01.spotless.worker

/**
 * Translates the Windows view of a WSL distribution's files (`\\wsl.localhost\<distro>\...`,
 * `\\wsl$\<distro>\...`, or the IDE's forward-slash spelling `//wsl.localhost/<distro>/...`) into
 * the Linux paths a worker running inside that distribution sees. Pure string logic, no platform
 * dependencies, so it is unit-testable anywhere.
 */
class WslPathMapper(val distributionId: String) {
    private val root = Regex(
        """^(?:\\\\|//)(?:wsl\.localhost|wsl\$)[\\/]${Regex.escape(distributionId)}(?=[\\/]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val embedded = Regex(
        """(?:\\\\|//)(?:wsl\.localhost|wsl\$)[\\/]${Regex.escape(distributionId)}((?:[\\/][^\\/\s"'<>|*?]+)*)[\\/]?""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [path] is inside this distribution. */
    fun isWslPath(path: String): Boolean = root.containsMatchIn(path)

    /** Whole-string conversion; strings that are not inside this distribution are returned unchanged. */
    fun toLinuxPath(path: String): String {
        val m = root.find(path) ?: return path
        val rest = path.substring(m.range.last + 1).replace('\\', '/')
        return if (rest.isEmpty()) "/" else rest
    }

    /** Converts every embedded occurrence (e.g. inside XML); paths with spaces are not supported here. */
    fun toLinuxInText(text: String): String = embedded.replace(text) { m ->
        m.groupValues[1].replace('\\', '/').ifEmpty { "/" }
    }

    companion object {
        private val ANY_ROOT = Regex("""^(?:\\\\|//)(?:wsl\.localhost|wsl\$)[\\/]([^\\/]+)""", RegexOption.IGNORE_CASE)

        /** The distribution a WSL UNC path belongs to, or null for any other path. */
        fun distributionIdOf(path: String): String? = ANY_ROOT.find(path)?.groupValues?.get(1)
    }
}
