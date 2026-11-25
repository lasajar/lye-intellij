package com.lasajar.lde.registry

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * An lde registry, queried through its generated `dist/index.json`.
 * The index is cached in memory and on disk under [cacheDir]; it is re-downloaded once older than [maxAge],
 * falling back to the stale copy when the download fails.
 */
internal class LdeRegistry(
    val indexUrl: String = DEFAULT_INDEX_URL,
    /** Base URL of the registry's `packages/` directory holding one portfile per package. */
    val packagesUrl: String = DEFAULT_PACKAGES_URL,
    private val cacheDir: Path = PathManager.getSystemDir().resolve("lye").resolve("lde-registry"),
    private val maxAge: Duration = DEFAULT_MAX_AGE,
) {
    private val indexFile: Path get() = cacheDir.resolve("index.json")
    private val mutex = Mutex()

    @Volatile private var cached: List<LdeIndexEntry>? = null
    @Volatile private var fetchedAt: Instant = Instant.EPOCH

    /** Installable versions per package name, newest first; filled lazily by [versions]. */
    private val versionsCache = ConcurrentHashMap<String, List<String>>()

    /** README markdown per package name; filled lazily by [readme]. */
    private val readmeCache = ConcurrentHashMap<String, String>()

    /** All packages in the registry index, downloading it if the cache is missing or older than [maxAge]. */
    suspend fun index(forceRefresh: Boolean = false): List<LdeIndexEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                cached?.takeIf { isFresh(fetchedAt) }?.let { return@withContext it }
                readCache()?.takeIf { isFresh(fetchedAt) }?.let { return@withContext it }
            }
            try {
                download()
            } catch (e: IOException) {
                logger<LdeRegistry>().warn("Failed to download lde registry index from $indexUrl", e)
                cached ?: readCache() ?: throw e
            }
        }
    }

    /** Non-revoked versions of package [name], newest first, downloaded from its portfile and cached in memory. */
    suspend fun versions(name: String): List<String> {
        versionsCache[name]?.let { return it }
        return withContext(Dispatchers.IO) {
            val text = HttpRequests.request("$packagesUrl/$name.json").readString()
            val json = runCatching { JsonParser.parseString(text) as JsonObject }
                .getOrElse { throw IOException("Malformed lde registry portfile for '$name'", it) }
            val revoked = json.strings("revoked").toSet()
            val versions = (json.get("versions") as? JsonObject)?.keySet().orEmpty().filter { it !in revoked }.reversed()
            versionsCache[name] = versions
            versions
        }
    }

    /**
     * README markdown of package [entry] from the default branch of its git repository, cached in memory,
     * or `null` when the repository is not hosted on GitHub or has no `README.md`.
     */
    suspend fun readme(entry: LdeIndexEntry): String? {
        readmeCache[entry.name]?.let { return it }
        val (owner, repo) = entry.git?.trim()?.let(GITHUB_REPO::matchEntire)?.destructured ?: return null
        return withContext(Dispatchers.IO) {
            val text = try {
                HttpRequests.request("https://raw.githubusercontent.com/$owner/$repo/HEAD/README.md").readString()
            } catch (e: HttpRequests.HttpStatusException) {
                if (e.statusCode == 404) return@withContext null
                throw e
            }
            readmeCache[entry.name] = text
            text
        }
    }

    /** Drops the in-memory and on-disk copies of the index. */
    suspend fun clearCache(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            cached = null
            fetchedAt = Instant.EPOCH
            versionsCache.clear()
            readmeCache.clear()
            Files.deleteIfExists(indexFile)
        }
    }

    private fun isFresh(time: Instant) = Duration.between(time, Instant.now()) < maxAge

    private fun readCache(): List<LdeIndexEntry>? {
        val file = indexFile
        if (!Files.isRegularFile(file)) return null
        val entries = runCatching { parseIndex(Files.readString(file)) }.getOrNull() ?: return null
        cached = entries
        fetchedAt = Files.getLastModifiedTime(file).toInstant()
        return entries
    }

    private fun download(): List<LdeIndexEntry> {
        val text = HttpRequests.request(indexUrl).readString()
        val entries = runCatching { parseIndex(text) }.getOrElse { throw IOException("Malformed lde registry index", it) }
        Files.createDirectories(cacheDir)
        Files.writeString(indexFile, text)
        cached = entries
        fetchedAt = Instant.now()
        return entries
    }

    private fun parseIndex(text: String): List<LdeIndexEntry> =
        (JsonParser.parseString(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(::parseEntry) }

    private fun parseEntry(json: JsonObject): LdeIndexEntry? = LdeIndexEntry(
        name = json.string("name") ?: return null,
        description = json.string("description"),
        authors = json.strings("authors"),
        tags = json.strings("tags"),
        latest = json.string("latest"),
        git = json.string("git"),
        lastUpdated = json.string("lastUpdated")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        firstPublished = json.string("firstPublished")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        approximate = json.get("approximate")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
    )

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.strings(key: String): List<String> =
        (get(key) as? JsonArray)?.mapNotNull { e: JsonElement -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()


    inner class Query {
        private var term: String? = null
        private var tag: String? = null
        private var author: String? = null

        /** Matches packages whose name contains [term], ignoring case. */
        fun term(term: String?) = apply { this.term = term }

        /** Matches packages tagged with [tag], ignoring case. */
        fun tag(tag: String?) = apply { this.tag = tag }

        /** Matches packages listing [author] among their authors, ignoring case. */
        fun author(author: String?) = apply { this.author = author }

        fun get(): List<LdeIndexEntry> {
            val term = term
            val tag = tag
            val author = author
            if (term == null && tag == null && author == null) return emptyList()

            return cached.orEmpty().filter { entry ->
                (term == null || entry.name.contains(term, ignoreCase = true)) &&
                    (tag == null || entry.tags.any { it.equals(tag, ignoreCase = true) }) &&
                    (author == null || entry.authors.any { it.equals(author, ignoreCase = true) })
            }
        }
    }

    fun query() = Query()

    companion object {
        const val DEFAULT_INDEX_URL = "https://raw.githubusercontent.com/lde-org/registry/dist/index.json"
        const val DEFAULT_PACKAGES_URL = "https://raw.githubusercontent.com/lde-org/registry/master/packages"
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(1)
        private val GITHUB_REPO = Regex("""https?://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?""")
    }
}

/**
 * One package summary in the registry's generated `dist/index.json`.
 * Mirrors `schemas/index.schema.json` from https://github.com/lde-org/registry.
 */
internal data class LdeIndexEntry(
    val name: String,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Latest non-revoked version, if any. */
    val latest: String? = null,
    val git: String? = null,
    /** When a new version was last added. */
    val lastUpdated: Instant? = null,
    val firstPublished: Instant? = null,
    /** Whether the dates were reconstructed from registry history and are estimates. */
    val approximate: Boolean = false,
)

/**
 * A package entry (portfile) in an lde registry, stored as `packages/<name>.json`.
 * Mirrors `schemas/registry.schema.json` from https://github.com/lde-org/registry.
 */
internal data class LdePackage(
    val name: String,
    val versions: Map<String, LdeVersionSource>,
    val description: String? = null,
    val license: String? = null,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val git: String? = null,
    val branch: String = DEFAULT_BRANCH,
    /** Package name to version constraint. */
    val dependencies: Map<String, String> = emptyMap(),
    /** Version keys that must not resolve for installs; each must exist in [versions]. */
    val revoked: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_BRANCH = "main"
    }
}

/** Where the source for one version of an [LdePackage] comes from. */
internal sealed interface LdeVersionSource {
    /** Bare commit hash, resolved against the package's own `git` and `branch`. */
    data class Commit(val commit: String) : LdeVersionSource

    /** Commit with optional overrides of the package's `git` and `branch`. */
    data class GitRef(
        val commit: String,
        val git: String? = null,
        val branch: String? = null,
        val sha256: String? = null,
    ) : LdeVersionSource

    /** Downloadable source archive. */
    data class Archive(
        val archive: String,
        val sha256: String,
        val size: Long? = null,
    ) : LdeVersionSource
}
