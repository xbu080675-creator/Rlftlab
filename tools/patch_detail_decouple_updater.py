from pathlib import Path

# 1) Schedule detail must not mutate main viewing target.
p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('''                            onMatchClick = { match ->
                                MatchSessionStore.selectScheduleMatch(match.matchId)
                                MatchDetailRepository.open(match)
                                selectedDetailMatch = match
                            }
''', '''                            onMatchClick = { match ->
                                MatchDetailRepository.open(match)
                                selectedDetailMatch = match
                            }
''', 1)
p.write_text(s, encoding='utf-8')

# 2) Version label opens update center; updater gets application Context.
p = Path('app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('import com.riftlab.app.stream.StreamPlatform\n', 'import com.riftlab.app.stream.StreamPlatform\nimport com.riftlab.app.update.AppUpdateManager\n', 1)
s = s.replace('''        var phase by remember { mutableIntStateOf(1) }
        val context = LocalContext.current
        val notificationPermission''', '''        var phase by remember { mutableIntStateOf(1) }
        val context = LocalContext.current
        var updateCenterOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
        LaunchedEffect(context) { AppUpdateManager.initialize(context) }
        val notificationPermission''', 1)
s = s.replace('''                Header()
                PhaseTabs''', '''                Header(onVersionClick = { updateCenterOpen = true })
                if (updateCenterOpen) UpdateCenterDialog(onClose = { updateCenterOpen = false })
                PhaseTabs''', 1)
s = s.replace('''private fun Header() {''', '''private fun Header(onVersionClick: () -> Unit) {''', 1)
old = '''        Text(
            BuildConfig.VERSION_NAME.replace("1.0.0-", "1.0 ").uppercase(),
            color = RiftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )'''
new = '''        Text(
            BuildConfig.VERSION_NAME.replace("1.0.0-", "1.0 ").uppercase(),
            color = RiftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onVersionClick).padding(horizontal = 6.dp, vertical = 8.dp)
        )'''
if old not in s:
    raise SystemExit('Header version label anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# 3) Manifest install permission + FileProvider.
p = Path('app/src/main/AndroidManifest.xml')
s = p.read_text(encoding='utf-8')
s = s.replace('''    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
''', '''    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
''', 1)
provider = '''
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/riftlab_update_paths" />
        </provider>
'''
s = s.replace('''        <service
            android:name=".overlay.RiftOverlayService"''', provider + '''
        <service
            android:name=".overlay.RiftOverlayService"''', 1)
p.write_text(s, encoding='utf-8')

# 4) Bump DEV build identity so OTA has a monotonic versionCode.
p = Path('app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('versionCode = 7', 'versionCode = 8', 1)
s = s.replace('versionName = "1.0.0-dev.7"', 'versionName = "1.0.0-dev.8"', 1)
s = s.replace('// dev.7: generic live provider router + match resolver baseline; registry API validated.', '// dev.8: independent match detail + official awards + in-app DEV OTA updater.')
p.write_text(s, encoding='utf-8')

print('patched schedule detail decouple + updater integration + dev.8')
