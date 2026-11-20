package org.unyri.admin.core

import java.nio.file.*
import java.nio.file.attribute.*
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Explicit, on-demand DDNS only. No background job, OS task or remote token storage. */
class DuckDns(private val file: Path) {
    fun save(token: String) {
        require(token.matches(Regex("[a-zA-Z0-9-]{16,128}")))
        Files.createDirectories(file.parent)
        check(!Files.isSymbolicLink(file) && !Files.isSymbolicLink(file.parent))
        if (!Files.exists(file)) Files.createFile(file)
        val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"))
        else {
            val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java) ?: error("Protected storage unavailable")
            view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                .setPermissions(*AclEntryPermission.entries.toTypedArray()).build())
        }
        Files.writeString(file, token, StandardOpenOption.TRUNCATE_EXISTING)
    }
    fun update(domain: String, publicIp: String, consent: Boolean): Boolean {
        require(consent && HostValidation.domain(domain) && domain.endsWith(".duckdns.org") && HostValidation.publicIpv4(publicIp))
        val name = domain.removeSuffix(".duckdns.org")
        require(!name.contains('.'))
        check(!Files.isSymbolicLink(file) && Files.size(file) <= 128)
        val token = Files.readString(file).trim()
        require(token.matches(Regex("[a-zA-Z0-9-]{16,128}")))
        return try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
            val response = client.send(HttpRequest.newBuilder(URI("https://www.duckdns.org/update?domains=$name&token=$token&ip=$publicIp"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { response.statusCode() == 200 && String(it.readNBytes(64)).trim() == "OK" }
        } catch (_: Exception) { false }
    }
}
