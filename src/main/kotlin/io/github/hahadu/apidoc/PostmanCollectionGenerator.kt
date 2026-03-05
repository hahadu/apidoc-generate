package io.github.hahadu.apidoc

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import hahadu.mvc.ApiDoc
import hahadu.mvc.BodyParam
import hahadu.mvc.BodyParams
import hahadu.mvc.Controller
import hahadu.mvc.ResponseDoc
import hahadu.mvc.Get
import hahadu.mvc.HeaderParam
import hahadu.mvc.Post
import hahadu.mvc.Put
import hahadu.mvc.Delete
import hahadu.mvc.HeaderParams
import hahadu.mvc.Patch
import hahadu.mvc.QueryParam
import hahadu.mvc.QueryParams
import hahadu.mvc.RouteGroup
import hahadu.mvc.UrlParam
import hahadu.mvc.UrlParams
import hahadu.mvc.UseAuth
import io.github.classgraph.ClassGraph
import java.util.UUID
import kotlin.collections.iterator
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation

data class PostmanConfig(
    val baseUrlHost: String,
    val protocol: String = "http",
    val collectionName: String,
    val description: String = "",
    val defaultGroup: String = "general",
    val headers: Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    ),
    val authToken: String? = null,
)

class PostmanCollectionGenerator(
    private val config: PostmanConfig,
) {
    fun generate(controllerPackages: List<String>): String {
        val controllers = scanControllers(controllerPackages)
        val doc = buildCollection(controllers)
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(doc)
    }

    private fun scanControllers(packages: List<String>): List<KClass<*>> {
        val scanner = ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages(*packages.toTypedArray())
            .scan()
        scanner.use { result ->
            return result.getClassesWithAnnotation(Controller::class.qualifiedName)
                .filter { !it.isAbstract }
                .map { it.loadClass().kotlin }
        }
    }

    private fun buildCollection(controllers: List<KClass<*>>): JsonObject {
        val collection = JsonObject()
        val info = JsonObject().apply {
            addProperty("name", config.collectionName)
            addProperty("_postman_id", UUID.randomUUID().toString())
            addProperty("description", config.description)
            addProperty("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json")
        }
        collection.add("info", info)

        val grouped = groupEndpoints(controllers)
        val items = JsonArray()
        for ((_, list) in grouped) {
            val folder = list.first().deepCopy().asJsonObject
            val itemsArr = folder.getAsJsonArray("item")
            for (endpoint in list.drop(1)) {
                itemsArr.add(endpoint)
            }
            items.add(folder)
        }
        collection.add("item", items)

        if (!config.authToken.isNullOrBlank()) {
            val auth = JsonObject()
            auth.addProperty("type", "bearer")
            val bearer = JsonArray()
            bearer.add(JsonObject().apply {
                addProperty("key", "token")
                addProperty("value", config.authToken)
                addProperty("type", "string")
            })
            auth.add("bearer", bearer)
            collection.add("auth", auth)
        }

        return collection
    }

    private fun groupEndpoints(controllers: List<KClass<*>>): Map<String, List<JsonObject>> {
        val groups = linkedMapOf<String, MutableList<JsonObject>>()
        for (controllerClass in controllers) {
            val controllerAnn = controllerClass.findAnnotation<Controller>() ?: continue
            val basePath = normalizePath(controllerAnn.path)
            val groupPath = normalizePath(controllerClass.findAnnotation<RouteGroup>()?.path ?: "")
            val classDoc = controllerClass.findAnnotation<ApiDoc>()
            val classTags = classDoc?.tags?.toList().orEmpty()

            for (fn in controllerClass.declaredMemberFunctions) {
                val method = httpMethodFor(fn) ?: continue
                val methodPath = methodPathFor(fn) ?: ""
                val fullPath = joinPaths(groupPath, basePath, normalizePath(methodPath))
                val doc = fn.findAnnotation<ApiDoc>()
                val groupName = doc?.tags?.firstOrNull()
                    ?: classTags.firstOrNull()
                    ?: config.defaultGroup
                val item = buildEndpointItem(fn, method, fullPath, doc)
                val folder = groups.getOrPut(groupName) { mutableListOf() }
                if (folder.isEmpty()) {
                    folder.add(folderMeta(groupName, classDoc?.description.orEmpty(), fn))
                }
                folder.add(item)
            }
        }
        return groups
            .mapValues { (_, list) -> list }
    }

    private fun folderMeta(name: String, description: String, fn: KFunction<*>): JsonObject {
        val folder = JsonObject()
        folder.addProperty("name", name)
        folder.addProperty("description", description)
        buildAuth(fn)?.let { folder.add("auth", it) }
        folder.add("event", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("listen", "prerequest")
                add("script", JsonObject().apply {
                    addProperty("type", "text/javascript")
                    add("exec", JsonArray().apply { add("") })
                })
            })
            add(JsonObject().apply {
                addProperty("listen", "test")
                add("script", JsonObject().apply {
                    addProperty("type", "text/javascript")
                    add("exec", JsonArray().apply { add("") })
                })
            })
        })
        folder.add("item", JsonArray())
        return folder
    }

    private fun buildEndpointItem(
        fn: KFunction<*>,
        method: String,
        path: String,
        doc: ApiDoc?,
    ): JsonObject {
        val request = JsonObject()
        request.addProperty("method", method)
        request.add("header", buildHeaders(fn))

        val body = buildBody(fn)
        if (body != null) {
            request.add("body", body)
        }

        request.add("url", buildUrl(path, fn))
        if (!doc?.description.isNullOrBlank()) {
            request.addProperty("description", doc.description)
        }

        val item = JsonObject()
        item.addProperty("name", doc?.summary?.ifBlank { null } ?: path)
        item.add("request", request)
        item.add("response", buildResponses(fn, request))
        return item
    }

    private fun buildHeaders(fn: KFunction<*>): JsonArray {
        val headers = JsonArray()
        config.headers.forEach { (k, v) ->
            headers.add(JsonObject().apply {
                addProperty("key", k)
                addProperty("value", v)
            })
        }
        val headerParams = collectHeaderParams(fn)
        for (ann in headerParams) {
            headers.add(JsonObject().apply {
                addProperty("key", ann.name)
                addProperty("value", ann.example.ifBlank { ann.description })
            })
        }
        return headers
    }

    private fun buildBody(fn: KFunction<*>): JsonObject? {
        val fields = collectBodyParams(fn).filter { it.name.isNotBlank() }
        if (fields.isEmpty()) return null
        val raw = linkedMapOf<String, Any?>()
        for (f in fields) {
            raw[f.name] = exampleValue(f)
        }
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val body = JsonObject()
        body.addProperty("mode", "raw")
        body.addProperty("raw", gson.toJson(raw))
        return body
    }

    private fun buildUrl(path: String, fn: KFunction<*>): JsonObject {
        val url = JsonObject()
        url.addProperty("protocol", config.protocol)
        url.addProperty("host", config.baseUrlHost)

        val rawPath = path.replace(Regex("/\\{(\\w+)\\??}")) { match ->
            "/:${match.groupValues[1]}"
        }
        url.addProperty("raw", rawPath.trimStart('/'))
        url.add("path", JsonArray().apply {
            rawPath.trim('/').split("/").filter { it.isNotBlank() }.forEach { add(it) }
        })

        val queryParams = collectQueryParams(fn)
        if (queryParams.isNotEmpty()) {
            val queryArr = JsonArray()
            for (q in queryParams) {
                queryArr.add(JsonObject().apply {
                    addProperty("key", q.name)
                    val value = q.example.ifBlank { defaultValueFor(q.type)?.toString() ?: "" }
                    addProperty("value", value)
                    addProperty("description", q.description)
                    addProperty("disabled", !q.required && value.isBlank())
                })
            }
            url.add("query", queryArr)
        }

        val urlParams = collectUrlParams(fn)
        if (urlParams.isNotEmpty()) {
            val vars = JsonArray()
            for (p in urlParams) {
                vars.add(JsonObject().apply {
                    addProperty("id", p.name)
                    addProperty("key", p.name)
                    addProperty("value", p.example.ifBlank { defaultValueFor(p.type)?.toString() ?: "" })
                    addProperty("description", p.description)
                })
            }
            url.add("variable", vars)
        }
        return url
    }

    private fun buildAuth(fn: KFunction<*>): JsonObject? {
        val token = config.authToken ?: extractBearerFromHeaders()
        if (token.isNullOrBlank()) return null
        val auth = JsonObject()
        auth.addProperty("type", "bearer")
        val bearer = JsonArray()
        bearer.add(JsonObject().apply {
            addProperty("key", "token")
            addProperty("value", token)
            addProperty("type", "string")
        })
        auth.add("bearer", bearer)
        return auth
    }

    private fun buildResponses(fn: KFunction<*> , request: JsonObject): JsonArray {
        val arr = JsonArray()
        val docs = fn.annotations.filterIsInstance<ResponseDoc>().filter { it.example.isNotBlank() }
        for (doc in docs) {
            val res = JsonObject()
            res.addProperty("name", doc.description.ifBlank { "Example" })
            res.add("originalRequest", request.deepCopy())
            res.addProperty("status", if (doc.code in 200..299) "OK" else "Error")
            res.addProperty("code", doc.code)
            res.addProperty("_postman_previewlanguage", "json")
            res.add("header", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("key", "Content-Type")
                    addProperty("value", "application/json")
                })
            })
            res.addProperty("body", doc.example)
            arr.add(res)
        }
        return arr
    }

    private fun requiresAuth(fn: KFunction<*>): Boolean {
        return fn.findAnnotation<UseAuth>() != null
    }

    private fun collectBodyParams(fn: KFunction<*>): List<BodyParam> {
        val direct = fn.annotations.filterIsInstance<BodyParam>()
        val container = fn.annotations.filterIsInstance<BodyParams>().flatMap { it.value.toList() }
        return direct + container
    }

    private fun collectHeaderParams(fn: KFunction<*>): List<HeaderParam> {
        val fnLevel = fn.annotations.filterIsInstance<HeaderParam>()
        val fnContainer = fn.annotations.filterIsInstance<HeaderParams>().flatMap { it.value.toList() }
        val paramLevel = fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .mapNotNull { it.findAnnotation<HeaderParam>() }
        return fnLevel + fnContainer + paramLevel
    }

    private fun collectQueryParams(fn: KFunction<*>): List<QueryParam> {
        val fnLevel = fn.annotations.filterIsInstance<QueryParam>()
        val fnContainer = fn.annotations.filterIsInstance<QueryParams>().flatMap { it.value.toList() }
        val paramLevel = fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .mapNotNull { it.findAnnotation<QueryParam>() }
        return fnLevel + fnContainer + paramLevel
    }

    private fun collectUrlParams(fn: KFunction<*>): List<UrlParam> {
        val fnLevel = fn.annotations.filterIsInstance<UrlParam>()
        val fnContainer = fn.annotations.filterIsInstance<UrlParams>().flatMap { it.value.toList() }
        val paramLevel = fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .mapNotNull { it.findAnnotation<UrlParam>() }
        return fnLevel + fnContainer + paramLevel
    }

    private fun exampleValue(param: BodyParam): Any? {
        if (param.example.isNotBlank()) return param.example
        return defaultValueFor(param.type)
    }

    private fun defaultValueFor(type: KClass<*>): Any? = when (type) {
        String::class -> "string"
        Int::class, Short::class, Long::class -> 0
        Float::class, Double::class -> 0
        Boolean::class -> false
        else -> null
    }

    private fun extractBearerFromHeaders(): String? {
        val auth = config.headers["Authorization"] ?: return null
        val idx = auth.indexOf("Bearer ")
        if (idx < 0) return null
        return auth.substring(idx + 7).trim().ifBlank { null }
    }

    private fun httpMethodFor(fn: KFunction<*>): String? = when {
        fn.hasAnnotation<Get>() -> "GET"
        fn.hasAnnotation<Post>() -> "POST"
        fn.hasAnnotation<Put>() -> "PUT"
        fn.hasAnnotation<Delete>() -> "DELETE"
        fn.hasAnnotation<Patch>() -> "PATCH"
        else -> null
    }

    private fun methodPathFor(fn: KFunction<*>): String? {
        return fn.findAnnotation<Get>()?.path
            ?: fn.findAnnotation<Post>()?.path
            ?: fn.findAnnotation<Put>()?.path
            ?: fn.findAnnotation<Delete>()?.path
            ?: fn.findAnnotation<Patch>()?.path
    }

    private fun joinPaths(vararg parts: String): String {
        val cleaned = parts.filter { it.isNotBlank() }.map { it.trim('/') }
        return "/" + cleaned.joinToString("/")
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
}
