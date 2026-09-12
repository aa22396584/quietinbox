package dev.quietinbox.core.model

/**
 * Verification tier for message sources according to COMPATIBILITY.md (plan §14).
 *
 * UNTESTED: Standard parser or no adapter test evidence.
 * SYNTHETIC_ONLY: Adapter has synthetic unit/fixture tests, but lacks real-device two-party confirmation.
 * REAL_DEVICE_PASSED: Verified on real hardware with two accounts across test matrix scenarios.
 * PARTIAL: Some scenarios verified on real hardware, but gaps remain.
 * REGRESSED: Previous verification broken on newer source or OS version.
 * BLOCKED: Source or platform actively blocks capture (e.g. DPC, MDM, platform redaction).
 */
enum class SourceVerificationTier {
    UNTESTED,
    SYNTHETIC_ONLY,
    REAL_DEVICE_PASSED,
    PARTIAL,
    REGRESSED,
    BLOCKED,
}

data class SourceCohort(
    val packageName: String,
    val sourceVersionCode: Long? = null,
    val sourceVersionName: String? = null,
    val adapterId: String? = null,
    val adapterVersion: String? = null,
    val osApiLevel: Int? = null,
    val oem: String? = null,
    val deviceModel: String? = null,
    val language: String? = null,
)

data class SourceEvidenceRecord(
    val packageName: String,
    val tier: SourceVerificationTier,
    val cohort: SourceCohort,
    val evidenceSummary: String,
    val commitSha: String? = null,
)

