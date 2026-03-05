package io.github.hahadu.apidoc

import com.google.gson.JsonParser
import com.typesafe.config.Config
import java.nio.file.Path

internal data class ApidocConfig(
    val collectionPath: String?,
    val outputPath: String?,
    val baseUrl: String?,
    val controllerPackages: List<String>?,
    val collectionName: String?,
    val collectionDescription: String?,
    val postmanBaseUrlHost: String?,
    val postmanProtocol: String?,
    val postmanAuthToken: String?,
    val postmanHeadersJson: String?,
    val defaultGroup: String?,
    val headers: Map<String, String>?,
    val postmanApiKey: String?,
)

internal fun resolvePath(path: String, baseDir: Path?): Path {
    if (baseDir != null) {
        val fromRoot = baseDir.resolve(path).normalize()
        if (fromRoot.toFile().exists()) return fromRoot
    }
    val direct = Path.of(path)
    if (direct.toFile().exists()) return direct
    val fallback = Path.of("..").resolve(path).normalize()
    if (fallback.toFile().exists()) return fallback
    return direct
}

internal fun parseHeaders(raw: String?): Map<String, String>? {
    if (raw.isNullOrBlank()) return null
    return try {
        val json = JsonParser.parseString(raw).asJsonObject
        json.entrySet().associate { it.key to it.value.asString }
    } catch (_: Exception) {
        null
    }
}

internal fun Config.getStringOrNull(path: String): String? =
    if (hasPath(path)) getString(path) else null
