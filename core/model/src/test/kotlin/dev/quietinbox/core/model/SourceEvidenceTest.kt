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

    test("empty catalog resolves to UNTESTED even if hasAdapter is true") {
        val tier = SourceEvidenceResolver.resolveTier(KnownSources.LINE, hasAdapter = true, catalog = emptyList())
        tier shouldBe SourceVerificationTier.UNTESTED
    }

    test("hasAdapter true without any catalog evidence resolves to UNTESTED") {
        val tier = SourceEvidenceResolver.resolveTier("com.unknown.adapter", hasAdapter = true)
        tier shouldBe SourceVerificationTier.UNTESTED
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
                oem = "Samsung",
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
            oem = "Samsung",
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

    test("switching adapterId does not inherit REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                sourceVersionCode = 100L,
                adapterId = "adapter_v1",
                adapterVersion = "1.0.0",
                osApiLevel = 36,
                deviceModel = "SM-S9280",
                language = "zh-Hant",
            ),
            evidenceSummary = "Verified on adapter_v1",
        )
        val customCatalog = listOf(verifiedRecord)

        val switchedAdapterCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "adapter_v2",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            deviceModel = "SM-S9280",
            language = "zh-Hant",
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = switchedAdapterCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.UNTESTED
    }

    test("evidence cohort missing version or device cannot act as universal wildcard") {
        val wildcardRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                adapterId = "test",
                // missing version, OS, and deviceModel
            ),
            evidenceSummary = "Incomplete evidence record",
        )
        val customCatalog = listOf(wildcardRecord)

        val realCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "test",
            osApiLevel = 36,
            deviceModel = "SM-S9280",
            language = "zh-Hant",
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = realCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.UNTESTED
    }

    test("evidence cohort missing language cannot act as universal wildcard") {
        val noLanguageRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                sourceVersionCode = 100L,
                adapterId = "test",
                osApiLevel = 36,
                deviceModel = "SM-S9280",
                language = null,
            ),
            evidenceSummary = "Evidence lacking language",
        )
        val customCatalog = listOf(noLanguageRecord)

        val realCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "test",
            osApiLevel = 36,
            deviceModel = "SM-S9280",
            language = "zh-Hant",
        )

        val resolved = SourceEvidenceResolver.resolveTier(
            packageName = "com.test.messaging",
            hasAdapter = true,
            currentCohort = realCohort,
            catalog = customCatalog,
        )
        resolved shouldBe SourceVerificationTier.UNTESTED
    }

    test("source version mismatch does not inherit REAL_DEVICE_PASSED") {
        val verifiedRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            cohort = SourceCohort(
                packageName = "com.test.messaging",
                sourceVersionCode = 100L,
                adapterId = "test",
                adapterVersion = "1.0.0",
                osApiLevel = 36,
                oem = "Samsung",
                deviceModel = "SM-S9280",
                language = "en",
            ),
            evidenceSummary = "Version 100 passed",
        )
        val syntheticRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(packageName = "com.test.messaging", adapterId = "test", adapterVersion = "1.0.0"),
            evidenceSummary = "Synthetic tests passed",
        )
        val customCatalog = listOf(verifiedRecord, syntheticRecord)

        val newerVersionCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 101L,
            adapterId = "test",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            oem = "Samsung",
            deviceModel = "SM-S9280",
            language = "en",
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
                sourceVersionCode = 100L,
                adapterId = "test",
                adapterVersion = "1.0.0",
                osApiLevel = 34,
                oem = "Samsung",
                deviceModel = "SM-S9280",
                language = "en",
            ),
            evidenceSummary = "API 34 passed",
        )
        val syntheticRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(packageName = "com.test.messaging", adapterId = "test", adapterVersion = "1.0.0"),
            evidenceSummary = "Synthetic tests passed",
        )
        val customCatalog = listOf(verifiedRecord, syntheticRecord)

        val api36Cohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "test",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            oem = "Samsung",
            deviceModel = "SM-S9280",
            language = "en",
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
                sourceVersionCode = 100L,
                adapterId = "test",
                adapterVersion = "1.0.0",
                osApiLevel = 36,
                oem = "Samsung",
                deviceModel = "Pixel 8",
                language = "en",
            ),
            evidenceSummary = "Pixel 8 passed",
        )
        val syntheticRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(packageName = "com.test.messaging", adapterId = "test", adapterVersion = "1.0.0"),
            evidenceSummary = "Synthetic tests passed",
        )
        val customCatalog = listOf(verifiedRecord, syntheticRecord)

        val galaxyCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "test",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            oem = "Samsung",
            deviceModel = "SM-S9280",
            language = "en",
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
                sourceVersionCode = 100L,
                adapterId = "test",
                adapterVersion = "1.0.0",
                osApiLevel = 36,
                oem = "Samsung",
                deviceModel = "SM-S9280",
                language = "en",
            ),
            evidenceSummary = "en passed",
        )
        val syntheticRecord = SourceEvidenceRecord(
            packageName = "com.test.messaging",
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = SourceCohort(packageName = "com.test.messaging", adapterId = "test", adapterVersion = "1.0.0"),
            evidenceSummary = "Synthetic tests passed",
        )
        val customCatalog = listOf(verifiedRecord, syntheticRecord)

        val jaCohort = SourceCohort(
            packageName = "com.test.messaging",
            sourceVersionCode = 100L,
            adapterId = "test",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            oem = "Samsung",
            deviceModel = "SM-S9280",
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

    // S1: Contract counterexamples from review (PR #40 / Issue #38)
    val reviewPkg = "synthetic.review.messaging"
    val reviewFull = SourceCohort(
        packageName = reviewPkg,
        sourceVersionCode = 100L,
        sourceVersionName = "1.0",
        adapterId = "synthetic-parser",
        adapterVersion = "1.0",
        osApiLevel = 36,
        oem = "ReviewOEM",
        deviceModel = "ReviewDevice",
        language = "en",
    )

    fun resolveReview(
        evidence: SourceCohort,
        current: SourceCohort = reviewFull,
        tier: SourceVerificationTier = SourceVerificationTier.REAL_DEVICE_PASSED,
    ): SourceVerificationTier = SourceEvidenceResolver.resolveTier(
        packageName = reviewPkg,
        hasAdapter = true,
        currentCohort = current,
        catalog = listOf(
            SourceEvidenceRecord(
                packageName = reviewPkg,
                tier = tier,
                cohort = evidence,
                evidenceSummary = "synthetic test only",
                commitSha = "a".repeat(40),
            )
        ),
    )

    test("positive control: complete matching cohort resolves to REAL_DEVICE_PASSED") {
        resolveReview(reviewFull) shouldBe SourceVerificationTier.REAL_DEVICE_PASSED
    }

    test("evidence missing deviceModel with API retained cannot resolve to REAL_DEVICE_PASSED") {
        resolveReview(reviewFull.copy(deviceModel = null)) shouldBe SourceVerificationTier.UNTESTED
    }

    test("evidence missing osApiLevel with model retained cannot resolve to REAL_DEVICE_PASSED") {
        resolveReview(reviewFull.copy(osApiLevel = null)) shouldBe SourceVerificationTier.UNTESTED
    }

    test("evidence missing adapterVersion cannot resolve to REAL_DEVICE_PASSED") {
        resolveReview(reviewFull.copy(adapterVersion = null)) shouldBe SourceVerificationTier.UNTESTED
    }

    test("evidence missing OEM cannot resolve to REAL_DEVICE_PASSED") {
        resolveReview(reviewFull.copy(oem = null)) shouldBe SourceVerificationTier.UNTESTED
    }

    test("evidence and current adapterId both blank cannot resolve to REAL_DEVICE_PASSED") {
        resolveReview(reviewFull.copy(adapterId = ""), reviewFull.copy(adapterId = "")) shouldBe SourceVerificationTier.UNTESTED
    }

    test("catalog with only parser v1 synthetic evidence cannot be inherited by current parser v2") {
        resolveReview(
            evidence = reviewFull,
            current = reviewFull.copy(adapterVersion = "2.0"),
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
        ) shouldBe SourceVerificationTier.UNTESTED
    }

    test("catalog with unversioned synthetic evidence cannot be inherited by current versioned parser v2") {
        resolveReview(
            evidence = reviewFull.copy(adapterVersion = null),
            current = reviewFull.copy(adapterVersion = "2.0"),
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
        ) shouldBe SourceVerificationTier.UNTESTED
    }

    test("synthetic evidence with missing or blank adapterVersion in both evidence and current resolves to UNTESTED") {
        val pkg = "synthetic.review.messaging"
        for (version in listOf<String?>(null, "", " ")) {
            val cohort = SourceCohort(pkg, adapterId = "synthetic-parser", adapterVersion = version)
            val record = SourceEvidenceRecord(
                packageName = pkg,
                tier = SourceVerificationTier.SYNTHETIC_ONLY,
                cohort = cohort,
                evidenceSummary = "synthetic-only test fixture",
            )
            // Tested with matching cohort
            SourceEvidenceResolver.resolveTier(pkg, true, cohort, listOf(record)) shouldBe
                SourceVerificationTier.UNTESTED
            // Also tested without current device cohort: unversioned synthetic evidence cannot be accepted
            SourceEvidenceResolver.resolveTier(pkg, true, null, listOf(record)) shouldBe
                SourceVerificationTier.UNTESTED
        }
    }

    test("synthetic positive control: identical explicit parser and version resolves to SYNTHETIC_ONLY across devices") {
        val pkg = "synthetic.review.messaging"
        val evidenceCohort = SourceCohort(
            packageName = pkg,
            adapterId = "synthetic-parser",
            adapterVersion = "1.0.0",
            osApiLevel = 34,
            deviceModel = "Pixel 8",
        )
        val currentCohort = SourceCohort(
            packageName = pkg,
            adapterId = "synthetic-parser",
            adapterVersion = "1.0.0",
            osApiLevel = 36,
            deviceModel = "SM-S9280",
        )
        val record = SourceEvidenceRecord(
            packageName = pkg,
            tier = SourceVerificationTier.SYNTHETIC_ONLY,
            cohort = evidenceCohort,
            evidenceSummary = "synthetic unit test",
        )
        SourceEvidenceResolver.resolveTier(pkg, true, currentCohort, listOf(record)) shouldBe
            SourceVerificationTier.SYNTHETIC_ONLY
    }
})
