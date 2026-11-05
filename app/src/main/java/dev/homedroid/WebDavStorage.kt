package dev.homedroid

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory

/** One private WebDAV endpoint per phone. */
class WebDavStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("webdav", Context.MODE_PRIVATE)
    private val cipherAlias = "homedroid-webdav-v1"

    data class Config(val url: String, val username: String, val password: String)
    data class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long)

    fun configured() = prefs.getString("url", "").orEmpty().isNotEmpty()
    fun endpointUrl() = prefs.getString("url", "").orEmpty()
    val manualFreeBytes: Long get() = prefs.getLong("manual_free_bytes", 0L)
    fun publicConfig() = JSONObject().put("configured", configured())
        .put("host", try { URI(endpointUrl()).host.orEmpty() } catch (_: Exception) { "" })
        .put("manualFreeBytes", manualFreeBytes)

    @Synchronized fun save(urlValue: String, usernameValue: String?, passwordValue: String?, manualFreeBytes: Long? = null): String? {
        val uri = try { URI(urlValue.trim()) } catch (_: Exception) { return "Enter a valid HTTPS WebDAV URL" }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return "The WebDAV URL must use HTTPS and cannot contain credentials or a query"
        }
        val normalizedUrl = uri.toASCIIString().trimEnd('/') + "/"
        val old = if (prefs.getString("url", "") == normalizedUrl) credentials() else null
        val username = usernameValue?.trim()?.takeIf { it.isNotEmpty() } ?: old?.username.orEmpty()
        val password = passwordValue?.takeIf { it.isNotEmpty() } ?: old?.password.orEmpty()
        prefs.edit().putString("url", normalizedUrl)
            .putString("credentials", encrypt(username + "\u0000" + password))
            .putLong("manual_free_bytes", manualFreeBytes ?: if (old != null) this.manualFreeBytes else 0L).commit()
        return null
    }

    @Synchronized fun clear() {
        prefs.edit().clear().commit()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(cipherAlias)) deleteEntry(cipherAlias) } } catch (_: Exception) {}
    }

    @Synchronized fun reduceManualFreeBytes(amount: Long) {
        if (amount > 0 && manualFreeBytes > 0) prefs.edit().putLong("manual_free_bytes", (manualFreeBytes - amount).coerceAtLeast(0L)).apply()
    }

    private fun config(): Config? {
        val url = prefs.getString("url", "").orEmpty()
        if (url.isEmpty()) return null
        val c = credentials() ?: return null
        return Config(url, c.username, c.password)
    }

    private fun credentials(): Config? {
        return try {
            val raw = prefs.getString("credentials", null) ?: return null
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            if (bytes.size <= 12) return null
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8)
            val i = plain.indexOf('\u0000')
            Config(prefs.getString("url", "").orEmpty(), if (i < 0) "" else plain.substring(0, i), if (i < 0) plain else plain.substring(i + 1))
        } catch (_: Exception) { null }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(cipherAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(cipherAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun auth(c: Config, connection: HttpURLConnection) {
        if (c.username.isNotEmpty() || c.password.isNotEmpty()) {
            val b64 = Base64.encodeToString((c.username + ":" + c.password).toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $b64")
        }
    }

    private fun url(c: Config, path: String): URL {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." || it.contains('\\') || it.any { ch -> ch.isISOControl() } }) { "Invalid WebDAV path" }
        val encoded = parts.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return URL(c.url + encoded)
    }

    private fun connection(c: Config, path: String, method: String): HttpURLConnection {
        val con = (url(c, path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        auth(c, con)
        return con
    }

    private fun checked(con: HttpURLConnection, allow: Set<Int> = emptySet()) {
        val status = con.responseCode
        if (status !in 200..299 && status !in allow) {
            con.disconnect()
            throw IOException("WebDAV returned HTTP $status")
        }
    }

    /** Depth-one listing. Also reads standard WebDAV quota properties when the bridge exposes them. */
    fun list(path: String): Pair<List<Entry>, Long?> {
        val c = config() ?: throw IOException("Configure a WebDAV account first")
        val con = connection(c, path, "PROPFIND")
        con.setRequestProperty("Depth", "1")
        con.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        con.doOutput = true
        return try {
            con.outputStream.use { it.write("""<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:quota-available-bytes/></d:prop></d:propfind>""".toByteArray()) }
            checked(con)
            val bytes = con.inputStream.use { it.readBytes() }
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
                isNamespaceAware = true
            }
            val doc = factory.newDocumentBuilder().parse(bytes.inputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val out = mutableListOf<Entry>()
            var free: Long? = null
            for (i in 0 until responses.length) {
                val response = responses.item(i) as? org.w3c.dom.Element ?: continue
                val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val decoded = java.net.URLDecoder.decode(href.substringBefore('?'), "UTF-8").trimEnd('/')
                val basePath = URI(c.url).path.trimEnd('/')
                val rel = decoded.removePrefix(basePath).trim('/')
                if (rel == path.trim('/') || rel.isEmpty() && path.isEmpty()) {
                    val quota = response.getElementsByTagNameNS("DAV:", "quota-available-bytes").item(0)?.textContent?.trim()?.toLongOrNull()
                    if (quota != null) free = quota
                    continue
                }
                if (rel.substringBeforeLast('/', "") != path.trim('/')) continue
                val prop = response.getElementsByTagNameNS("DAV:", "prop").item(0) as? org.w3c.dom.Element ?: continue
                val name = rel.substringAfterLast('/')
                val isDir = prop.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = prop.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                val modified = prop.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.trim()?.let {
                    try { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
                } ?: 0L
                out += Entry(name, rel, isDir, size, modified)
            }
            out.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() })) to free
        } finally { con.disconnect() }
    }

    fun download(path: String, range: String?): Response {
        val c = config() ?: return Response.error(503, "Configure a WebDAV account first")
        val con = try { connection(c, path, "GET") } catch (e: Exception) { return Response.error(502, e.message ?: "WebDAV unavailable") }
        if (!range.isNullOrBlank()) con.setRequestProperty("Range", range)
        val status = try { con.responseCode } catch (e: Exception) { con.disconnect(); return Response.error(502, e.message ?: "WebDAV unavailable") }
        if (status !in 200..299) { con.disconnect(); return Response.error(if (status == 404) 404 else 502, "WebDAV returned HTTP $status") }
        val headers = mutableMapOf<String, String>()
        listOf("Content-Range", "Accept-Ranges", "Content-Disposition").forEach { h -> con.getHeaderField(h)?.let { headers[h] = it } }
        if (headers["Content-Disposition"] == null) {
            val name = path.substringAfterLast('/').ifEmpty { "download" }
            headers["Content-Disposition"] = "attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        }
        val length = con.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
        return Response(status, ByteArray(0), con.contentType ?: "application/octet-stream", headers,
            stream = { out -> try { con.inputStream.use { it.copyTo(out) } } finally { con.disconnect() } }, length = length)
    }

    fun upload(r: Request, path: String): Response {
        val c = config() ?: return Response.error(503, "Configure a WebDAV account first")
        if (path.trim('/').isEmpty()) return Response.error(409, "Choose a file name")
        val parent = path.substringBeforeLast('/', "")
        if (list(parent).first.any { it.name == path.substringAfterLast('/') }) return Response.error(409, "${path.substringAfterLast('/')} already exists")
        ensureParents(path)
        val con = try { connection(c, path, "PUT") } catch (e: Exception) { return Response.error(502, e.message ?: "WebDAV unavailable") }
        con.doOutput = true
        if (r.length >= 0) con.setFixedLengthStreamingMode(r.length)
        return try {
            con.outputStream.use { if (!r.bodyTo(it)) return Response.error(400, "Upload was cut off") }
            val status = con.responseCode
            if (status in 200..299) { reduceManualFreeBytes(r.length); Response.json(JSONObject().put("ok", true), 201) }
            else Response.error(if (status == 507) 507 else 502, "WebDAV returned HTTP $status")
        } catch (e: Exception) { Response.error(502, e.message ?: "Upload failed") }
        finally { con.disconnect() }
    }

    fun mkdir(path: String): Response {
        val c = config() ?: return Response.error(503, "Configure a WebDAV account first")
        val con = connection(c, path, "MKCOL")
        return try { val status = con.responseCode; if (status in 200..299) Response.ok() else Response.error(if (status == 405) 409 else 502, "WebDAV returned HTTP $status") }
        finally { con.disconnect() }
    }

    private fun ensureParents(path: String) {
        val parts = path.trim('/').split('/').dropLast(1)
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            val c = config() ?: throw IOException("Configure a WebDAV account first")
            val con = connection(c, current, "MKCOL")
            try {
                val status = con.responseCode
                if (status !in 200..299 && status != 405 && status != 409) throw IOException("WebDAV returned HTTP $status while creating a folder")
            } finally { con.disconnect() }
        }
    }

    fun search(query: String, max: Int = 100): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || !configured()) return emptyList()
        val results = mutableListOf<Entry>()
        val todo = ArrayDeque<Pair<String, Int>>().apply { add("" to 0) }
        var visited = 0
        while (todo.isNotEmpty() && results.size < max && visited < 200) {
            val (dir, depth) = todo.removeFirst(); visited++
            val entries = list(dir).first
            for (e in entries) {
                if (e.name.contains(q, ignoreCase = true)) results += e
                if (e.dir && depth < 12 && visited < 200) todo.add(e.path to depth + 1)
                if (results.size >= max) break
            }
        }
        return results
    }
}
