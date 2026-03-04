package com.wokfoy.apidoc

import com.google.gson.Gson
import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.Path

fun main() {
    val projectRoot = System.getProperty("user.dir")
    println("ProjectRoot = $projectRoot")
    val config = loadApidocConfig(projectRoot)
    println(config)
    val collectionPath = System.getenv("APIDOC_COLLECTION_PATH") ?: config.collectionPath ?: "docs/postman/auction-api.postman_collection.json"
    val outputPath = System.getenv("APIDOC_OUTPUT_PATH") ?: config.outputPath ?: "docs/frontend-api.md"
    val baseUrl = System.getenv("APIDOC_BASE_URL") ?: config.baseUrl ?: ""
    val controllerPackages = System.getenv("APIDOC_CONTROLLER_PACKAGES") ?.split(",") ?.map { it.trim() } ?.filter { it.isNotBlank() } ?: config.controllerPackages ?: emptyList()
    val collectionName = System.getenv("APIDOC_COLLECTION_NAME") ?: config.collectionName ?: "Auction API"
    val collectionDesc = System.getenv("APIDOC_COLLECTION_DESCRIPTION") ?: config.collectionDescription ?: ""
    val baseUrlHost = System.getenv("APIDOC_POSTMAN_BASE_URL_HOST") ?: config.postmanBaseUrlHost ?: baseUrl
    val protocol = System.getenv("APIDOC_POSTMAN_PROTOCOL") ?: config.postmanProtocol ?: "http"
    val defaultGroup = System.getenv("APIDOC_DEFAULT_GROUP") ?: config.defaultGroup ?: "general"
    val authToken = System.getenv("APIDOC_POSTMAN_AUTH_TOKEN") ?: config.postmanAuthToken
    val headersJson = System.getenv("APIDOC_POSTMAN_HEADERS_JSON") ?: config.postmanHeadersJson

    val resolvedOutputPath = resolvePath(outputPath, baseDir = Path.of(projectRoot))
    val resolvedCollectionPath = resolvePath(collectionPath, baseDir = Path.of(projectRoot))

    val headers = parseHeaders(headersJson) ?: config.headers ?: mapOf( "Content-Type" to "application/json", "Accept" to "application/json", "Authorization" to (authToken?.let { "Bearer $it" } ?: "")).filterValues { it.isNotBlank() }

    val generator = PostmanCollectionGenerator(
        PostmanConfig(
            baseUrlHost = baseUrlHost,
            protocol = protocol,
            collectionName = collectionName,
            description = collectionDesc,
            defaultGroup = defaultGroup,
            headers = headers,
            authToken = authToken,
        )
    )
    val collectionJson = generator.generate(controllerPackages)
    resolvedCollectionPath.toFile().parentFile?.mkdirs()
    resolvedCollectionPath.toFile().writeText(collectionJson, Charsets.UTF_8)

    val markdownGenerator = ApiDocGenerator()
    markdownGenerator.generateMarkdownFromPostman(collectionJson, resolvedOutputPath)

    val apiKey = System.getenv("POSTMAN_API_KEY") ?: config.postmanApiKey
    if (!apiKey.isNullOrBlank()) {
        val workspaceId = System.getenv("POSTMAN_WORKSPACE_ID")
        val uploader = PostmanUploader(apiKey, Gson())
        val collectionId = uploader.upsertCollection(collectionJson, workspaceId)
        println("Postman collection synced: $collectionId")
    } else {
        println("POSTMAN_API_KEY not set, skip Postman upload")
    }
}

private fun resolvePath(path: String, baseDir: Path?): Path {
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

private fun parseHeaders(raw: String?): Map<String, String>? {
    if (raw.isNullOrBlank()) return null
    return try {
        val json = com.google.gson.JsonParser.parseString(raw).asJsonObject
        json.entrySet().associate { it.key to it.value.asString }
    } catch (_: Exception) {
        null
    }
}

private data class ApidocConfig(
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

private fun loadApidocConfig(projectRoot: String): ApidocConfig {
    val confFile = File(projectRoot, "src/main/resources/application.conf")
    if (!confFile.exists()) {
        return ApidocConfig(null, null, null, null, null, null, null, null, null, null, null, null,null)
    }
    val cfg = ConfigFactory.parseFile(confFile).resolve()
    if (!cfg.hasPath("apidoc")) {
        return ApidocConfig(null, null, null, null, null, null, null, null, null, null, null, null,null)
    }
    val apidoc = cfg.getConfig("apidoc")
    val postman = if (apidoc.hasPath("postman")) apidoc.getConfig("postman") else null
    val headers = if (apidoc.hasPath("headers")) {
        apidoc.getConfig("headers").entrySet().associate { it.key to apidoc.getString("headers.${it.key}") }
    } else null
    println(apidoc.getStringOrNull("postmanApiKey"))
    return ApidocConfig(
        collectionPath = apidoc.getStringOrNull("collectionPath"),
        outputPath = apidoc.getStringOrNull("outputPath"),
        baseUrl = apidoc.getStringOrNull("baseUrl"),
        controllerPackages = apidoc.getStringOrNull("controllerPackages")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() },
        collectionName = postman?.getStringOrNull("name"),
        collectionDescription = postman?.getStringOrNull("description"),
        postmanBaseUrlHost = postman?.getStringOrNull("baseUrlHost"),
        postmanProtocol = postman?.getStringOrNull("protocol"),
        postmanAuthToken = postman?.getStringOrNull("authToken"),
        postmanHeadersJson = postman?.getStringOrNull("headersJson"),
        defaultGroup = apidoc.getStringOrNull("defaultGroup"),
        headers = headers,
        postmanApiKey = apidoc.getStringOrNull("postmanApiKey"),
    )
}

private fun com.typesafe.config.Config.getStringOrNull(path: String): String? =
    if (hasPath(path)) getString(path) else null
