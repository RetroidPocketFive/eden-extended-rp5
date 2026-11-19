// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.Patch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Creates and validates local, per-title shader-cache packages.
 *
 * Only Eden's portable vulkan.bin cache is transferred. The opaque Vulkan driver cache is
 * deliberately excluded because it is tied to the source GPU/driver implementation.
 */
object ShaderCacheTransfer {
    private const val PACKAGE_VERSION = 1
    private const val CACHE_FILE = "vulkan.bin"
    private const val MANIFEST_FILE = "manifest.txt"
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L
    private const val MAX_CACHE_BYTES = 1024L * 1024L * 1024L

    data class Candidate(
        val stagingDirectory: File,
        val cacheFile: File,
        val manifest: Properties,
        val warnings: List<String>
    ) {
        fun cleanup() {
            stagingDirectory.deleteRecursively()
        }
    }

    fun cacheDirectory(userDirectory: String, game: Game): File {
        val titleId = titleIdHex(game)
        return File(userDirectory, "cache/shader/$titleId")
    }

    fun suggestedFilename(game: Game): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC)
            .format(Instant.now())
        val safeTitle = game.title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        return "${safeTitle}_${titleIdHex(game)}_$date.eden-cache"
    }

    fun export(
        resolver: ContentResolver,
        destination: Uri,
        userDirectory: String,
        game: Game,
        patches: List<Patch>
    ) {
        check(!NativeLibrary.isRunning()) { "Close the running game before exporting a shader cache." }
        val cacheFile = File(cacheDirectory(userDirectory, game), CACHE_FILE)
        check(cacheFile.isFile && cacheFile.length() > 0L) { "No shader cache exists for this game yet." }
        check(cacheFile.length() <= MAX_CACHE_BYTES) { "Shader cache is too large to export safely." }

        val properties = Properties().apply {
            setProperty("package_format", PACKAGE_VERSION.toString())
            setProperty("exported_utc", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            setProperty("eden_build", NativeLibrary.getBuildVersion().ifBlank { "unknown" })
            setProperty("game_title", game.title)
            setProperty("title_id", titleIdHex(game))
            setProperty("game_version", game.version.ifBlank { "unknown" })
            setProperty("android_device_model", Build.MODEL.ifBlank { "unknown" })
            setProperty("android_hardware", Build.HARDWARE.ifBlank { "unknown" })
            setProperty("eden_cache_version", NativeLibrary.getShaderCacheVersion().toString())
            setProperty("driver_cache", "excluded_driver_specific")
            setProperty("cache_file", CACHE_FILE)
            setProperty("cache_bytes", cacheFile.length().toString())
            setProperty("cache_sha256", sha256(cacheFile))
            patches.forEachIndexed { index, patch ->
                setProperty(
                    "addon.$index",
                    listOf(
                        patch.type.toString(),
                        patch.enabled.toString(),
                        patch.name,
                        patch.version
                    ).joinToString("|")
                )
            }
        }

        resolver.openOutputStream(destination, "w")?.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_FILE))
                properties.store(zip, "Eden local shader cache package")
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(CACHE_FILE))
                BufferedInputStream(FileInputStream(cacheFile)).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        } ?: error("Could not open the selected destination.")
    }

    fun inspect(
        resolver: ContentResolver,
        source: Uri,
        cacheDirectory: File,
        game: Game,
        currentPatches: List<Patch>
    ): Candidate {
        check(!NativeLibrary.isRunning()) { "Close the running game before importing a shader cache." }
        val staging = File(cacheDirectory, "shader-cache-import-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Could not create a temporary import folder." }

        try {
            val manifestFile = File(staging, MANIFEST_FILE)
            val cacheFile = File(staging, CACHE_FILE)
            var sawManifest = false
            var sawCache = false

            val input = resolver.openInputStream(source)
                ?: error("Could not open the selected shader-cache package.")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory) { "Folders are not allowed inside a shader-cache package." }
                    when (entry.name) {
                        MANIFEST_FILE -> {
                            check(!sawManifest) { "The package contains duplicate manifests." }
                            copyBounded(zip, manifestFile, MAX_MANIFEST_BYTES)
                            sawManifest = true
                        }
                        CACHE_FILE -> {
                            check(!sawCache) { "The package contains duplicate cache files." }
                            copyBounded(zip, cacheFile, MAX_CACHE_BYTES)
                            sawCache = true
                        }
                        else -> error("Unexpected package entry: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }

            check(sawManifest && sawCache) { "The package must contain manifest.txt and vulkan.bin." }
            val properties = Properties().apply {
                manifestFile.inputStream().use { load(it) }
            }
            check(properties.getProperty("package_format") == PACKAGE_VERSION.toString()) {
                "Unsupported shader-cache package version."
            }
            check(properties.getProperty("cache_file") == CACHE_FILE) {
                "The manifest does not identify the expected cache file."
            }
            check(properties.getProperty("title_id") == titleIdHex(game)) {
                "This shader cache belongs to a different game (title ID mismatch)."
            }
            check(properties.getProperty("eden_cache_version") == NativeLibrary.getShaderCacheVersion().toString()) {
                "The shader-cache format is incompatible with this Eden build."
            }

            val declaredSize = properties.getProperty("cache_bytes")?.toLongOrNull()
                ?: error("The manifest has no valid cache size.")
            check(declaredSize == cacheFile.length()) { "The cache size does not match the manifest." }
            val declaredHash = properties.getProperty("cache_sha256")
                ?: error("The manifest has no cache checksum.")
            check(declaredHash.equals(sha256(cacheFile), ignoreCase = true)) {
                "The shader cache failed its SHA-256 integrity check."
            }

            val warnings = mutableListOf<String>()
            val importedBuild = properties.getProperty("eden_build", "unknown")
            if (importedBuild != NativeLibrary.getBuildVersion()) {
                warnings += "Created by Eden build $importedBuild; this device uses ${NativeLibrary.getBuildVersion()}."
            }
            val importedGameVersion = properties.getProperty("game_version", "unknown")
            if (importedGameVersion != game.version && importedGameVersion != "unknown") {
                warnings += "Game version differs: package $importedGameVersion, installed ${game.version}."
            }
            val importedAddons = properties.stringPropertyNames()
                .filter { it.startsWith("addon.") }
                .map { properties.getProperty(it) }
                .sorted()
            val activeAddons = currentPatches.map {
                listOf(it.type.toString(), it.enabled.toString(), it.name, it.version).joinToString("|")
            }.sorted()
            if (importedAddons != activeAddons) {
                warnings += "Detected add-ons differ from the export. Imported pipelines may need rebuilding."
            }

            return Candidate(staging, cacheFile, properties, warnings)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun install(candidate: Candidate, destinationDirectory: File): File? {
        check(!NativeLibrary.isRunning()) { "Close the running game before importing a shader cache." }
        check(destinationDirectory.exists() || destinationDirectory.mkdirs()) {
            "Could not create the game's shader-cache directory."
        }

        val target = File(destinationDirectory, CACHE_FILE)
        val stagedTarget = File(destinationDirectory, "vulkan.bin.importing")
        check(candidate.cacheFile.copyTo(stagedTarget, overwrite = true).length() == candidate.cacheFile.length()) {
            stagedTarget.delete()
            error("Could not stage the imported shader cache.")
        }

        var backup: File? = null
        if (target.exists()) {
            backup = File(destinationDirectory, "vulkan.bin.backup-${System.currentTimeMillis()}")
            check(target.renameTo(backup)) {
                stagedTarget.delete()
                error("Could not back up the existing shader cache; nothing was replaced.")
            }
        }

        if (!stagedTarget.renameTo(target)) {
            stagedTarget.delete()
            if (backup != null) {
                backup.renameTo(target)
            }
            error("Could not install the imported shader cache. The previous cache was restored where possible.")
        }
        return backup
    }

    private fun titleIdHex(game: Game): String {
        val titleId = game.programId.toLongOrNull()
            ?: error("This game does not have a valid title ID.")
        require(titleId > 0L) { "This game does not have a valid title ID." }
        return "%016x".format(java.util.Locale.ROOT, titleId)
    }

    private fun copyBounded(input: java.io.InputStream, destination: File, limit: Long) {
        var total = 0L
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= limit) { "Shader-cache package entry exceeds the safety limit." }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
