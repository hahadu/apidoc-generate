package io.github.hahadu.apidoc

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

class ApiDocGenerator(
    private val gson: Gson = Gson(),
) {
    fun generateMarkdownFromPostman(collectionJson: String, outputPath: Path) {
        val root = gson.fromJson(collectionJson, JsonObject::class.java)
        val info = root.getAsJsonObject("info")
        val name = info.get("name")?.asString ?: "API"

        val sb = StringBuilder()
        sb.append("# ").append(name).append(" 前端对接文档\n\n")
        sb.append("Base URL: `{{host}}`\n\n")
        sb.append("Header 示例：`Authorization: Bearer <token>`\n\n")

        val items = root.getAsJsonArray("item") ?: JsonArray()
        for (group in items) {
            val groupObj = group.asJsonObject
            val groupName = groupObj.get("name")?.asString ?: "未命名分组"
            sb.append("## ").append(groupName).append("\n\n")
            val groupItems = groupObj.getAsJsonArray("item") ?: JsonArray()
            for (req in groupItems) {
                val reqObj = req.asJsonObject
                val title = reqObj.get("name")?.asString ?: "未命名接口"
                val request = reqObj.getAsJsonObject("request")
                val method = request.get("method")?.asString ?: "GET"
                val url = urlString(request)
                sb.append("### ").append(title).append("\n")
                sb.append("- ").append(method).append(" `").append(url).append("`\n")

                val headers = request.getAsJsonArray("header")
                if (headers != null && headers.size() > 0) {
                    sb.append("- Header:\n")
                    for (h in headers) {
                        val ho = h.asJsonObject
                        sb.append("  - ").append(ho.get("key").asString)
                            .append(": ").append(ho.get("value").asString).append("\n")
                    }
                }

                val body = request.getAsJsonObject("body")
                if (body != null && body.has("raw")) {
                    val raw = body.get("raw").asString
                    if (raw.isNotBlank()) {
                        sb.append("- 请求示例\n")
                        sb.append("```json\n").append(raw).append("\n```\n")
                    }
                }
                sb.append("\n")
            }
        }

        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, sb.toString())
    }
}

private fun urlString(request: JsonObject): String {
    val urlElem = request.get("url") ?: return ""
    if (urlElem.isJsonPrimitive) return urlElem.asString
    if (!urlElem.isJsonObject) return ""
    val obj = urlElem.asJsonObject
    val raw = obj.get("raw")?.asString
    if (!raw.isNullOrBlank()) return raw
    val hostElem = obj.get("host")
    val host = when {
        hostElem == null -> ""
        hostElem.isJsonArray -> hostElem.asJsonArray.joinToString(".") { it.asString }
        hostElem.isJsonPrimitive -> hostElem.asString
        else -> ""
    }
    val pathElem = obj.get("path")
    val path = when {
        pathElem == null -> ""
        pathElem.isJsonArray -> pathElem.asJsonArray.joinToString("/") { it.asString }
        pathElem.isJsonPrimitive -> pathElem.asString
        else -> ""
    }
    val protocol = obj.get("protocol")?.asString ?: ""
    val prefix = if (protocol.isNotBlank()) "$protocol://" else ""
    val slash = if (path.isNotBlank()) "/$path" else ""
    return "$prefix$host$slash"
}
