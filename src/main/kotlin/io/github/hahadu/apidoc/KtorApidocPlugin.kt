package io.github.hahadu.apidoc

import io.ktor.server.application.*
import com.google.gson.Gson
import java.nio.file.Path

class KtorApidocConfig {
    var collectionPath: String = "docs/postman/auction-api.postman_collection.json"
    var outputPath: String = "docs/frontend-api.md"
    var postmanApiKey: String? = System.getenv("POSTMAN_API_KEY")
    var postmanWorkspaceId: String? = System.getenv("POSTMAN_WORKSPACE_ID")
    var generateOnStartup: Boolean = false
}

val KtorApidocPlugin = createApplicationPlugin(name = "KtorApidocPlugin", ::KtorApidocConfig) {
    val config = pluginConfig
    if (config.generateOnStartup) {
        val collectionJson = Path.of(config.collectionPath).toFile().readText(Charsets.UTF_8)
        ApiDocGenerator(Gson()).generateMarkdownFromPostman(collectionJson, Path.of(config.outputPath))
        val apiKey = config.postmanApiKey
        if (!apiKey.isNullOrBlank()) {
            PostmanUploader(apiKey, Gson()).upsertCollection(collectionJson, config.postmanWorkspaceId)
        }
    }
}
