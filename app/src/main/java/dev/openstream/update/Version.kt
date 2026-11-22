package dev.openstream.update

/** "v1.2.3-beta" to [1, 2, 3]; null when it is not a dotted number. */
private fun parseVersion(text: String): List<Int>? {
    val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    return core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
}

/** Whether a release tag names a later version than the installed one. False when either is not a version. */
fun isNewer(candidate: String, installed: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(installed) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
