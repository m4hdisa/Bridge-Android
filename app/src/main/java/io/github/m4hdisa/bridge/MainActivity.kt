package io.github.m4hdisa.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var link: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var perApp: CheckBox
    private lateinit var hint: TextView
    private lateinit var list: ListView
    private var apps: List<AppEntry> = emptyList()
    private val adapter = AppAdapter()

    private data class AppEntry(val label: String, val packageName: String)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn() else showError(getString(R.string.vpn_permission_denied))
    }

    // The app works without it; only the "connected" notification stays hidden.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        link = findViewById(R.id.link)
        status = findViewById(R.id.status)
        connect = findViewById(R.id.connect)
        perApp = findViewById(R.id.per_app)
        hint = findViewById(R.id.apps_hint)
        list = findViewById(R.id.apps)

        link.setText(prefs.link)
        perApp.isChecked = prefs.perApp
        perApp.setOnCheckedChangeListener { _, checked ->
            prefs.perApp = checked
            updateAppList()
            render()
        }
        list.adapter = adapter
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.setOnItemClickListener { _, _, position, _ ->
            val name = apps[position].packageName
            prefs.apps = if (name in prefs.apps) prefs.apps - name else prefs.apps + name
            adapter.notifyDataSetChanged()
            render()
        }
        connect.setOnClickListener { onConnectClicked() }
        updateAppList()
        loadApps()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        VpnState.listener = { render() }
        render()
    }

    override fun onStop() {
        VpnState.listener = null
        super.onStop()
    }

    private fun onConnectClicked() {
        if (VpnState.status != VpnState.Status.Stopped) {
            startService(Intent(this, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_STOP))
            return
        }
        val text = link.text.toString().trim()
        try {
            VlessLink.parse(text)
        } catch (e: LinkException) {
            showError(e.message ?: getString(R.string.bad_link))
            return
        }
        prefs.link = text
        if (prefs.perApp && prefs.apps.isEmpty()) {
            showError(getString(R.string.choose_apps))
            return
        }
        val request = VpnService.prepare(this)
        if (request != null) vpnPermission.launch(request) else startVpn()
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, BridgeVpnService::class.java))
    }

    private fun showError(message: String) {
        status.text = message
    }

    private fun render() {
        val state = VpnState.status
        status.text = VpnState.error?.let { getString(R.string.error_prefix, it) } ?: getString(
            when (state) {
                VpnState.Status.Stopped -> R.string.status_off
                VpnState.Status.Starting -> R.string.status_starting
                VpnState.Status.Running -> R.string.status_on
                VpnState.Status.Stopping -> R.string.status_stopping
            },
        )
        connect.setText(if (state == VpnState.Status.Stopped) R.string.connect else R.string.disconnect)
        connect.isEnabled = state != VpnState.Status.Stopping
        val editable = state == VpnState.Status.Stopped
        link.isEnabled = editable
        perApp.isEnabled = editable
        list.isEnabled = editable
        list.alpha = if (editable) 1f else 0.5f
        hint.text = getString(R.string.apps_chosen, prefs.apps.size)
    }

    private fun updateAppList() {
        val visibility = if (prefs.perApp) View.VISIBLE else View.GONE
        list.visibility = visibility
        hint.visibility = visibility
    }

    private fun loadApps() {
        Thread {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = packageManager.queryIntentActivities(launcher, 0)
                .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = found
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size

        override fun getItem(position: Int) = apps[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)) as CheckedTextView
            val app = apps[position]
            row.text = app.label
            list.setItemChecked(position, app.packageName in prefs.apps)
            return row
        }
    }
}
