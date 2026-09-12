package dev.quietinbox.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SourceEvidenceTest : FunSpec({

    test("five known adapters default to SYNTHETIC_ONLY without real device cohort") {
        for (pkg in KnownSources.ALL) {
            val tier = SourceEvidenceResolver.resolveTier(pkg, hasAdapter = true)
            tier shouldBe SourceVerificationTier.SYNTHETIC_ONLY
            tier shouldNotBe SourceVerificationTier.REAL_DEVICE_PASSED
        }
    }

    test("hasAdapter true without real-device evidence never resolves to REAL_DEVICE_PASSED") {
        val tier = SourceEvidenceResolver.resolveTier("com.unknown.adapter", hasAdapter = true)
        tier shouldBe SourceVerificationTier.SYNTHETIC_ONLY
        tier shouldNotBe SourceVerificationTier.REAL_DEVICE_PASSED
    }

    test("unknown package without adapter resolves to UNTESTED") {
        val tier = SourceEvidenceResolver.resolveTier("com.random.unregistered", hasAdapter = false)
        tier shouldBe SourceVerificationTier.UNTESTED
    }

    test("matching cohort fixture in test resolves to REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                sourceVersionCode = 100L,
                adapterId = "test",
                adapterVersion = "1.0.0",
                osApiLevel = 36,
                deviceModel = "SM-S9280",
                language = "zh-Hant",
            ),
            evidenceSummary = "Dual-account real phone matrix passed",
        )
        val customCatalog = SourceEvidenceResolver.DEFAULT_CATALOG + verifiedRecord

        val exactCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "test",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            deviceModel = "SM-S9280",
            language = "zh-Hant",
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = exactCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.REAL_DEVICE_PASSED
    }

    test("source version mismatch does not inherit REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                sourceVersionCode = 100L,
            ),
            evidenceSummary = "Version 100 passed",
        )
        val customCatalog = SourceEvidenceResolver.DEFAULT_CATALOG + verifiedRecord

        val newerVersionCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 101L,
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = newerVersionCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.SYNTHETIC_ONLY
    }

    test("osApiLevel mismatch does not inherit REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                osApiLevel = 34,
            ),
            evidenceSummary = "API 34 passed",
        )
        val customCatalog = SourceEvidenceResolver.DEFAULT_CATALOG + verifiedRecord

        val api36Cohort = SourceCohort(
            packageName = "com.test.messaging",
            osApiLevel = 36,
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = api36Cohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.SYNTHETIC_ONLY
    }

    test("device model mismatch does not inherit REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                deviceModel = "Pixel 8",
            ),
            evidenceSummary = "Pixel 8 passed",
        )
        val customCatalog = SourceEvidenceResolver.DEFAULT_CATALOG + verifiedRecord

        val galaxyCohort = SourceCohort(
            packageName = "com.test.messaging",
            deviceModel = "SM-S9280",
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = galaxyCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.SYNTHETIC_ONLY
    }

    test("language mismatch does not inherit REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                language = "en",
            ),
            evidenceSummary = "en passed",
        )
        val customCatalog = SourceEvidenceResolver.DEFAULT_CATALOG + verifiedRecord

        val jaCohort = SourceCohort(
            packageName = "com.test.messaging",
            language = "ja",
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = jaCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.SYNTHETIC_ONLY
    }
})
