package zed.rainxch.githubstore.announcements

import java.time.format.DateTimeParseException
import java.time.Instant

// Server-side enforcement of every rule in announcements-endpoint.md §2.
// Runs at load time so a malformed file never reaches the served payload.
// Returns a list of violations (empty = valid). The loader logs and drops
// any item that produces a non-empty list.
object AnnouncementValidator {

    private val SEVERITIES = setOf("INFO", "IMPORTANT", "CRITICAL")
    private val CATEGORIES = setOf("NEWS", "PRIVACY", "SURVEY", "SECURITY", "STATUS")
    private val ICON_HINTS = setOf("INFO", "WARNING", "SECURITY", "CELEBRATION", "CHANGE")
    // Lowercase canonical hosts. Mirrors ForgejoSearchClient.DEFAULT_TRUSTED_HOSTS
    // + the GitHub baseline. Kept narrow on purpose — authors can't tag a
    // banner with an arbitrary URL, only one of the hosts the rest of the
    // forge surface is configured to talk to.
    private val VALID_SOURCE_HOSTS = setOf(
        "github.com",
        "codeberg.org",
        "gitea.com",
        "git.disroot.org",
    )

    private const val TITLE_MAX = 80
    private const val BODY_MIN = 50
    private const val BODY_MAX = 600
    private const val CTA_LABEL_MAX = 30

    // Lowercase alphanumerics + hyphen, 1-64 chars. Matches the admin-panel
    // handoff doc and rejects path traversal, control chars, mixed case.
    private val ID_PATTERN = Regex("^[a-z0-9-]{1,64}$")

    // BCP-47 -- a primary subtag of 2–3 letters, optional region/script subtags
    // of 2–8 alphanumerics each. Strict enough to reject "en_US" and "EN-us-".
    private val BCP47 = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    // Unicode bidi-override codepoints — used in homograph attacks to flip
    // visual rendering of identifiers/URLs.
    private val BIDI_OVERRIDE = "[‪-‮⁦-⁩]".toRegex()

    fun validate(item: AnnouncementDto): List<String> {
        val errs = mutableListOf<String>()

        if (item.id.isBlank()) errs += "id: blank"
        else if (!ID_PATTERN.matches(item.id)) errs += "id: must match ^[a-z0-9-]{1,64}$"

        if (!isIso8601(item.publishedAt)) errs += "publishedAt: not ISO 8601"
        item.expiresAt?.let { if (!isIso8601(it)) errs += "expiresAt: not ISO 8601" }

        if (item.expiresAt != null && isIso8601(item.publishedAt) && isIso8601(item.expiresAt)) {
            val pub = Instant.parse(item.publishedAt)
            val exp = Instant.parse(item.expiresAt)
            if (!exp.isAfter(pub)) errs += "expiresAt: must be after publishedAt"
        }

        val severity = item.severity.uppercase()
        if (severity !in SEVERITIES) errs += "severity: '${item.severity}' not in $SEVERITIES"

        val category = item.category.uppercase()
        if (category !in CATEGORIES) errs += "category: '${item.category}' not in $CATEGORIES"

        item.iconHint?.let {
            if (it.uppercase() !in ICON_HINTS) errs += "iconHint: '$it' not in $ICON_HINTS"
        }

        // Forge brief 3.7. sourceHost (when present) must be in the same
        // allowlist the rest of the forge surface respects, plus the
        // GitHub canonical. Authors who write a typo or invent a vendor
        // ("codeberge.org") fail loud at load time instead of shipping a
        // banner with a host the UI can't render.
        item.sourceHost?.let {
            val normalized = it.trim().lowercase()
            if (normalized !in VALID_SOURCE_HOSTS) {
                errs += "sourceHost: '$it' not in $VALID_SOURCE_HOSTS"
            }
        }

        // Locale-aware length checks. Defaults are checked against EN; each
        // i18n variant is checked independently because translators can
        // overrun even when the source is in budget.
        // Body is multi-line (\n / \r / \t allowed); title and ctaLabel are
        // single-line so control chars including newlines are rejected.
        validateText(label = "title", value = item.title, min = 1, max = TITLE_MAX, multiline = false)?.let(errs::add)
        validateText(label = "body", value = item.body, min = BODY_MIN, max = BODY_MAX, multiline = true)?.let(errs::add)
        item.ctaLabel?.let {
            validateText(label = "ctaLabel", value = it, min = 1, max = CTA_LABEL_MAX, multiline = false)?.let(errs::add)
        }

        item.i18n.forEach { (locale, variant) ->
            if (!BCP47.matches(locale)) errs += "i18n.$locale: not a BCP-47 code"
            variant.title?.let { validateText("i18n.$locale.title", it, 1, TITLE_MAX, multiline = false)?.let(errs::add) }
            variant.body?.let { validateText("i18n.$locale.body", it, BODY_MIN, BODY_MAX, multiline = true)?.let(errs::add) }
            variant.ctaLabel?.let {
                validateText("i18n.$locale.ctaLabel", it, 1, CTA_LABEL_MAX, multiline = false)?.let(errs::add)
            }
            variant.ctaUrl?.let { if (!it.startsWith("https://")) errs += "i18n.$locale.ctaUrl: must be https://" }
        }

        if (item.requiresAcknowledgment && item.dismissible) {
            errs += "requiresAcknowledgment=true requires dismissible=false"
        }
        if (category == "SECURITY" && severity !in setOf("IMPORTANT", "CRITICAL")) {
            errs += "category=SECURITY requires severity in [IMPORTANT, CRITICAL]"
        }
        if (category == "PRIVACY" && !item.requiresAcknowledgment) {
            errs += "category=PRIVACY requires requiresAcknowledgment=true"
        }

        item.ctaUrl?.let {
            if (!it.startsWith("https://")) errs += "ctaUrl: must be https://"
        }

        return errs
    }

    // Top-level validator used after every individual item has passed. Catches
    // the cross-item rule that no two items may share an id; per spec, a
    // duplicate rejects the whole payload, not just the duplicate.
    fun checkDuplicates(items: List<AnnouncementDto>): String? {
        val dupes = items.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        return if (dupes.isEmpty()) null
        else "duplicate ids: $dupes"
    }

    private fun validateText(
        label: String,
        value: String,
        min: Int,
        max: Int,
        multiline: Boolean,
    ): String? {
        val len = value.length
        return when {
            len < min -> "$label: < $min chars (was $len)"
            len > max -> "$label: > $max chars (was $len)"
            value.any { it.isDangerousControl(multiline) } -> "$label: contains control character"
            BIDI_OVERRIDE.containsMatchIn(value) -> "$label: contains bidi override character"
            else -> null
        }
    }

    // Multi-line fields (body / per-locale body) accept printable whitespace --
    // \n \r \t -- because the admin panel surfaces a textarea and the client
    // renders body as plain text with newlines preserved. All other ISO control
    // characters (NULL, escape, vertical-tab, etc.) remain rejected as
    // UI-spoofing / log-injection vectors. Single-line fields (title, ctaLabel)
    // reject every control char.
    private fun Char.isDangerousControl(multiline: Boolean): Boolean {
        if (!isISOControl()) return false
        if (multiline && (this == '\n' || this == '\r' || this == '\t')) return false
        return true
    }

    private fun isIso8601(value: String): Boolean = try {
        Instant.parse(value)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}
