package madoyomi.exthost

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.android.apksig.ApkVerifier
import net.dongliu.apk.parser.ApkFile
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** What apksig says about an APK's signatures. */
data class ApkSignatureInfo(
    /** apksig verified at least one scheme and found no error. */
    val verified: Boolean,
    /** The schemes that verified: `v1`, `v2`, `v3`, `v3.1`, `v4`. */
    val schemes: Set<String>,
    /** Lowercase hex SHA-256 of each signer certificate (DER), in apksig's order (for v3: the current signer first). */
    val signerCertSha256: List<String>,
    val errors: List<String>,
) {
    /** The hash Madoyomi trusts: the first signer's (the same format as a repo's `signingKeyFingerprint`). */
    val signatureHash: String? get() = signerCertSha256.firstOrNull()
}

/** An extension APK's manifest and signatures, read without running any of its code. */
data class ApkInspection(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String?,
    val minSdk: Int?,
    val usesFeatures: List<String>,
    /** `<application>`'s `meta-data` (name → value). */
    val metaData: Map<String, String>,
    val signature: ApkSignatureInfo,
) {
    /** The extension kind its features declare, or null when it isn't an extension APK. */
    val kind: ExtensionKind? get() = ExtensionKind.fromFeatures(usesFeatures)

    /**
     * The extensions-lib version: [ExtensionKind.LIB_KEY], else the version name without its last part (upstream's
     * rule: `1.4.12` → `1.4`, `14.2` → `14`).
     */
    fun libVersion(): String =
        metaData[ExtensionKind.LIB_KEY]?.takeUnless { it.isBlank() || it == "0" } ?: versionName.substringBeforeLast('.')
}

/**
 * Reads an APK's manifest with apk-parser and verifies its signatures with apksig (v1 JAR signing, v2/v3/v3.1 APK
 * Signature Scheme). apk-parser alone only *reads* the v1 certificate and never checks it, so it can't be used for
 * trust.
 */
object ApkInspector {
    fun inspect(apk: Path): ApkInspection {
        val signature = verify(apk)
        return ApkFile(apk.toFile()).use { parsed ->
            val meta = parsed.apkMeta
            val doc =
                parsed.manifestXml.byteInputStream().use {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
                }
            val application = doc.getElementsByTagName("application").item(0)
            val metaData =
                application
                    ?.childElements("meta-data")
                    .orEmpty()
                    .mapNotNull { element ->
                        val name = element.getAttribute("android:name").ifEmpty { return@mapNotNull null }
                        name to decodeTypedValue(element.getAttribute("android:value"))
                    }.toMap()
            val features =
                doc.documentElement
                    .childElements("uses-feature")
                    .map { it.getAttribute("android:name") }
                    .filter { it.isNotEmpty() }
            ApkInspection(
                packageName = meta.packageName,
                versionCode = meta.versionCode ?: 0L,
                versionName = meta.versionName.orEmpty(),
                label = meta.label,
                minSdk = meta.minSdkVersion?.toIntOrNull(),
                usesFeatures = features,
                metaData = metaData,
                signature = signature,
            )
        }
    }

    fun verify(apk: Path): ApkSignatureInfo {
        val result =
            try {
                ApkVerifier.Builder(apk.toFile()).build().verify()
            } catch (e: Exception) {
                return ApkSignatureInfo(false, emptySet(), emptyList(), listOf("${e.javaClass.simpleName}: ${e.message}"))
            }
        val schemes =
            buildSet {
                if (result.isVerifiedUsingV1Scheme) add("v1")
                if (result.isVerifiedUsingV2Scheme) add("v2")
                if (result.isVerifiedUsingV3Scheme) add("v3")
                if (result.isVerifiedUsingV31Scheme) add("v3.1")
                if (result.isVerifiedUsingV4Scheme) add("v4")
            }
        val errors = result.allErrors.map { it.toString() }
        return ApkSignatureInfo(
            verified = result.isVerified && !result.containsErrors(),
            schemes = schemes,
            signerCertSha256 = if (result.isVerified) result.signerCertificates.map { sha256Hex(it.encoded) } else emptyList(),
            errors = errors,
        )
    }

    /**
     * apk-parser renders typed binary-XML values it doesn't format itself as `{type:data}`. Keiyoushi's
     * `tachiyomix.extensionLib` is a float (`{4:1070386381}` = 1.6f), so floats (`TYPE_FLOAT` = 4) are decoded; other
     * typed values become their raw data.
     */
    internal fun decodeTypedValue(value: String): String {
        val match = TYPED_VALUE.matchEntire(value) ?: return value
        val (type, data) = match.destructured
        val bits = data.toLongOrNull()?.toInt() ?: return value
        return if (type == "4") Float.fromBits(bits).toString() else bits.toString()
    }

    private val TYPED_VALUE = Regex("""\{(\d+):(-?\d+)}""")

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Node.childElements(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }
            .filter { it.tagName == tag }
    }
}
