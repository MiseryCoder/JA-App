package eu.kanade.tachiyomi.data.sync

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

/**
 * The few calls of the Google Drive REST API the sync needs. They all block, so call them off the main thread.
 */
@Inject
class GoogleDriveClient(
    private val networkHelper: NetworkHelper,
    private val json: Json,
) {

    /**
     * Returns the most recently modified file called [name] that this app created, or null if there's none.
     * With the access the app asks for, Drive only ever lists the app's own files.
     */
    fun findFile(token: String, name: String, mimeType: String? = null): DriveFile? {
        val query = buildString {
            append("name = '$name' and trashed = false")
            if (mimeType != null) append(" and mimeType = '$mimeType'")
        }
        val url = FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("fields", "files(id,md5Checksum)")
            .build()

        val body = execute(Request.Builder().url(url).get(), token) { it.body.string() }
        val file = json.parseToJsonElement(body).jsonObject["files"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: return null
        val id = file["id"]?.jsonPrimitive?.content ?: return null
        return DriveFile(id = id, md5 = file["md5Checksum"]?.jsonPrimitive?.content)
    }

    fun download(token: String, fileId: String, target: File) {
        val url = "$FILES_URL/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()

        execute(Request.Builder().url(url).get(), token) { response ->
            target.sink().buffer().use { it.writeAll(response.body.source()) }
        }
    }

    /**
     * @return the ID of the new folder.
     */
    fun createFolder(token: String, name: String): String {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
        }
        val request = Request.Builder()
            .url("$FILES_URL?fields=id")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))

        return execute(request, token) { parseId(it) }
    }

    /**
     * @return the ID of the new file.
     */
    fun createFile(token: String, name: String, folderId: String, content: File): String {
        val metadata = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add(folderId) }
        }
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED_MEDIA_TYPE)
            .addPart(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.asRequestBody(BINARY_MEDIA_TYPE))
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_URL?uploadType=multipart&fields=id")
            .post(body)

        return execute(request, token) { parseId(it) }
    }

    /**
     * Replaces the content of the file [fileId].
     */
    fun updateFile(token: String, fileId: String, content: File) {
        val request = Request.Builder()
            .url("$UPLOAD_URL/$fileId?uploadType=media")
            .patch(content.asRequestBody(BINARY_MEDIA_TYPE))

        execute(request, token) {}
    }

    private fun parseId(response: Response): String {
        return json.parseToJsonElement(response.body.string()).jsonObject["id"]
            ?.jsonPrimitive
            ?.content
            ?: throw IOException("Google Drive didn't return the ID of the created file")
    }

    private fun <T> execute(request: Request.Builder, token: String, block: (Response) -> T): T {
        val call = networkHelper.client.newCall(
            request
                .header("Authorization", "Bearer $token")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build(),
        )
        return call.execute().use { response ->
            when {
                response.code == 401 -> throw UnauthorizedException()
                !response.isSuccessful -> {
                    throw IOException("Google Drive error ${response.code}: ${response.body.string().take(300)}")
                }
                else -> block(response)
            }
        }
    }

    /**
     * @param md5 checksum of the content as lowercase hex, which Drive doesn't have for folders.
     */
    class DriveFile(val id: String, val md5: String?)

    /**
     * Drive rejected the access token.
     */
    class UnauthorizedException : IOException("Google Drive rejected the access token")

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}

private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
private val MULTIPART_RELATED_MEDIA_TYPE = "multipart/related".toMediaType()
