package dev.lhrb.app.glowkin.features.connection.domain

class InvalidEndpoint : IllegalArgumentException("Enter a hostname or IP address without a URL, path, or port; use port 1–65535.")

data class Endpoint private constructor(val host: String, val port: Int) {
    companion object {
        fun parse(host: String, port: Int): Endpoint {
            if (port !in 1..65535 || host.isEmpty() || host.any { it.isWhitespace() }) throw InvalidEndpoint()
            val address = if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host
            val valid = when {
                ':' in address -> validIpv6(address)
                '[' in host || ']' in host -> false
                address.all { it.isDigit() || it == '.' } -> validIpv4(address)
                else -> address.length <= 253 && address.split('.').all {
                    it.length in 1..63 && it.first().isAsciiAlphanumeric() && it.last().isAsciiAlphanumeric() &&
                        it.all { c -> c.isAsciiAlphanumeric() || c == '-' }
                }
            }
            if (!valid) throw InvalidEndpoint()
            return Endpoint(address, port)
        }

        private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

        private fun validIpv4(value: String): Boolean = value.split('.').let { parts ->
            parts.size == 4 && parts.all {
                it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } &&
                    (it.length == 1 || it[0] != '0') && (it.toIntOrNull() ?: -1) in 0..255
            }
        }

        private fun validIpv6(value: String): Boolean {
            if (value.count { it == ':' } < 2 || value.contains(":::")) return false
            val halves = value.split("::")
            if (halves.size > 2) return false
            val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
            var count = 0
            for ((index, group) in groups.withIndex()) {
                if ('.' in group) {
                    if (index != groups.lastIndex || !value.endsWith(group) || !validIpv4(group)) return false
                    count += 2
                } else {
                    if (group.length !in 1..4 || !group.all { it in "0123456789abcdefABCDEF" }) return false
                    count++
                }
            }
            return if (halves.size == 2) count < 8 else count == 8
        }
    }
}