object SourceEvidenceResolver {
    /**
     * Default baseline evidence catalog from COMPATIBILITY.md.
     * All 5 well-known external messaging apps currently have SYNTHETIC_ONLY status.
     * Only the local synthetic publisher has REAL_DEVICE_PASSED on SM-S9280 / Android 16.
     */
    val DEFAULT_CATALOG: List<SourceEvidenceRecord> = listOf(
        SourceEvidenceRecord(
            packageName = KnownSources.LINE,
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(
                packageName = KnownSources.LINE,
                adapterId = "line",
                adapterVersion = "0.1.0",
            ),
            evidenceSummary = "parsers/apps/.../LineParserTest.kt",
        ),
        SourceEvidenceRecord(
            packageName = KnownSources.WHATSAPP,
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(
                packageName = KnownSources.WHATSAPP,
                adapterId = "whatsapp",
                adapterVersion = "0.1.0",
            ),
            evidenceSummary = "parsers/apps/.../WhatsAppParserTest.kt",
        ),
        SourceEvidenceRecord(
            packageName = KnownSources.TELEGRAM,
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(
                packageName = KnownSources.TELEGRAM,
                adapterId = "telegram",
                adapterVersion = "0.1.0",
            ),
            evidenceSummary = "parsers/apps/.../TelegramParserTest.kt",
        ),
        SourceEvidenceRecord(
            packageName = KnownSources.INSTAGRAM,
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(
                packageName = KnownSources.INSTAGRAM,
                adapterId = "instagram",
                adapterVersion = "0.1.0",
            ),
            evidenceSummary = "parsers/apps/.../InstagramParserTest.kt",
        ),
        SourceEvidenceRecord(
            packageName = KnownSources.MESSENGER,
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(
                packageName = KnownSources.MESSENGER,
                adapterId = "messenger",
                adapterVersion = "0.1.0",
            ),
            evidenceSummary = "parsers/apps/.../MessengerParserTest.kt",
        ),
        SourceEvidenceRecord(
            packageName = "dev.quietinbox.app.debug",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "dev.quietinbox.app.debug",
                sourceVersionCode = 1L,
                adapterId = "standard",
                adapterVersion = "1.0.0",
                osApiLevel = 36,
                oem = "Samsung",
                deviceModel = "SM-S9280",
                language = "zh-Hant",
            ),
            evidenceSummary = "Onboarding step 4 captured 3/3 messages",
            commitSha = "afa7818",
        ),
    )

    fun resolveTier(
        packageName: String,
        hasAdapter: Boolean,
        currentCohort: SourceCohort? = null,
        catalog: List<SourceEvidenceRecord> = DEFAULT_CATALOG,
    ): SourceVerificationTier {
        if (!hasAdapter && packageName !in KnownSources.ALL) {
            return SourceVerificationTier.UNTESTED
        }

        // Check for matching evidence records in catalog
        val matches = catalog.filter { it.packageName == packageName }
        if (matches.isEmpty()) {
            // An empty catalog or unknown evidence never claims SYNTHETIC_ONLY merely because hasAdapter == true
            return SourceVerificationTier.UNTESTED
        }

        if (currentCohort != null) {
            val matchingRecord = matches.firstOrNull { record ->
                matchesCohort(record.cohort, currentCohort, record.tier)
            }
            if (matchingRecord != null) {
                return matchingRecord.tier
            }
            // Cohort mismatch: never inherits verified status of older/different cohort (plan §14).
            // Falls back to SYNTHETIC_ONLY only if catalog contains synthetic evidence matching the current parser.
            val matchingSynthetic = matches.firstOrNull { record ->
                record.tier == SourceVerificationTier.SYNTHETIC_ONLY &&
                    matchesCohort(record.cohort, currentCohort, record.tier)
            }
            return matchingSynthetic?.tier ?: SourceVerificationTier.UNTESTED
        }

        // Without device cohort, check if there is an explicit non-device tier (e.g. SYNTHETIC_ONLY)
        val nonDeviceRecord = matches.firstOrNull { it.tier == SourceVerificationTier.SYNTHETIC_ONLY }
        if (nonDeviceRecord != null) {
            return SourceVerificationTier.SYNTHETIC_ONLY
        }
        val firstRecord = matches.first()
        if (firstRecord.tier == SourceVerificationTier.REAL_DEVICE_PASSED) {
            // Unconfirmed device cohort cannot inherit REAL_DEVICE_PASSED
            return SourceVerificationTier.UNTESTED
        }
        return firstRecord.tier
    }

    private fun matchesCohort(evidence: SourceCohort, current: SourceCohort, tier: SourceVerificationTier): Boolean {
        if (evidence.packageName != current.packageName) return false

        if (tier == SourceVerificationTier.REAL_DEVICE_PASSED) {
            // Switching adapterId must not inherit REAL_DEVICE_PASSED; adapterId must strictly match and not be blank
            if (evidence.adapterId.isNullOrBlank() || current.adapterId.isNullOrBlank() || evidence.adapterId != current.adapterId) {
                return false
            }
            // adapterVersion must strictly match and not be blank
            if (evidence.adapterVersion.isNullOrBlank() || current.adapterVersion.isNullOrBlank() || evidence.adapterVersion != current.adapterVersion) {
                return false
            }
            // Evidence cohort cannot be a universal wildcard: must specify version, OS API, OEM, device model, and language
            val hasVersion = evidence.sourceVersionCode != null || !evidence.sourceVersionName.isNullOrBlank()
            if (!hasVersion) return false
            if (evidence.osApiLevel == null || current.osApiLevel == null || evidence.osApiLevel != current.osApiLevel) return false
            if (evidence.oem.isNullOrBlank() || current.oem.isNullOrBlank() || !evidence.oem.equals(current.oem, ignoreCase = true)) return false
            if (evidence.deviceModel.isNullOrBlank() || current.deviceModel.isNullOrBlank() || !evidence.deviceModel.equals(current.deviceModel, ignoreCase = true)) return false
            if (evidence.language.isNullOrBlank() || current.language.isNullOrBlank() || !evidence.language.equals(current.language, ignoreCase = true)) return false

            if (evidence.sourceVersionCode != null && evidence.sourceVersionCode != current.sourceVersionCode) return false
            if (evidence.sourceVersionName != null && evidence.sourceVersionName != current.sourceVersionName) return false
            return true
        } else if (tier == SourceVerificationTier.SYNTHETIC_ONLY) {
            // Synthetic evidence tests only parser identity and parser version.
            // Does not require real-source device/OS matrix.
            if (evidence.adapterId.isNullOrBlank() || current.adapterId.isNullOrBlank() || evidence.adapterId != current.adapterId) {
                return false
            }
            if (evidence.adapterVersion != current.adapterVersion) {
                return false
            }
            return true
        } else {
            if (evidence.adapterId != null && evidence.adapterId != current.adapterId) return false
            if (evidence.sourceVersionCode != null && evidence.sourceVersionCode != current.sourceVersionCode) return false
            if (evidence.sourceVersionName != null && evidence.sourceVersionName != current.sourceVersionName) return false
            if (evidence.adapterVersion != null && evidence.adapterVersion != current.adapterVersion) return false
            if (evidence.osApiLevel != null && evidence.osApiLevel != current.osApiLevel) return false
            if (evidence.oem != null && !evidence.oem.equals(current.oem, ignoreCase = true)) return false
            if (evidence.deviceModel != null && !evidence.deviceModel.equals(current.deviceModel, ignoreCase = true)) return false
            if (evidence.language != null && !evidence.language.equals(current.language, ignoreCase = true)) return false
            return true
        }
    }
}
