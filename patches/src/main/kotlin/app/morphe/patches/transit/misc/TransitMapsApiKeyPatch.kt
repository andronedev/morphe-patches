package app.morphe.patches.transit.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringOption
import java.io.File
import java.nio.charset.StandardCharsets.UTF_16LE

@Suppress("unused")
val transitMapsApiKeyPatch = rawResourcePatch(
    name = "Custom Maps API Key",
    description = "Replace Transit Google Maps key with your own Android Maps SDK key for re-signed APKs.",
) {
    compatibleWith("com.thetransitapp.droid")

    val mapsApiKeyOption = stringOption(
        key = "mapsApiKey",
        default = "",
        title = "Google Maps API key",
        description = "Android Maps SDK key",
        required = false,
    )

    execute {
        val apiKey = mapsApiKeyOption.value?.trim().orEmpty()
        if (apiKey.isBlank()) {
            throw PatchException("Option 'mapsApiKey' is required.")
        }

        val apkFile = readApkFileFromConfig()
        patchManifestInsideApk(apkFile, apiKey)
    }
}

private fun patchManifestInsideApk(apkFile: File, apiKey: String) {
    val manifestBytes = readManifestFromApkWithApkzlib(apkFile)

    val keyNameExists = manifestBytes.indexOfSubArray("com.google.android.maps.v2.API_KEY".toByteArray(UTF_16LE)) >= 0 ||
        manifestBytes.indexOfSubArray("com.google.android.maps.v2.API_KEY".toByteArray()) >= 0
    if (!keyNameExists) {
        throw PatchException("Could not find com.google.android.maps.v2.API_KEY in AndroidManifest.xml.")
    }

    val utf16KeyLocation = manifestBytes.findUtf16ApiKey()
    val utf8KeyLocation = manifestBytes.findUtf8ApiKey()

    val keyLocation = utf16KeyLocation ?: utf8KeyLocation
    if (keyLocation == null) {
        throw PatchException("Could not find current Google Maps key value in AndroidManifest.xml.")
    }

    val currentKeyLength = keyLocation.key.length
    if (apiKey.length != currentKeyLength) {
        throw PatchException(
            "mapsApiKey length (${apiKey.length}) must match existing key length ($currentKeyLength) " +
                "for raw manifest patching."
        )
    }

    if (keyLocation.encoding == Encoding.UTF16LE) {
        apiKey.toByteArray(UTF_16LE).copyInto(manifestBytes, destinationOffset = keyLocation.start)
    } else {
        apiKey.toByteArray().copyInto(manifestBytes, destinationOffset = keyLocation.start)
    }

    writeManifestToApkWithApkzlib(apkFile, manifestBytes)
}

private data class ApiKeyLocation(
    val start: Int,
    val key: String,
    val encoding: Encoding,
)

private enum class Encoding {
    UTF16LE,
    UTF8,
}

private fun ByteArray.indexOfSubArray(needle: ByteArray, fromIndex: Int = 0): Int {
    if (needle.isEmpty()) return fromIndex.coerceAtMost(size)
    if (fromIndex < 0 || fromIndex >= size) return -1

    val limit = size - needle.size
    for (i in fromIndex..limit) {
        var found = true
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) {
                found = false
                break
            }
        }
        if (found) return i
    }
    return -1
}

private fun ByteArray.findUtf16ApiKey(): ApiKeyLocation? {
    val prefix = "AIza".toByteArray(UTF_16LE)
    var index = indexOfSubArray(prefix)

    while (index >= 0) {
        var end = index
        while (end + 1 < size && !(this[end] == 0.toByte() && this[end + 1] == 0.toByte())) {
            end += 2
        }

        if (end > index) {
            val key = String(copyOfRange(index, end), UTF_16LE)
            if (key.matches(Regex("AIza[A-Za-z0-9_-]{20,}"))) {
                return ApiKeyLocation(index, key, Encoding.UTF16LE)
            }
        }

        index = indexOfSubArray(prefix, index + 2)
    }

    return null
}

private fun ByteArray.findUtf8ApiKey(): ApiKeyLocation? {
    val prefix = "AIza".toByteArray()
    var index = indexOfSubArray(prefix)

    while (index >= 0) {
        var end = index
        while (end < size && this[end] != 0.toByte()) {
            end++
        }

        if (end > index) {
            val key = String(copyOfRange(index, end))
            if (key.matches(Regex("AIza[A-Za-z0-9_-]{20,}"))) {
                return ApiKeyLocation(index, key, Encoding.UTF8)
            }
        }

        index = indexOfSubArray(prefix, index + 1)
    }

    return null
}

private fun ResourcePatchContext.readApkFileFromConfig(): File {
    val configField = ResourcePatchContext::class.java.getDeclaredField("config")
    configField.isAccessible = true
    val config = configField.get(this)

    val apkFileField = config.javaClass.getDeclaredField("apkFile")
    apkFileField.isAccessible = true
    return apkFileField.get(config) as File
}

private fun readManifestFromApkWithApkzlib(apkFile: File): ByteArray {
    return withApkzlibZip(apkFile, "openReadOnly") { zip ->
        val entry = zip.javaClass.getMethod("get", String::class.java).invoke(zip, "AndroidManifest.xml")
            ?: throw PatchException("Could not find AndroidManifest.xml in APK.")
        entry.javaClass.getMethod("read").invoke(entry) as ByteArray
    }
}

private fun writeManifestToApkWithApkzlib(apkFile: File, manifestBytes: ByteArray) {
    withApkzlibZip(apkFile, "openReadWrite") { zip ->
        val getMethod = zip.javaClass.getMethod("get", String::class.java)
        val existing = getMethod.invoke(zip, "AndroidManifest.xml")
            ?: throw PatchException("Could not find AndroidManifest.xml in APK.")
        existing.javaClass.getMethod("delete").invoke(existing)

        zip.javaClass.getMethod("add", String::class.java, java.io.InputStream::class.java)
            .invoke(zip, "AndroidManifest.xml", manifestBytes.inputStream())
        zip.javaClass.getMethod("realign").invoke(zip)
    }
}

private fun <T> withApkzlibZip(apkFile: File, factoryMethod: String, block: (Any) -> T): T {
    try {
        val zFileClass = Class.forName("com.android.tools.build.apkzlib.zip.ZFile")
        val open = zFileClass.getMethod(factoryMethod, File::class.java)
        val zip = open.invoke(null, apkFile) as java.io.Closeable
        zip.use {
            return block(zip)
        }
    } catch (exception: Exception) {
        throw PatchException("Failed to patch AndroidManifest.xml using apkzlib: ${exception.message}", exception)
    }
}
