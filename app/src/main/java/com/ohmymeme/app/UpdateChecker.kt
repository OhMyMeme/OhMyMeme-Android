package com.ohmymeme.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val REPO = "OhMyMeme/OhMyMeme-Android"
    private const val GITHUB_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    private val GH_MIRRORS = listOf(
        "https://github.dpik.top/",
        "https://gh.dpik.top/",
        "https://gh-proxy.org/",
        "https://proxy.starsfire.top/-----"
    )

    data class UpdateInfo(
        val latest: String,
        val downloadUrl: String,
        val hasUpdate: Boolean,
        val error: String
    )

    fun parseVersion(v: String): List<Int> {
        val parts = v.trim().trimStart('v', 'V').split(".")
        return parts.mapNotNull { it.toIntOrNull() }
    }

    /** 阻塞调用，须在后台线程执行 */
    fun checkLatest(currentVersion: String): UpdateInfo {
        val current = parseVersion(currentVersion)
        val (tag, url) = fetchLatest() ?: return UpdateInfo("", "", false, "无法连接到 GitHub，请检查网络设置")
        val latest = tag.trimStart('v', 'V')
        return UpdateInfo(
            latest = latest,
            downloadUrl = url,
            hasUpdate = compareVersions(parseVersion(latest), current) > 0,
            error = ""
        )
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private fun fetchLatest(): Pair<String, String>? {
        return try {
            val conn = URL(GITHUB_LATEST).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "OhMyMeme-Android")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val html = json.optString("html_url", "")
            // 优先找 apk 资产，否则用 release 页面
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            Pair(tag, apkUrl.ifEmpty { html })
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 依次探测镜像源，返回第一个可访问的下载地址；全部失败回退直连。
     * 桌面端 updater.py `_urlretrieve_mirror` 迁移：仅探测连通性（HEAD），
     * 实际下载交给系统下载管理器/浏览器。
     */
    fun mirrorDownloadUrl(url: String): String {
        if (url.isEmpty()) return url
        if (!url.startsWith("https://github.com/")) return url
        for (mirror in GH_MIRRORS) {
            val candidate = mirror + url
            if (reachable(candidate)) return candidate
        }
        return url
    }

    private fun reachable(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "OhMyMeme-Android")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }
}
