package com.rfsat.vtb.profiles

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.rfsat.vtb.databinding.ActivityProfileBinding
import com.rfsat.vtb.ui.BaseActivity

class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var repo: ProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)
        setupBottomNav(com.rfsat.vtb.R.id.nav_profiles)

        binding.spinnerClickUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1/4 MOA per click", "1/8 MOA per click", "0.1 MRAD per click")
        )
        // v20.22: the preset pull-down now feeds from the FULL scope
        // catalogue (all brands incl. ATN) + "Custom" — one list, one truth.
        // The legacy 4-entry PRESETS list confused users who couldn't find
        // their scopes here while the catalogue dialog had them.
        val scopePresetNames = ScopeCatalog.entries.map { "${it.brand} ${it.model}" } + "Custom (edit fields)"
        binding.spinnerScopePreset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, scopePresetNames
        )
        binding.spinnerScopePreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // v1.20.28: position memory (see set spinner) — adapter
                // (re)assignment fires one callback per Settings open. Also
                // fixes a v1.20.26 defect: the name-comparison guard compared
                // against an accidentally-literal template string (a patch
                // escaping artifact), so it never matched.
                if (position == lastPresetSpinnerPos) return
                lastPresetSpinnerPos = position
                val entry = ScopeCatalog.entries.getOrNull(position) ?: return // Custom -> leave fields
                if (binding.etScopeName.text.toString() == entry.brand + " " + entry.model) return
                applyImportedScope(entry.toScopeProfile())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // v20.13: field-unit bindings MUST be created before loadIntoFields(),
        // which assigns through them (uBarrel.set(...) etc.). The v20.9
        // ordering had setup running later in onCreate -> lateinit crash.
        setupFieldUnits()
        loadIntoFields()

        binding.btnSave.setOnClickListener {
            saveFromFields()
            finish()
        }
        binding.btnResetDefaults.setOnClickListener {
            repo.resetToDefaults()
            loadIntoFields()
        }
        binding.btnCalibrateDrag.setOnClickListener { calibrateDragFromOfficialDrop() }
        binding.tvTableZeroLabel.text = "Table zero (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"
        binding.tvTableRangeLabel.text = "Range (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"
        binding.tvTableDropLabel.text = "Drop (${com.rfsat.vtb.ui.UnitsManager.offsetUnitLabel()})"
        refreshDropReadouts()

        // Underline section titles for visibility (no XML attribute for
        // underline; paint flags are the standard way).
        setupDisplaySpinners()
        binding.btnAmmoCatalog.setOnClickListener { showAmmoCatalog() }
        binding.btnScopeCatalog.setOnClickListener { showScopeCatalog() }
        binding.btnRifleCatalog.setOnClickListener { showRifleCatalog() }
        binding.btnBackupExport.setOnClickListener {
            backupCreate.launch("VTB_backup_${com.rfsat.vtb.BuildConfig.VERSION_NAME}.json")
        }
        binding.btnBackupRestore.setOnClickListener { backupOpen.launch(arrayOf("application/json", "text/plain", "*/*")) }
        binding.cbTracer.setOnCheckedChangeListener { _, on -> if (on) binding.cbPellet.isChecked = false }
        binding.cbPellet.setOnCheckedChangeListener { _, on -> if (on) binding.cbTracer.isChecked = false }
        binding.btnChronograph.setOnClickListener { showChronograph() }
        binding.btnBulletCsvExport.setOnClickListener { csvKind = CsvKind.BULLET; csvCreate.launch("vtb_bullets.csv") }
        binding.btnBulletCsvImport.setOnClickListener { csvKind = CsvKind.BULLET; csvOpen.launch(arrayOf("*/*")) }
        binding.btnRifleCsvExport.setOnClickListener { csvKind = CsvKind.RIFLE; csvCreate.launch("vtb_rifles.csv") }
        binding.btnRifleCsvImport.setOnClickListener { csvKind = CsvKind.RIFLE; csvOpen.launch(arrayOf("*/*")) }
        binding.btnScopeCsvExport.setOnClickListener { csvKind = CsvKind.SCOPE; csvCreate.launch("vtb_scopes.csv") }
        binding.btnScopeCsvImport.setOnClickListener { csvKind = CsvKind.SCOPE; csvOpen.launch(arrayOf("*/*")) }

        listOf(binding.tvHeaderDisplay, binding.tvHeaderRifle, binding.tvHeaderBullet, binding.tvHeaderScope,
               binding.tvHeaderDropCal, binding.tvHeaderWindCal, binding.tvHeaderSets,
               binding.tvHeaderBackup).forEach {
            it.paintFlags = it.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        }

        binding.tvTrueWindLabel.text = "Measured wind (${com.rfsat.vtb.ui.UnitsManager.speedUnitLabel()})"
        binding.btnSolveWindScale.setOnClickListener { solveWindScale() }
        refreshWindScale()

        binding.btnSaveSet.setOnClickListener { promptSaveSet() }
        binding.btnLoadSet.setOnClickListener { loadSelectedSet() }
        binding.btnDeleteSet.setOnClickListener { deleteSelectedSet() }
        repo.seedDefaultSetsIfEmpty() // v20.22: user's rigs as ready-made sets
        repo.migrateSeededBulletBrand() // v1.20.24: AEA-branded pellet -> EDgun
        repo.migrateLtvStreamFlag() // v1.20.25: LTV has no Wi-Fi -> clear stream flag
        repo.migrateDefaultSetBullet() // v1.20.26: default set bullet CCI -> Federal GMT
        refreshSetSpinner()
    }

    // ---- Per-field unit selection (v20.9) ----

    private lateinit var uBarrel: FieldUnits.Binding
    private lateinit var uZero: FieldUnits.Binding
    private lateinit var uCaliber: FieldUnits.Binding
    private lateinit var uWeight: FieldUnits.Binding
    private lateinit var uMv: FieldUnits.Binding

    private fun setupFieldUnits() {
        uBarrel = FieldUnits.Binding(this, FieldUnits.Kind.LENGTH_IN, binding.etBarrelLength, binding.spUBarrel)
        uZero = FieldUnits.Binding(this, FieldUnits.Kind.DISTANCE, binding.etZeroDistance, binding.spUZero)
        uCaliber = FieldUnits.Binding(this, FieldUnits.Kind.CALIBER, binding.etCaliber, binding.spUCaliber)
        uWeight = FieldUnits.Binding(this, FieldUnits.Kind.WEIGHT, binding.etWeightGrains, binding.spUWeight)
        uMv = FieldUnits.Binding(this, FieldUnits.Kind.VELOCITY, binding.etMuzzleVelocity, binding.spUMv)
    }

    // ---- Factory ammunition catalogue (v20.6) ----

    /** v20.7 pending-base pattern: a catalogue pick, chronograph entry or
     *  CSV import stages a BASE profile consumed by the next Save. The base
     *  carries the fields no text box edits (drag calibration factor,
     *  boresight offsets, turret travels) — visible fields still come from
     *  the boxes, so the user reviews before anything persists, and
     *  cancelling (never saving) leaves the stored profile untouched. */
    private var pendingBulletBase: BulletProfile? = null
    private var pendingRifleBase: RifleProfile? = null
    private var pendingScopeBase: ScopeProfile? = null

    private enum class CsvKind { BULLET, RIFLE, SCOPE }
    private var csvKind = CsvKind.BULLET

    private val csvCreate = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) writeCsv(uri) }

    private val csvOpen = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readCsv(uri) }

    // v1.20.30: whole-app backup/restore (see backup/AppBackup.kt).
    private val backupCreate = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackup(uri) }

    private val backupOpen = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readBackup(uri) }

    private fun writeBackup(uri: android.net.Uri) {
        try {
            val json = com.rfsat.vtb.backup.AppBackup.export(this)
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw java.io.IOException("no stream")
            notifyUser("Backup saved. Keep it somewhere off the phone \u2014 it holds every profile, set and calibration.")
        } catch (t: Throwable) {
            com.rfsat.vtb.log.Logger.w("ProfileActivity", "Backup export failed: ${t.message}")
            notifyUser("Backup failed: ${t.message}")
        }
    }

    private fun readBackup(uri: android.net.Uri) {
        val json = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.IOException("no stream")
        } catch (t: Throwable) {
            notifyUser("Could not read that file: ${t.message}"); return
        }
        val summary = com.rfsat.vtb.backup.AppBackup.inspect(json).getOrElse { t ->
            notifyUser("Not a usable backup: ${t.message}"); return
        }
        val when_ = java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(summary.createdMs))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Restore backup?")
            .setMessage(
                "From VTB ${summary.versionName}, saved ${when_}.\n" +
                "${summary.keys} stored values across ${summary.stores} sections.\n\n" +
                "This REPLACES everything currently stored \u2014 profiles, profile sets, " +
                "calibrations, units and preferences. VTB reloads itself afterwards."
            )
            .setPositiveButton("Restore") { _, _ ->
                com.rfsat.vtb.backup.AppBackup.restore(this, json)
                    .onSuccess { n ->
                        // v1.20.33: no shutdown. Reload the singletons that
                        // cache preference state, then rebuild the activity
                        // stack from Home — CLEAR_TASK drops any screen still
                        // holding pre-restore values, and every activity reads
                        // its data afresh in onCreate.
                        com.rfsat.vtb.backup.AppBackup.reloadInMemoryState(this)
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Restored")
                            .setMessage("$n values restored.")
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ ->
                                startActivity(
                                    android.content.Intent(this, com.rfsat.vtb.ui.MainActivity::class.java)
                                        .addFlags(
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        )
                                )
                                finish()
                            }
                            .show()
                    }
                    .onFailure { t ->
                        com.rfsat.vtb.log.Logger.w("ProfileActivity", "Restore failed: ${t.message}")
                        notifyUser("Restore failed: ${t.message}")
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAmmoCatalog() {
        val v = layoutInflater.inflate(com.rfsat.vtb.R.layout.dialog_ammo_catalog, null)
        val spMfr = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatMfr)
        val spCal = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatCal)
        val spVel = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatVel)
        val spWeight = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatWeight)
        val spType = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatType)
        val tvCount = v.findViewById<android.widget.TextView>(com.rfsat.vtb.R.id.tvCatCount)
        val lv = v.findViewById<android.widget.ListView>(com.rfsat.vtb.R.id.lvCatResults)

        fun spinner(sp: android.widget.Spinner, items: List<String>) {
            val a = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sp.adapter = a
        }
        spinner(spMfr, AmmoCatalog.manufacturers())
        spinner(spCal, AmmoCatalog.calibers())
        spinner(spVel, AmmoCatalog.velocityClasses())
        spinner(spWeight, AmmoCatalog.weights())
        spinner(spType, AmmoCatalog.types())

        var current: List<AmmoCatalog.Entry> = emptyList()
        fun refresh() {
            current = AmmoCatalog.filter(
                spMfr.selectedItem as String, spCal.selectedItem as String,
                spVel.selectedItem as String, spWeight.selectedItem as String,
                spType.selectedItem as String
            )
            tvCount.text = "${current.size} of ${AmmoCatalog.entries.size} cartridges"
            lv.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_1, current.map { it.label() })
        }
        val onSel = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, w: android.view.View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        listOf(spMfr, spCal, spVel, spWeight, spType).forEach { it.onItemSelectedListener = onSel }
        refresh()

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ammunition catalogue")
            .setView(v)
            .setNegativeButton("Cancel", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val b = current.getOrNull(pos) ?: return@setOnItemClickListener
            applyCatalogEntry(b.toBulletProfile())
            dlg.dismiss()
        }
        dlg.show()
    }

    private fun showRifleCatalog() {
        val v = layoutInflater.inflate(com.rfsat.vtb.R.layout.dialog_rifle_catalog, null)
        val spBrand = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spRifBrand)
        val spType = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spRifType)
        val tvCount = v.findViewById<android.widget.TextView>(com.rfsat.vtb.R.id.tvRifCount)
        val lv = v.findViewById<android.widget.ListView>(com.rfsat.vtb.R.id.lvRifResults)
        fun spinner(sp: android.widget.Spinner, items: List<String>) {
            val a = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sp.adapter = a
        }
        spinner(spBrand, RifleCatalog.brands()); spinner(spType, RifleCatalog.types())
        // v1.20.26: default the Type filter from the airgun checkbox — with
        // "Airgun pellet/slug" unticked the list opens on rimfire rifles,
        // ticked it opens on air rifles. (The checkbox marks the BULLET as a
        // tracked projectile; catalogues list everything — this just picks
        // the relevant starting filter. Switch to "All" to see every rifle.)
        val defaultType = if (binding.cbPellet.isChecked) "Air (PCP)" else "Rimfire"
        RifleCatalog.types().indexOf(defaultType).takeIf { it >= 0 }?.let { spType.setSelection(it) }
        var current: List<RifleCatalog.Entry> = emptyList()
        fun refresh() {
            current = RifleCatalog.filter(spBrand.selectedItem as String, spType.selectedItem as String)
            tvCount.text = "${current.size} of ${RifleCatalog.entries.size} rifles"
            lv.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, current.map { it.label() })
        }
        val onSel = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, w: android.view.View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        listOf(spBrand, spType).forEach { it.onItemSelectedListener = onSel }
        refresh()
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rifle catalogue").setView(v).setNegativeButton("Cancel", null).create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            current.getOrNull(pos)?.let { applyImportedRifle(it.toRifleProfile()) }
            dlg.dismiss()
        }
        dlg.show()
    }

    private fun showScopeCatalog() {
        val v = layoutInflater.inflate(com.rfsat.vtb.R.layout.dialog_scope_catalog, null)
        val spBrand = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spScBrand)
        val spClick = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spScClick)
        val spMag = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spScMag)
        val spFamily = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spScFamily)
        val tvCount = v.findViewById<android.widget.TextView>(com.rfsat.vtb.R.id.tvScCount)
        val lv = v.findViewById<android.widget.ListView>(com.rfsat.vtb.R.id.lvScResults)

        fun spinner(sp: android.widget.Spinner, items: List<String>) {
            val a = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sp.adapter = a
        }
        spinner(spBrand, ScopeCatalog.brands())
        spinner(spClick, ScopeCatalog.clickUnits())
        spinner(spMag, ScopeCatalog.magClasses())
        spinner(spFamily, ScopeCatalog.families())

        var current: List<ScopeCatalog.Entry> = emptyList()
        fun refresh() {
            current = ScopeCatalog.filter(
                spBrand.selectedItem as String, spClick.selectedItem as String,
                spMag.selectedItem as String, spFamily.selectedItem as String)
            tvCount.text = "${current.size} of ${ScopeCatalog.entries.size} scopes"
            lv.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_1, current.map { it.label() })
        }
        val onSel = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, w: android.view.View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        listOf(spBrand, spClick, spMag, spFamily).forEach { it.onItemSelectedListener = onSel }
        refresh()

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Scope catalogue")
            .setView(v)
            .setNegativeButton("Cancel", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            current.getOrNull(pos)?.let { applyImportedScope(it.toScopeProfile()) }
            dlg.dismiss()
        }
        dlg.show()
    }

    /** Fill the bullet FIELDS only — review, tweak and Save via the normal
     *  flow, so custom profiles keep working exactly as before. */
    private fun applyCatalogEntry(b: BulletProfile) {
        with(binding) {
            etBulletName.setText(b.name)
            uCaliber.set(b.caliberDiameterIn)
            uWeight.set(b.weightGrains)
            uMv.set(b.muzzleVelocityFps)
            etBallisticCoefficient.setText(b.ballisticCoefficientG1.toString())
            etMvTempCoeff.setText("0.0")
            etMvRefTemp.setText("15.0")
            cbTracer.isChecked = false
            cbPellet.isChecked = b.isPellet
        }
        pendingBulletBase = repo.getBullet().copy(dragCalibrationFactor = 1.0)
        notifyUser("Catalogue values loaded — review and Save (drag factor resets to 1.0; re-run drop calibration for the new load).")
    }

    // ---- Chronograph (v20.7): your measured MV over the published one ----

    private fun showChronograph() {
        val v = layoutInflater.inflate(com.rfsat.vtb.R.layout.dialog_chronograph, null)
        // v20.9: each field carries its own unit spinner (fps/m·s and degC/degF).
        val uMv1 = FieldUnits.Binding(this, FieldUnits.Kind.VELOCITY, v.findViewById(com.rfsat.vtb.R.id.etChronoMv1), v.findViewById(com.rfsat.vtb.R.id.spUChronoMv1))
        val uT1 = FieldUnits.Binding(this, FieldUnits.Kind.TEMPERATURE, v.findViewById(com.rfsat.vtb.R.id.etChronoT1), v.findViewById(com.rfsat.vtb.R.id.spUChronoT1))
        val uMv2 = FieldUnits.Binding(this, FieldUnits.Kind.VELOCITY, v.findViewById(com.rfsat.vtb.R.id.etChronoMv2), v.findViewById(com.rfsat.vtb.R.id.spUChronoMv2))
        val uT2 = FieldUnits.Binding(this, FieldUnits.Kind.TEMPERATURE, v.findViewById(com.rfsat.vtb.R.id.etChronoT2), v.findViewById(com.rfsat.vtb.R.id.spUChronoT2))
        uMv.get()?.let { uMv1.set(it) }
        uT1.set(com.rfsat.vtb.environment.EnvironmentManager.current.atmosphere.temperatureC)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chronograph measurement")
            .setView(v)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                // Bindings return canonical units: MV in fps, temperature in degC.
                val m1 = uMv1.get()
                if (m1 == null || m1 <= 0) { notifyUser("Enter the measured muzzle velocity."); return@setPositiveButton }
                val temp1 = uT1.get() ?: 15.0
                uMv.set(m1)
                binding.etMvRefTemp.setText(String.format("%.1f", temp1))
                val m2 = uMv2.get()
                val temp2 = uT2.get()
                var msg = "Chronographed MV applied — review and Save; re-run drop calibration."
                if (m2 != null && temp2 != null) {
                    if (kotlin.math.abs(temp1 - temp2) >= 3.0) {
                        val coeff = (m1 - m2) * 0.3048 / (temp1 - temp2)
                        binding.etMvTempCoeff.setText(String.format("%.3f", coeff))
                        msg = "MV + temperature coefficient (%.3f m/s/°C) applied — review and Save; re-run drop calibration.".format(coeff)
                    } else {
                        msg = "MV applied; second point ignored (temperatures under 3°C apart cannot resolve a coefficient)."
                    }
                }
                // A new measured MV invalidates the old load calibration.
                pendingBulletBase = repo.getBullet().copy(dragCalibrationFactor = 1.0)
                notifyUser(msg)
            }
            .show()
    }

    // ---- Profile CSV export / import (v20.7) ----

    /** Current profile first, then each saved set's (deduped by name). */
    private fun writeCsv(uri: android.net.Uri) {
        try {
            val sets = repo.getSets()
            val text = when (csvKind) {
                CsvKind.BULLET -> CsvProfiles.bulletsToCsv(
                    (listOf(repo.getBullet()) + sets.map { it.bullet }).distinctBy { it.name })
                CsvKind.RIFLE -> CsvProfiles.riflesToCsv(
                    (listOf(repo.getRifle()) + sets.map { it.rifle }).distinctBy { it.name })
                CsvKind.SCOPE -> CsvProfiles.scopesToCsv(
                    (listOf(repo.getScope()) + sets.map { it.scope }).distinctBy { it.name })
            }
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw java.io.IOException("no stream")
            notifyUser("Exported ${text.lineSequence().count { it.isNotBlank() } - 1} profile(s).")
        } catch (t: Throwable) {
            com.rfsat.vtb.log.Logger.w("ProfileActivity", "CSV export failed: ${t.message}")
            notifyUser("Export failed: ${t.message}")
        }
    }

    private fun readCsv(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.IOException("no stream")
            when (csvKind) {
                CsvKind.BULLET -> chooseAndApply(CsvProfiles.bulletsFromCsv(text), { it.name }) { applyImportedBullet(it) }
                CsvKind.RIFLE -> chooseAndApply(CsvProfiles.riflesFromCsv(text), { it.name }) { applyImportedRifle(it) }
                CsvKind.SCOPE -> chooseAndApply(CsvProfiles.scopesFromCsv(text), { it.name }) { applyImportedScope(it) }
            }
        } catch (t: Throwable) {
            com.rfsat.vtb.log.Logger.w("ProfileActivity", "CSV import failed: ${t.message}")
            notifyUser("Import failed: ${t.message}")
        }
    }

    private fun <T> chooseAndApply(list: List<T>, label: (T) -> String, apply: (T) -> Unit) {
        when {
            list.isEmpty() -> notifyUser("No profiles found in that file.")
            list.size == 1 -> apply(list[0])
            else -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Choose a profile")
                .setItems(list.map(label).toTypedArray()) { _, which -> apply(list[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun applyImportedBullet(b: BulletProfile) {
        with(binding) {
            etBulletName.setText(b.name)
            uCaliber.set(b.caliberDiameterIn)
            uWeight.set(b.weightGrains)
            uMv.set(b.muzzleVelocityFps)
            etBallisticCoefficient.setText(b.ballisticCoefficientG1.toString())
            etMvTempCoeff.setText(b.mvTempCoeffMpsPerC.toString())
            etMvRefTemp.setText(b.mvRefTempC.toString())
            cbTracer.isChecked = b.isTracer
            cbPellet.isChecked = b.isPellet
        }
        pendingBulletBase = b // carries the imported drag calibration factor
        notifyUser("Bullet “${b.name}” loaded (drag factor %.3f) — review and Save.".format(b.dragCalibrationFactor))
    }

    private fun applyImportedRifle(r: RifleProfile) {
        with(binding) {
            etRifleName.setText(r.name)
            uBarrel.set(r.barrelLengthIn)
            etTwistRate.setText(r.twistRateInPerTurn.toString())
            uZero.set(r.zeroDistanceM)
        }
        pendingRifleBase = r // carries sight height + boresight offsets
        notifyUser("Rifle “${r.name}” loaded — review and Save.")
    }

    private fun applyImportedScope(sc: ScopeProfile) {
        with(binding) {
            etScopeName.setText(sc.name)
            etZoomMin.setText(sc.zoomMin.toString())
            etZoomMax.setText(sc.zoomMax.toString())
            etObjectiveDiameter.setText(sc.objectiveDiameterMm.toString())
            etFocalLength.setText(sc.focalLengthMm.toString())
            etHeightAboveBarrel.setText(sc.heightAboveBarrelIn.toString())
            spinnerClickUnit.setSelection(when (sc.clickUnit) {
                ClickUnit.MOA_QUARTER -> 0; ClickUnit.MOA_EIGHTH -> 1; ClickUnit.MRAD_TENTH -> 2
            })
        }
        pendingScopeBase = sc // carries the turret-travel figures
        notifyUser("Scope “${sc.name}” loaded — review and Save.")
    }

    // ---- Display & units (v20.0, moved from Home) ----

    private fun setupDisplaySpinners() {
        // v20.14: Log-tab visibility toggle. Re-inflating the nav (recreate)
        // is the simplest way to apply it everywhere consistently.
        binding.swLogTab.isChecked = logTabEnabled()
        binding.swLogTab.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("vtb_prefs", MODE_PRIVATE).edit().putBoolean("log_tab_visible", checked).apply()
            recreate()
        }
        // v1.20.32: Play suggests offering an explicit full-screen control
        // rather than forcing immersive mode on everyone.
        binding.swFullScreen.isChecked = fullScreenEnabled()
        binding.swFullScreen.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("vtb_prefs", MODE_PRIVATE).edit().putBoolean("full_screen", checked).apply()
            recreate() // re-applies window flags on this screen; others do it in onCreate
        }

        val um = com.rfsat.vtb.ui.UnitsManager
        val tm = com.rfsat.vtb.ui.ThemeManager

        binding.spinnerTheme.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            com.rfsat.vtb.ui.ThemeMode.values().map { it.label }
        )
        binding.spinnerTheme.setSelection(com.rfsat.vtb.ui.ThemeMode.values().indexOf(tm.mode()))
        binding.spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = com.rfsat.vtb.ui.ThemeMode.values()[position]
                if (selected != tm.mode()) {
                    tm.setMode(this@ProfileActivity, selected)
                    recreate() // re-inflate with the new theme
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.spinnerUnits.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            com.rfsat.vtb.ui.UnitSystem.values().map { it.label }
        )
        binding.spinnerUnits.setSelection(com.rfsat.vtb.ui.UnitSystem.values().indexOf(um.system()))
        binding.spinnerUnits.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = com.rfsat.vtb.ui.UnitSystem.values()[position]
                if (selected != um.system()) {
                    um.setSystem(this@ProfileActivity, selected)
                    recreate() // re-render every field/label in the new units
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // ---- Wind calibration (v19.0) ----

    private fun refreshWindScale() {
        val scale = getSharedPreferences("vtb_wind_cal", MODE_PRIVATE).getFloat("scale", 1.0f)
        binding.tvWindScale.text = "Wind scale factor: %.2f%s".format(
            scale, if (scale == 1.0f) " (uncalibrated)" else "")
    }

    /**
     * Solves the vapor estimator's effective-distance scale from ONE
     * reference: enter the true crosswind (Kestrel-measured) that was
     * blowing during the LAST analysis, and since the estimate is linear
     * in the assumed centroid distance, newScale = oldScale x true/est.
     */
    private fun solveWindScale() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val input = binding.etTrueWind.text.toString().toDoubleOrNull()
        if (input == null || input <= 0.0) {
            notifyUser("Enter the wind speed the Kestrel measured during the last analyzed shot.")
            return
        }
        val trueMps = if (um.isImperial()) input * 0.44704 else input
        com.rfsat.vtb.results.AnalysisSession.restore(this)
        val estMps = kotlin.math.abs(
            com.rfsat.vtb.results.AnalysisSession.adjustment?.estimatedCrosswindMps ?: 0.0
        )
        if (estMps < 0.1) {
            notifyUser("Last analysis has no usable wind estimate to calibrate against.")
            return
        }
        val prefs = getSharedPreferences("vtb_wind_cal", MODE_PRIVATE)
        val old = prefs.getFloat("scale", 1.0f)
        val solved = (old * trueMps / estMps).toFloat().coerceIn(0.2f, 5.0f)
        prefs.edit().putFloat("scale", solved).apply()
        com.rfsat.vtb.log.Logger.i("ProfileActivity",
            "Wind scale calibrated: true=%.2f m/s est=%.2f m/s -> scale %.2f (was %.2f)"
                .format(trueMps, estMps, solved, old))
        refreshWindScale()
        notifyUser("Wind scale set to %.2f — applies to the next analysis.".format(solved))
    }

    // ---- Profile sets (v16.0) ----

    private var lastSetSpinnerPos = -1
    private var lastPresetSpinnerPos = -1

    private fun refreshSetSpinner() {
        val names = repo.getSets().map { it.name }
        binding.spinnerProfileSets.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("(no saved sets)") else names
        )
        // v20.10: point the spinner at the active set so Settings agrees with Home.
        lastSetSpinnerPos = 0 // adapter reset lands on 0 unless restored below
        repo.getActiveSetName()?.let { active ->
            names.indexOf(active).takeIf { it >= 0 }?.let {
                binding.spinnerProfileSets.setSelection(it)
                lastSetSpinnerPos = it
            }
        }
        // v1.20.26: SELECTING a set loads it — decided by STATE COMPARISON,
        // not suppression flags. Spinner.setSelection only fires its callback
        // when the position CHANGES, so a pre-set suppress flag for a no-op
        // selection was never consumed and silently ate the user's NEXT real
        // selection (the "set doesn't load" bug). Comparing against the
        // active set name is idempotent and immune to callback timing.
        binding.spinnerProfileSets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // v1.20.28: POSITION MEMORY — the spinner fires one callback
                // whenever its adapter is (re)assigned, i.e. on every Settings
                // open. Only a position CHANGE from the last settled one is a
                // user action; the settled position is recorded by
                // refreshSetSpinner. (The active-name check alone missed the
                // no-active-set case — a manual edit detaches the set — which
                // toasted "loaded" on every Settings visit.)
                if (position == lastSetSpinnerPos) return
                lastSetSpinnerPos = position
                val set = repo.getSets().getOrNull(position) ?: return
                if (set.name == repo.getActiveSetName()) return // already active
                loadSelectedSet()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun promptSaveSet() {
        val input = android.widget.EditText(this).apply {
            hint = "Set name"
            setText("${binding.etRifleName.text} — ${binding.etBulletName.text}".trim(' ', '—'))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save current profiles as set")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                // Snapshot what's TYPED, including calibration factors.
                saveFromFields()
                repo.saveSet(ProfileSet(name, repo.getRifle(), repo.getBullet(), repo.getScope()))
                refreshSetSpinner()
                notifyUser("Saved set \"$name\".")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectedSet(): ProfileSet? {
        val sets = repo.getSets()
        val idx = binding.spinnerProfileSets.selectedItemPosition
        return sets.getOrNull(idx)
    }

    private fun loadSelectedSet() {
        val set = selectedSet() ?: run {
            notifyUser("No saved sets yet — use \"Save as set\" first.")
            return
        }
        repo.saveRifle(set.rifle)
        repo.saveBullet(set.bullet)
        repo.saveScope(set.scope)
        repo.setActiveSetName(set.name) // v20.10: Home names the active set
        loadIntoFields()
        refreshDropReadouts()
        notifyUser("Loaded set \"${set.name}\" as active.")
    }

    private fun deleteSelectedSet() {
        val set = selectedSet() ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete set \"${set.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteSet(set.name)
                refreshSetSpinner()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Solves BulletProfile.dragCalibrationFactor so the simulated drop at
     * the entered range matches ONE official manufacturer table point
     * (v15.0). The engine's Cd(M) curve is approximate; the maker's
     * ballistic table is measured — this pins the model to it.
     *
     * The official point is taken RELATIVE TO THE TABLE'S OWN ZERO (which
     * usually differs from your scope zero): the solver re-zeroes the
     * simulation at the table zero, matches the drop there, and the
     * calibrated drag then applies to trajectories at YOUR zero everywhere
     * else in the app. Beyond the zero, drop is strictly increasing in
     * drag, so bisection is safe.
     */
    private fun calibrateDragFromOfficialDrop() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val tableZeroM = binding.etTableZero.text.toString().toDoubleOrNull()?.let { um.inputDistanceToMeters(it) }
        val rangeM = binding.etTableRange.text.toString().toDoubleOrNull()?.let { um.inputDistanceToMeters(it) }
        val dropM = binding.etTableDrop.text.toString().toDoubleOrNull()
            ?.let { it * if (um.isImperial()) 0.0254 else 0.01 }
        if (tableZeroM == null || rangeM == null || dropM == null) {
            notifyUser("Fill table zero, range and official drop first.")
            return
        }
        if (rangeM <= tableZeroM + 1.0) {
            notifyUser("Reference range must be beyond the table zero (drop is 0 at the zero).")
            return
        }
        // Calibrate against what's TYPED (MV, BC), not what was last saved.
        saveFromFields()
        val bullet = repo.getBullet()
        val rifle = repo.getRifle()
        val scope = repo.getScope()
        val sightHeightM = com.rfsat.vtb.results.AdjustmentCalculator.effectiveSightHeightM(rifle, scope)
        val atmosphere = com.rfsat.vtb.ballistics.Atmosphere() // official tables assume standard conditions

        fun dropAt(k: Double): Double? {
            val b = bullet.copy(dragCalibrationFactor = k)
            val pitch = com.rfsat.vtb.ballistics.BallisticsEngine.solveZeroPitch(b, atmosphere, tableZeroM, sightHeightM)
            val traj = com.rfsat.vtb.ballistics.BallisticsEngine.simulate(b, atmosphere, pitch, 0.0, rangeM + 1.0)
            val pt = traj.lastOrNull { it.position.x <= rangeM } ?: return null
            if (pt.position.x < rangeM - 2.0) return null // stalled short — drag way too high
            // Drop below the (level) line of sight, positive down.
            return sightHeightM - pt.position.y
        }

        var lo = 0.2; var hi = 5.0
        val dLo = dropAt(lo); val dHi = dropAt(hi)
        if (dLo == null || dLo > dropM) {
            notifyUser("Official drop is below what the model can reach even at minimal drag — check muzzle velocity, BC and units.")
            return
        }
        if (dHi != null && dHi < dropM) {
            notifyUser("Official drop exceeds the model even at maximal drag — check muzzle velocity, BC and units.")
            return
        }
        repeat(48) {
            val mid = 0.5 * (lo + hi)
            val d = dropAt(mid)
            if (d == null || d > dropM) hi = mid else lo = mid
        }
        val k = 0.5 * (lo + hi)
        repo.saveBullet(repo.getBullet().copy(dragCalibrationFactor = k))
        com.rfsat.vtb.log.Logger.i("ProfileActivity",
            "Drag calibrated from official drop: k=${"%.3f".format(k)} " +
            "(tableZero=${"%.0f".format(tableZeroM)}m range=${"%.0f".format(rangeM)}m drop=${"%.3f".format(dropM)}m)")
        notifyUser("Drag calibration factor set to ${"%.3f".format(k)}.")
        refreshDropReadouts()
    }

    /** Predicted drop table at the USER's zero — compare it against the
     *  manufacturer's published table to judge the calibration. */
    private fun refreshDropReadouts() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val bullet = repo.getBullet()
        val rifle = repo.getRifle()
        val scope = repo.getScope()
        binding.tvDragFactor.text = "Drag calibration factor: ${"%.3f".format(bullet.dragCalibrationFactor)}" +
            if (bullet.dragCalibrationFactor == 1.0) " (uncalibrated)" else ""
        try {
            val sightHeightM = com.rfsat.vtb.results.AdjustmentCalculator.effectiveSightHeightM(rifle, scope)
            val atmosphere = com.rfsat.vtb.ballistics.Atmosphere()
            val pitch = com.rfsat.vtb.ballistics.BallisticsEngine.solveZeroPitch(bullet, atmosphere, rifle.zeroDistanceM, sightHeightM)
            val maxM = if (um.isImperial()) 300 * 0.9144 else 300.0
            val traj = com.rfsat.vtb.ballistics.BallisticsEngine.simulate(bullet, atmosphere, pitch, 0.0, maxM + 1.0)
            val sb = StringBuilder("Predicted drop (zero ${"%.0f".format(um.displayDistance(rifle.zeroDistanceM))} ${um.distanceUnitLabel()}) / dial-up:\n")
            var step = 25
            while (step <= 300) {
                val rM = if (um.isImperial()) step * 0.9144 else step.toDouble()
                val pt = traj.lastOrNull { it.position.x <= rM }
                if (pt != null && pt.position.x >= rM - 2.0) {
                    val drop = sightHeightM - pt.position.y
                    val sign = if (drop >= 0) "-" else "+" // shooters read drop below LOS as negative
                    // Elevation correction to dial: the angular size of the
                    // drop at that range. atan, not the small-angle shortcut,
                    // though they agree to <0.1% at rifle angles.
                    val mrad = kotlin.math.atan(kotlin.math.abs(drop) / rM) * 1000.0
                    // Fixed-width right-aligned value columns (monospace font),
                    // matching the right-aligned distance column.
                    val dropStr = "%s%.1f".format(sign, um.displayOffset(kotlin.math.abs(drop)))
                    val mradStr = "%s%.2f".format(if (drop >= 0) "+" else "-", mrad)
                    sb.append("%4d %s: %8s %s %7s MRAD\n".format(
                        step, um.distanceUnitLabel(), dropStr, um.offsetUnitLabel(), mradStr))
                }
                step += 25
            }
            binding.tvDropTable.text = sb.toString().trimEnd()
        } catch (e: Exception) {
            binding.tvDropTable.text = "Drop table unavailable: ${e.message}"
        }
    }

    private fun fillScopeFields(scope: ScopeProfile) = with(binding) {
        etScopeName.setText(scope.name)
        etZoomMin.setText(scope.zoomMin.toString())
        etZoomMax.setText(scope.zoomMax.toString())
        etObjectiveDiameter.setText(scope.objectiveDiameterMm.toString())
        etFocalLength.setText(scope.focalLengthMm.toString())
        etHeightAboveBarrel.setText(scope.heightAboveBarrelIn.toString())
        spinnerClickUnit.setSelection(
            when (scope.clickUnit) {
                ClickUnit.MOA_QUARTER -> 0
                ClickUnit.MOA_EIGHTH -> 1
                ClickUnit.MRAD_TENTH -> 2
            }
        )
    }

    private fun loadIntoFields() {
        val rifle = repo.getRifle()
        val bullet = repo.getBullet()
        val scope = repo.getScope()

        with(binding) {
            etRifleName.setText(rifle.name)
            uBarrel.set(rifle.barrelLengthIn)
            etTwistRate.setText(rifle.twistRateInPerTurn.toString())
            tvZeroLabel.text = "Zero"
            uZero.set(rifle.zeroDistanceM)

            etBulletName.setText(bullet.name)
            uCaliber.set(bullet.caliberDiameterIn)
            uWeight.set(bullet.weightGrains)
            uMv.set(bullet.muzzleVelocityFps)
            etMvTempCoeff.setText(bullet.mvTempCoeffMpsPerC.toString())
            etMvRefTemp.setText(bullet.mvRefTempC.toString())
            etBallisticCoefficient.setText(bullet.ballisticCoefficientG1.toString())
            cbTracer.isChecked = bullet.isTracer
            cbPellet.isChecked = bullet.isPellet

            // Select the matching catalogue entry (or Custom, the last item).
            val presetIdx = ScopeCatalog.entries.indexOfFirst { "${it.brand} ${it.model}" == scope.name }
            val settled = if (presetIdx >= 0) presetIdx else ScopeCatalog.entries.size
            spinnerScopePreset.setSelection(settled)
            lastPresetSpinnerPos = settled
        }
        fillScopeFields(scope)
    }

    private fun saveFromFields() = with(binding) {
        repo.clearActiveSetName() // a manual edit detaches from any saved set
        repo.saveRifle(
            (pendingRifleBase ?: repo.getRifle())
                .also { pendingRifleBase = null }
                .copy( // base carries boresight offsets + sight height
                name = etRifleName.text.toString().ifBlank { RifleProfile.DEFAULT.name },
                barrelLengthIn = uBarrel.get() ?: RifleProfile.DEFAULT.barrelLengthIn,
                twistRateInPerTurn = etTwistRate.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.twistRateInPerTurn,
                zeroDistanceM = uZero.get() ?: RifleProfile.DEFAULT.zeroDistanceM
            )
        )
        repo.saveBullet(
            // copy() from the stored profile so dragCalibrationFactor — set
            // by the drop-calibration solver, not by any text field — is
            // PRESERVED. Constructing a fresh BulletProfile here silently
            // reset it to 1.0 on every save.
            (pendingBulletBase ?: repo.getBullet())
                .also { pendingBulletBase = null }
                .copy(
                name = etBulletName.text.toString().ifBlank { BulletProfile.DEFAULT.name },
                caliberDiameterIn = uCaliber.get() ?: BulletProfile.DEFAULT.caliberDiameterIn,
                weightGrains = uWeight.get() ?: BulletProfile.DEFAULT.weightGrains,
                muzzleVelocityFps = uMv.get() ?: BulletProfile.DEFAULT.muzzleVelocityFps,
                mvTempCoeffMpsPerC = etMvTempCoeff.text.toString().toDoubleOrNull() ?: 0.0,
                mvRefTempC = etMvRefTemp.text.toString().toDoubleOrNull() ?: 15.0,
                ballisticCoefficientG1 = etBallisticCoefficient.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.ballisticCoefficientG1,
                isTracer = cbTracer.isChecked,
                isPellet = binding.cbPellet.isChecked
            )
        )
        val unit = when (spinnerClickUnit.selectedItemPosition) {
            1 -> ClickUnit.MOA_EIGHTH
            2 -> ClickUnit.MRAD_TENTH
            else -> ClickUnit.MOA_QUARTER
        }
        repo.saveScope(
            // v20.7: copy() from a base instead of fresh construction — the
            // old form silently reset the turret-travel fields on every save.
            (pendingScopeBase ?: repo.getScope())
                .also { pendingScopeBase = null }
                .copy(
                name = etScopeName.text.toString().ifBlank { ScopeProfile.DEFAULT.name },
                clickUnit = unit,
                zoomMin = etZoomMin.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.zoomMin,
                zoomMax = etZoomMax.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.zoomMax,
                objectiveDiameterMm = etObjectiveDiameter.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.objectiveDiameterMm,
                focalLengthMm = etFocalLength.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.focalLengthMm,
                heightAboveBarrelIn = etHeightAboveBarrel.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.heightAboveBarrelIn
            )
        )
    }
}
