from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"target not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Route all persisted Riot gateway reads through the resilient transport.
replace(
    "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt",
'''    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiftLab/1.0 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from LoL Esports: ${body.take(180)}")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
''',
'''    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
''')

replace(
    "app/src/main/java/com/riftlab/app/data/LolEsportsStandingsClient.kt",
'''    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiftLab/1.0 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code from LoL Esports standings: ${body.take(180)}")
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
''',
'''    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
''')

replace(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
'''    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiftLab/1.0 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from LoL Esports: ${body.take(160)}")
            if (body.isBlank()) throw IOException("Empty response from LoL Esports")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
''',
'''    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
''')

# Schedule source label must say when the mirror is serving data instead of pretending it is direct.
replace(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
'''        return "Riot Schedule · ${matches.size} 场 · 已结束 $completed · $currentText · $nextText"
''',
'''        return "${RiotResilientHttp.sourceLabel()} · Schedule · ${matches.size} 场 · 已结束 $completed · $currentText · $nextText"
''')

# Team pages must still load dynamic staff/management when Riot roster itself is unreachable.
replace(
    "app/src/main/java/com/riftlab/app/data/TeamDetailRepository.kt",
'''            val lookup = team.id.ifBlank { team.slug }
            if (lookup.isBlank()) {
                val resolved = runCatching { assetProvider.resolve(team.copy(imageUrl = "")) }
                    .getOrDefault(cachedImage)
                    .let(EsportsAssetCache::normalize)
                if (resolved.isNotBlank()) EsportsAssetCache.putTeam(resolved, *aliases)
                _state.value = TeamDetailState(
                    team = team,
                    imageUrl = resolved,
                    loading = false,
                    status = "战队资料暂不可用",
                    errorMessage = "缺少 Riot team id/slug"
                )
                return@launch
            }

            val detailResult = runCatching { source.fetchTeam(lookup) }
            val baseDetails = detailResult.getOrNull()
''',
'''            val lookup = team.id.ifBlank { team.slug }
            val detailResult: Result<EsportsTeamDetails?> = if (lookup.isBlank()) {
                Result.success(null)
            } else {
                runCatching { source.fetchTeam(lookup) }
            }
            val baseDetails = detailResult.getOrNull()
''')

replace(
    "app/src/main/java/com/riftlab/app/data/TeamDetailRepository.kt",
'''            val dynamicSupplement = if (baseDetails != null) {
                runCatching { dynamicProvider.fetch(effectiveTeam, baseDetails) }
                    .getOrElse {
                        TeamDynamicSupplement(
                            profile = TeamProfileSupplement(status = "管理层动态目录同步失败 · ${it.message?.take(80).orEmpty()}"),
                            staff = TeamStaffSupplement(status = "教练组动态目录同步失败"),
                            sourceMode = "error"
                        )
                    }
            } else {
                TeamDynamicSupplement(
                    profile = TeamProfileSupplement(status = "管理层动态目录等待 Riot roster"),
                    staff = TeamStaffSupplement(status = "教练组动态目录等待 Riot roster"),
                    sourceMode = "waiting"
                )
            }

            val staffSupplement = dynamicSupplement.staff
''',
'''            val dynamicBase = baseDetails ?: EsportsTeamDetails(
                id = effectiveTeam.id,
                slug = effectiveTeam.slug,
                code = effectiveTeam.code,
                name = effectiveTeam.name,
                imageUrl = effectiveTeam.imageUrl,
                players = emptyList()
            )
            val dynamicSupplement = runCatching { dynamicProvider.fetch(effectiveTeam, dynamicBase) }
                .getOrElse {
                    TeamDynamicSupplement(
                        profile = TeamProfileSupplement(status = "管理层动态目录同步失败 · ${it.message?.take(80).orEmpty()}"),
                        staff = TeamStaffSupplement(status = "教练组动态目录同步失败"),
                        sourceMode = "error"
                    )
                }

            val staffSupplement = dynamicSupplement.staff
''')

replace(
    "app/src/main/java/com/riftlab/app/data/TeamDetailRepository.kt",
'''            val enrichedPlayers = baseDetails?.players?.map { player ->
''',
'''            val enrichedPlayers = dynamicBase.players.map { player ->
''')

replace(
    "app/src/main/java/com/riftlab/app/data/TeamDetailRepository.kt",
'''            val details = baseDetails?.copy(
                players = enrichedPlayers,
                staff = staff,
                management = management,
                socialLinks = teamSocialLinks
            )
            if (details != null) cache[key] = details
''',
'''            val details = dynamicBase.copy(
                players = enrichedPlayers,
                staff = staff,
                management = management,
                socialLinks = teamSocialLinks
            )
            cache[key] = details
''')

# Initialize persistent mirror cache from Application and allow bundled seed fallback.
replace(
    "app/src/main/java/com/riftlab/app/RiftLabApplication.kt",
'''import coil.memory.MemoryCache
''',
'''import coil.memory.MemoryCache
import com.riftlab.app.data.RiotPersistedMirror
''')
replace(
    "app/src/main/java/com/riftlab/app/RiftLabApplication.kt",
'''class RiftLabApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
''',
'''class RiftLabApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        RiotPersistedMirror.initialize(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
''')

# Hotfix version.
replace(
    "app/build.gradle.kts",
'''        versionCode = 28
        versionName = "1.0.0-dev.28"
''',
'''        versionCode = 29
        versionName = "1.0.0-dev.29"
''')
replace(
    "app/build.gradle.kts",
'''// dev.28: canonical people entities, sourced staff avatars, and employment timelines.
''',
'''// dev.29: resilient Riot persisted mirror and non-blocking team dynamic data fallback.
''')

# Copy current mirror into APK as a last-resort seed when the data file exists.
for workflow in (".github/workflows/ota-direct.yml", ".github/workflows/android-build.yml"):
    p = ROOT / workflow
    text = p.read_text(encoding="utf-8")
    marker = '''      - uses: gradle/actions/setup-gradle@v4
'''
    if marker not in text:
        raise SystemExit(f"gradle marker not found in {workflow}")
    copy_step = '''      - name: Bundle Riot mirror seed
        shell: bash
        run: |
          if [ -f data/lpl/riot_persisted_mirror.json ]; then
            mkdir -p app/src/main/assets
            cp data/lpl/riot_persisted_mirror.json app/src/main/assets/riot_persisted_mirror.json
          fi
'''
    if "Bundle Riot mirror seed" not in text:
        text = text.replace(marker, copy_step + marker, 1)
    p.write_text(text, encoding="utf-8")

# Keep OTA note compact; first paragraph is what updater shows.
p = ROOT / "DEV_CHANGELOG.txt"
text = p.read_text(encoding="utf-8")
new_head = (
    "dev.29：修复移动网络无法连接 esports-api.lolesports.com 时赛程、排名、战队详情整块空白的问题。"
    "Riot Persisted Gateway 仍为主源；直连失败时自动切换到 RiftLab Riot Mirror，镜像由 GitHub Actions 每 10 分钟直接从 Riot 拉取，"
    "并同时保留设备磁盘缓存和 APK 内置种子快照。赛程状态会明确标注 Mirror，不把缓存冒充实时直连。"
    "战队详情也解除对 Riot roster 的硬依赖：即使 getTeams 暂时不可达，管理层、教练组、运营关系和人物履历仍可从 Dynamic Team Data 正常显示；"
    "只有未核实的选手 roster 保持空缺，不使用 Mock。"
)
if not text.startswith("dev.28："):
    raise SystemExit("unexpected changelog head")
p.write_text(new_head + "\n\n" + text, encoding="utf-8")
