package com.clawgui.android.core.nano.security

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

private class IpNetwork(val baseBytes: ByteArray, val prefixLen: Int) {
    fun contains(addr: InetAddress): Boolean {
        val ab = addr.address
        if (ab.size != baseBytes.size) return false
        val wholeBytes = prefixLen / 8
        val partBits = prefixLen % 8
        for (i in 0 until wholeBytes) {
            if (ab[i] != baseBytes[i]) return false
        }
        if (partBits > 0 && wholeBytes < baseBytes.size) {
            val mask = (0xFF shl (8 - partBits)) and 0xFF
            val addrNibble = ab[wholeBytes].toInt() and 0xFF
            val baseNibble = baseBytes[wholeBytes].toInt() and 0xFF
            if ((addrNibble and mask) != (baseNibble and mask)) return false
        }
        return true
    }
}

private fun parseNetwork(host: String, prefixLen: Int): IpNetwork =
    IpNetwork(InetAddress.getByName(host).address, prefixLen)

private val BLOCKED_NETWORKS: List<IpNetwork> = listOf(
    parseNetwork("0.0.0.0", 8),
    parseNetwork("10.0.0.0", 8),
    parseNetwork("100.64.0.0", 10),   // carrier-grade NAT
    parseNetwork("127.0.0.0", 8),
    parseNetwork("169.254.0.0", 16),  // link-local / cloud metadata
    parseNetwork("172.16.0.0", 12),
    parseNetwork("192.168.0.0", 16),
    parseNetwork("::1", 128),
    parseNetwork("fc00::", 7),         // IPv6 unique local
    parseNetwork("fe80::", 10),        // IPv6 link-local
)

private val URL_REGEX = Regex("""https?://[^\s"'`;|<>]+""", RegexOption.IGNORE_CASE)

private fun isPrivate(addr: InetAddress): Boolean = BLOCKED_NETWORKS.any { it.contains(addr) }

fun validateUrlTarget(url: String): Pair<Boolean, String> {
    val parsed = try {
        URI(url)
    } catch (e: Exception) {
        return Pair(false, e.message ?: "Invalid URL")
    }

    if (parsed.scheme !in listOf("http", "https")) {
        return Pair(false, "Only http/https allowed, got '${parsed.scheme ?: "none"}'")
    }

    val hostname = parsed.host
        ?: return Pair(false, "Missing hostname")

    val addrs = try {
        InetAddress.getAllByName(hostname)
    } catch (e: UnknownHostException) {
        return Pair(false, "Cannot resolve hostname: $hostname")
    }

    for (addr in addrs) {
        if (isPrivate(addr)) {
            return Pair(false, "Blocked: $hostname resolves to private/internal address ${addr.hostAddress}")
        }
    }

    return Pair(true, "")
}

fun validateResolvedUrl(url: String): Pair<Boolean, String> {
    val parsed = try {
        URI(url)
    } catch (_: Exception) {
        return Pair(true, "")
    }

    val hostname = parsed.host ?: return Pair(true, "")

    // If hostname is a literal IP address, check it directly
    val literalAddr = try {
        InetAddress.getByName(hostname).also {
            // getByName returns same address for IP literals without DNS lookup
            if (isPrivate(it)) {
                return Pair(false, "Redirect target is a private address: ${it.hostAddress}")
            }
        }
        return Pair(true, "")
    } catch (_: Exception) {
        null
    }

    // Hostname is a domain — resolve and check
    val addrs = try {
        InetAddress.getAllByName(hostname)
    } catch (_: UnknownHostException) {
        return Pair(true, "")
    }

    for (addr in addrs) {
        if (isPrivate(addr)) {
            return Pair(false, "Redirect target $hostname resolves to private address ${addr.hostAddress}")
        }
    }

    return Pair(true, "")
}

fun containsInternalUrl(command: String): Boolean {
    for (match in URL_REGEX.findAll(command)) {
        val (ok, _) = validateUrlTarget(match.value)
        if (!ok) return true
    }
    return false
}
