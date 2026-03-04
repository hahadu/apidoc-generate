package com.wokfoy.apidoc

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class PostmanUploader(
    private val apiKey: String,
    private val gson: Gson = Gson(),
) {
    private val client = HttpClient.newHttpClient()

    fun upsertCollection(collectionJson: String, workspaceId: String? = null): String {
        val info = gson.fromJson(collectionJson, JsonObject::class.java)
            .getAsJsonObject("info")
        val name = info.get("name")?.asString ?: "API"

        val existingId = findCollectionIdByName(name)
        return if (existingId != null) {
            updateCollection(existingId, collectionJson)
            existingId
        } else {
            createCollection(collectionJson, workspaceId)
        }
    }

    private fun findCollectionIdByName(name: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.getpostman.com/collections"))
            .header("X-Api-Key", apiKey)
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return null
        }
        val json = gson.fromJson(response.body(), JsonObject::class.java)
        val collections = json.getAsJsonArray("collections") ?: return null
        for (c in collections) {
            val obj = c.asJsonObject
            if (obj.get("name")?.asString == name) {
                return obj.get("uid")?.asString
            }
        }
        return null
    }

    private fun createCollection(collectionJson: String, workspaceId: String?): String {
        val body = JsonObject()
        body.add("collection", gson.fromJson(collectionJson, JsonObject::class.java))

        val url = if (workspaceId.isNullOrBlank()) {
            "https://api.getpostman.com/collections"
        } else {
            "https://api.getpostman.com/collections?workspace=$workspaceId"
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        println(response)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Postman create failed: ${response.statusCode()} ${response.body()}")
        }
        val json = gson.fromJson(response.body(), JsonObject::class.java)
        return json.getAsJsonObject("collection").get("uid").asString
    }

    private fun updateCollection(id: String, collectionJson: String) {
        val body = JsonObject()
        body.add("collection", gson.fromJson(collectionJson, JsonObject::class.java))

        val request = HttpRequest.newBuilder().uri(URI.create("https://api.getpostman.com/collections/$id")).header("X-Api-Key", apiKey).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Postman update failed: ${response.statusCode()} ${response.body()}")
        }
    }
}
