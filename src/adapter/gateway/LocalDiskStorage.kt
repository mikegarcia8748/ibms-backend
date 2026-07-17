package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.port.StoragePort
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stores proof files on the local filesystem under [baseDir]; the object's
 * storage_key is its path relative to that root. Swappable for a GCS/S3 adapter
 * later without touching the schema or callers.
 */
class LocalDiskStorage(baseDir: String) : StoragePort {
    private val root: Path = Paths.get(baseDir).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    override fun put(key: String, bytes: ByteArray) {
        val target = resolve(key)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, bytes)
    }

    override fun read(key: String): ByteArray? {
        val target = resolve(key)
        return if (Files.exists(target)) Files.readAllBytes(target) else null
    }

    private fun resolve(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root)) { "invalid storage key: $key" }
        return resolved
    }
}
