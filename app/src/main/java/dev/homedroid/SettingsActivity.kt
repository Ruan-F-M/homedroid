package dev.homedroid

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.app.usage.StorageStatsManager
import android.os.storage.StorageManager
import android.widget.*
import java.io.File

/** Phone settings apply at the point of interaction; dialogs keep edits explicit. */
class SettingsActivity : MobileActivity() {
    private lateinit var cfg: Config
    private lateinit var backgroundStatus: TextView
    private var renderedSettings = ""
    private fun signature() = listOf(cfg.sshEnabled, cfg.webEnabled, cfg.tailscaleEnabled, cfg.autostart).joinToString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        val page = design.page("Settings")
        page.addView(design.label("PREFERENCES"))
        page.addView(design.row("Appearance", "${cfg.appearance.replaceFirstChar { it.uppercase() }} · light, dark or follow your phone") {
            val values = arrayOf("system", "light", "dark")
            AlertDialog.Builder(this).setTitle("Appearance")
                .setSingleChoiceItems(arrayOf("Follow phone", "Light", "Dark"), values.indexOf(cfg.appearance)) { dialog, index ->
                    cfg.appearance = values[index]; dialog.dismiss(); recreate()
                }.setNegativeButton(android.R.string.cancel, null).show()
        })
        val startup = design.card()
        design.switch(startup, "Start on boot", "Bring the server back when this phone restarts.", cfg.autostart) { cfg.autostart = it }
        page.addView(startup)
        page.addView(design.label("SERVICES & CONNECTIONS"))
        val modules = design.card()
        modules.addView(design.text("Changes restart active services.", 13f, design.muted))
        design.switch(modules, "SSH & SFTP", "Secure shell and file transfers · port ${cfg.sshPort}", cfg.sshEnabled) { cfg.sshEnabled = it; applyServices() }
        design.switch(modules, "Web hosting", "Serve websites with Caddy · port ${cfg.webPort}", cfg.webEnabled) { cfg.webEnabled = it; applyServices() }
        design.switch(modules, "Tailscale", "Private access from your own devices, anywhere.", cfg.tailscaleEnabled) { cfg.tailscaleEnabled = it; applyServices() }
        page.addView(modules)
        page.addView(design.row("Tailscale setup", "Device name and optional authentication key") { editTailscale() })
        page.addView(design.row("Cloudflare Tunnel", "Connect or disconnect with a tunnel token") { editTunnel() })
        page.addView(design.row("WebDAV storage", if (WebDavStorage(this).configured()) "A remote storage account is configured on this phone" else "Connect this phone to a WebDAV service") { editWebDav() })
        page.addView(design.row("All modules", "AI, camera and advanced network options") { DashboardActivity.open(this, "modules") })
        page.addView(design.label("ACCESS & SECURITY"))
        page.addView(design.row("Dashboard password", "Change the password and sign out browser sessions") { editPassword() })
        page.addView(design.row("SSH public keys", "Choose who can connect to this phone") { editKeys() })
        page.addView(design.row("Terminal & access history", "Open a shell and review recent connections") { DashboardActivity.open(this, "ssh") })
        page.addView(design.label("PHONE & PERMISSIONS"))
        val background = design.card()
        background.addView(design.text("Background running", 17f, design.ink, true))
        backgroundStatus = design.text("", 14f, design.muted); background.addView(backgroundStatus)
        background.addView(design.button("Battery settings") {
            val pm = getSystemService(PowerManager::class.java)
            val intent = if (pm.isIgnoringBatteryOptimizations(packageName)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            systemSettings(intent)
        })
        page.addView(background)
        page.addView(design.row("App permissions", "Camera, microphone and notifications") {
            systemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        page.addView(design.row("Shared storage access", "Allow access to phone storage, SD cards and USB") { Storage.requestAccess(this) })
        page.addView(design.row("Free up app storage", "Select apps from 40 MiB to reclaim at least 2 GiB total") { reviewLargeApps() })
        if (Build.VERSION.SDK_INT >= 31) page.addView(design.row("Keep services reliable", "One-time Android background process setup") {
            val text = design.text(getString(R.string.phantom_tip), 14f).apply { setTextIsSelectable(true); setPadding(design.dp(24), design.dp(12), design.dp(24), design.dp(12)) }
            AlertDialog.Builder(this).setTitle("Background process setup").setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Done", null).show()
        })
        page.addView(design.label("ABOUT"))
        page.addView(design.row("App updates", "Check for an update or install an APK") { DashboardActivity.open(this, "settings") })
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        page.addView(design.text("Homedroid $version\nYour old phone, now a home server.", 13f, design.muted).apply { setPadding(design.dp(2), design.dp(16), 0, 0) })
        renderedSettings = signature()
    }

    override fun onResume() {
        super.onResume()
        if (renderedSettings != signature()) { recreate(); return }
        backgroundStatus.setTextIfChanged(if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            "Battery optimization is disabled for Homedroid." else "Allow background running to keep your server available with the screen off.")
    }
    override fun onPause() { renderedSettings = signature(); super.onPause() }
    private fun systemSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private fun applyServices() { if (ServerService.active) ServerService.restart(this) }
    private fun saved() { Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show() }
    private data class LargeApp(val info: android.content.pm.ApplicationInfo, val label: String, val bytes: Long, val system: Boolean)
    private val uninstallQueue = ArrayDeque<LargeApp>()
    private var uninstallRequestedBytes = 0L
    private val cleanupTargetBytes = 2L * 1024L * 1024L * 1024L
    private val cleanupMinAppBytes = 40L * 1024L * 1024L

    private fun reviewLargeApps() {
        val ops = getSystemService(AppOpsManager::class.java)
        if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) != AppOpsManager.MODE_ALLOWED) {
            AlertDialog.Builder(this).setTitle("Allow storage size access")
                .setMessage("Android requires Usage access to show how much storage each app uses. Homedroid only reads app storage sizes to find large apps.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open access settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                }.show()
            return
        }
        val loading = AlertDialog.Builder(this).setTitle("Scanning app storage…").setMessage("This may take a moment.")
            .setNegativeButton(android.R.string.cancel, null).create()
        loading.show()
        Thread({
            val result = mutableListOf<LargeApp>()
            val manager = getSystemService(StorageStatsManager::class.java)
            val packages = packageManager.getInstalledApplications(0)
            for (app in packages) {
                if (app.packageName == packageName || packageManager.getLaunchIntentForPackage(app.packageName) == null) continue
                try {
                    val size = manager.queryStatsForPackage(StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle())
                    val total = (size.appBytes + size.dataBytes + size.cacheBytes).coerceAtLeast(0L)
                    if (total >= cleanupMinAppBytes) {
                        val system = app.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        result += LargeApp(app, packageManager.getApplicationLabel(app).toString(), total, system)
                    }
                } catch (_: Exception) { /* Some packages do not expose stats to this app. */ }
            }
            result.sortByDescending { it.bytes }
            runOnUiThread {
                loading.dismiss()
                showLargeApps(result)
            }
        }, "large-app-scan").apply { isDaemon = true; start() }
    }

    private fun showLargeApps(apps: List<LargeApp>) {
        if (apps.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No apps over 40 MiB")
                .setMessage("No launchable apps at or above 40 MiB were found. Some system apps may not report their sizes.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val removable = apps.filterNot { it.system }
        val system = apps.filter { it.system }
        if (removable.isEmpty()) return showSystemApps(system)
        val checked = BooleanArray(removable.size)
        fun selectedBytes() = removable.indices.filter { checked[it] }.sumOf { removable[it].bytes }
        val labels = removable.map { "${it.label} · ${formatBytes(it.bytes)}" }.toTypedArray()
        var refreshSelection: (() -> Unit)? = null
        val dialog = AlertDialog.Builder(this).setTitle("Select apps to remove")
            .setMessage("Choose user-installed apps. The total must reach 2 GiB; Android will ask before uninstalling each app. System apps are excluded from this total.")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                checked[index] = isChecked
                refreshSelection?.invoke()
            }
            .setPositiveButton("Continue", null)
            .setNeutralButton(if (system.isNotEmpty()) "System apps" else "Scan again", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val continueButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val otherButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            fun updateSelection() {
                val total = selectedBytes()
                dialog.setTitle("Select apps · ${formatBytes(total)} / 2 GiB")
                continueButton.isEnabled = total >= cleanupTargetBytes
            }
            refreshSelection = ::updateSelection
            updateSelection()
            otherButton?.setOnClickListener {
                dialog.dismiss()
                if (system.isNotEmpty()) showSystemApps(system) else reviewLargeApps()
            }
            continueButton.setOnClickListener {
                val selected = removable.indices.filter { checked[it] }.map { removable[it] }.sortedByDescending { it.bytes }
                uninstallQueue.clear()
                uninstallRequestedBytes = 0L
                for (app in selected) {
                    uninstallQueue.addLast(app)
                    uninstallRequestedBytes += app.bytes
                    if (uninstallRequestedBytes >= cleanupTargetBytes) break
                }
                dialog.dismiss()
                requestNextUninstall()
            }
        }
        dialog.show()
    }

    private fun showSystemApps(apps: List<LargeApp>) {
        if (apps.isEmpty()) { reviewLargeApps(); return }
        val labels = apps.map { "${it.label} · ${formatBytes(it.bytes)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Preinstalled system apps")
            .setMessage("These do not count toward the 2 GiB reclaim target. Android may offer disable or uninstall-updates options, but the system APK cannot be removed here.")
            .setItems(labels) { _, index ->
                val app = apps[index]
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.info.packageName}"))) }
                catch (_: Exception) { Toast.makeText(this, "Android could not open app management", Toast.LENGTH_SHORT).show() }
            }
            .setPositiveButton("Scan again") { _, _ -> reviewLargeApps() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun requestNextUninstall() {
        val app = uninstallQueue.removeFirstOrNull()
        if (app == null) {
            Toast.makeText(this, "Finished app cleanup requests", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val request = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${app.info.packageName}"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            startActivityForResult(request, REQUEST_UNINSTALL)
        }
        catch (_: Exception) {
            Toast.makeText(this, "Android could not uninstall ${app.label}", Toast.LENGTH_SHORT).show()
            uninstallQueue.clear()
        }
    }

    @Deprecated("Android returns to this activity after each uninstall confirmation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_UNINSTALL) return
        if (resultCode == RESULT_OK) requestNextUninstall()
        else {
            uninstallQueue.clear()
            Toast.makeText(this, "Remaining app removals cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatBytes(value: Long): String {
        val mib = value / (1024.0 * 1024.0)
        return if (mib >= 1024) String.format(java.util.Locale.getDefault(), "%.1f GiB", mib / 1024.0)
        else String.format(java.util.Locale.getDefault(), "%.0f MiB", mib)
    }

    companion object { private const val REQUEST_UNINSTALL = 7301 }
    private fun editor(title: String, build: (LinearLayout) -> (() -> Boolean)) {
        val content = design.column().apply { setPadding(design.dp(24), design.dp(4), design.dp(24), design.dp(20)) }
        val save = build(content)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { if (save()) { saved(); dialog.dismiss() } }
                catch (e: Exception) { Toast.makeText(this, e.message ?: "Could not save settings", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
    private fun editTunnel() = editor("Cloudflare Tunnel") { box ->
        box.addView(design.text("Paste your tunnel token to connect. Clear it to disconnect. Active services restart after saving.", 14f, design.muted))
        val token = design.field(box, "Tunnel token", cfg.tunnelToken, secret = true)
        val save: () -> Boolean = { cfg.tunnelToken = token.text.toString().trim(); applyServices(); true }
        save
    }
    private fun editWebDav() {
        val storage = WebDavStorage(this)
        val savedConfig = storage.publicConfig()
        val content = design.column().apply { setPadding(design.dp(24), design.dp(4), design.dp(24), design.dp(20)) }
        content.addView(design.text("Configure a private HTTPS WebDAV endpoint for this phone. Use a separate endpoint and account on each phone. Credentials are encrypted with Android Keystore and are not sent through the dashboard browser.", 14f, design.muted))
        val url = design.field(content, "HTTPS WebDAV URL", storage.endpointUrl()).apply {
            hint = "https://bridge.example/webdav/"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val username = design.field(content, "Username", "")
        val password = design.field(content, "Password or app token", "", secret = true)
        val freeGb = design.field(content, "Approximate free space (GiB)", (savedConfig.optLong("manualFreeBytes") / (1024L * 1024L * 1024L)).takeIf { it > 0 }?.toString().orEmpty()).apply {
            hint = "Leave at 0 if the server reports its quota"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        content.addView(design.text("Used for automatic placement only if the WebDAV server does not report quota. Homedroid subtracts uploads made through this panel; update the estimate if you add files elsewhere.", 12f, design.muted))
        val dialog = AlertDialog.Builder(this).setTitle("WebDAV storage").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null).setNegativeButton(android.R.string.cancel, null)
            .apply { if (savedConfig.optBoolean("configured")) setNeutralButton("Remove account", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val gb = freeGb.text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val capacity = (gb.coerceAtMost(Long.MAX_VALUE / (1024L * 1024L * 1024L))) * (1024L * 1024L * 1024L)
                    val error = storage.save(url.text.toString(), username.text.toString(), password.text.toString(), capacity)
                    if (error != null) url.error = error else { saved(); dialog.dismiss(); recreate() }
                } catch (e: Exception) { Toast.makeText(this, e.message ?: "Could not save WebDAV settings", Toast.LENGTH_LONG).show() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Remove WebDAV account?")
                    .setMessage("The saved URL and credentials will be removed from this phone.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Remove") { _, _ -> storage.clear(); saved(); dialog.dismiss(); recreate() }.show()
            }
        }
        dialog.show()
    }
    private fun editTailscale() = editor("Tailscale setup") { box ->
        box.addView(design.text("Without an authentication key, use the login link in Control panel → Modules → Tailscale. Changes restart active services.", 14f, design.muted))
        val host = design.field(box, "Device name", cfg.tailscaleHostname)
        val key = design.field(box, "Authentication key (optional)", cfg.tailscaleAuthKey, secret = true)
        val save: () -> Boolean = {
            val value = host.text.toString().trim().lowercase()
            if (!PhoneSettings.validHostname(value)) { host.error = "Use 1–63 letters, numbers or hyphens; start and end with a letter or number"; false }
            else { cfg.tailscaleHostname = value; cfg.tailscaleAuthKey = key.text.toString().trim(); applyServices(); true }
        }
        save
    }
    private fun editPassword() = editor("Dashboard password") { box ->
        box.addView(design.text("Use 12–128 characters. Saving signs out existing browser sessions.", 14f, design.muted))
        val password = design.field(box, "New password", "", secret = true)
        val confirm = design.field(box, "Confirm password", "", secret = true)
        val save: () -> Boolean = {
            val value = password.text.toString()
            when {
                !PhoneSettings.validPassword(value) -> { password.error = "Use 12–128 characters without control characters"; false }
                value != confirm.text.toString() -> { confirm.error = "Passwords do not match"; false }
                else -> { Dashboard.changePasswordFromPhone(this, value); true }
            }
        }
        save
    }
    private fun editKeys() = editor("SSH public keys") { box ->
        box.addView(design.text("One authorized_keys entry per line. Paste public keys only. Changes apply to new connections immediately.", 14f, design.muted))
        val paths = Paths(this)
        val keys = design.field(box, "Authorized keys", paths.authorizedKeys.takeIf { it.exists() }?.readText().orEmpty(), multiline = true)
        keys.typeface = android.graphics.Typeface.MONOSPACE; keys.textSize = 12f
        val save: () -> Boolean = {
            val value = keys.text.toString().trim()
            require(!value.contains("PRIVATE KEY")) { "Paste a public key, not a private key" }
            paths.authorizedKeys.parentFile!!.mkdirs()
            val tmp = File(paths.authorizedKeys.path + ".phone.tmp")
            tmp.writeText(if (value.isEmpty()) "" else "$value\n")
            check(tmp.renameTo(paths.authorizedKeys)) { "Could not save SSH keys" }
            true
        }
        save
    }
}
