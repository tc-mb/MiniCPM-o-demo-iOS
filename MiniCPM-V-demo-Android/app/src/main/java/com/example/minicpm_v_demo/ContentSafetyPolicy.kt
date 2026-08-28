package com.example.minicpm_v_demo

import java.text.Normalizer
import java.util.Locale

enum class ContentSafetyDecision {
    ALLOW,
    WARNING,
    BLOCK,
    REVIEW
}

enum class PrivacyDataType {
    CHINESE_ID_CARD,
    MOBILE_PHONE,
    POSTAL_ADDRESS
}

enum class IllegalContentCategory {
    FRAUD,
    CREDENTIAL_THEFT,
    EXPLOSIVES,
    FORGED_DOCUMENTS,
    ILLEGAL_DRUGS
}

data class ContentSafetyAssessment(
    val privacyTypes: Set<PrivacyDataType> = emptySet(),
    val illegalCategory: IllegalContentCategory? = null,
    val requiresReview: Boolean = false
)

object ContentSafetyPolicyEngine {
    fun evaluate(assessment: ContentSafetyAssessment): ContentSafetyDecision =
        when {
            assessment.illegalCategory != null -> ContentSafetyDecision.BLOCK
            assessment.requiresReview -> ContentSafetyDecision.REVIEW
            assessment.privacyTypes.isNotEmpty() -> ContentSafetyDecision.WARNING
            else -> ContentSafetyDecision.ALLOW
        }
}

enum class ContentDisplayAction {
    SHOW_CANDIDATE,
    SHOW_VISUAL_GUARD,
    REQUEST_PRIVACY_CONFIRMATION,
    SHOW_ILLEGAL_REFUSAL,
    SHOW_REVIEW_FALLBACK
}

object ContentSafetyDisplayPolicy {
    fun plan(
        visualDecision: VisualResponseDecision,
        contentDecision: ContentSafetyDecision
    ): ContentDisplayAction = when {
        contentDecision == ContentSafetyDecision.BLOCK ->
            ContentDisplayAction.SHOW_ILLEGAL_REFUSAL
        contentDecision == ContentSafetyDecision.REVIEW ->
            ContentDisplayAction.SHOW_REVIEW_FALLBACK
        visualDecision != VisualResponseDecision.ALLOW ->
            ContentDisplayAction.SHOW_VISUAL_GUARD
        contentDecision == ContentSafetyDecision.WARNING ->
            ContentDisplayAction.REQUEST_PRIVACY_CONFIRMATION
        else -> ContentDisplayAction.SHOW_CANDIDATE
    }
}

enum class PrivacyInputChoiceAction {
    SUBMIT,
    DELETE,
    IGNORE
}

object PrivacyInputConfirmationPolicy {
    fun resolve(
        pendingMessageId: Long,
        selectedMessageId: Long,
        approved: Boolean
    ): PrivacyInputChoiceAction {
        if (pendingMessageId != selectedMessageId) {
            return PrivacyInputChoiceAction.IGNORE
        }
        return if (approved) {
            PrivacyInputChoiceAction.SUBMIT
        } else {
            PrivacyInputChoiceAction.DELETE
        }
    }
}

object LocalContentSafetyClassifier {
    private const val MAX_CLASSIFIER_CHARS = 8_192

    private val chineseIdPattern = Regex(
        pattern = "(?<![0-9])[0-9](?:[ -]?[0-9]){16}[ -]?[0-9xX](?![0-9])"
    )
    private val mobilePhonePattern = Regex(
        pattern = "(?<![0-9])1[3-9](?:[ -]?[0-9]){9}(?![0-9])"
    )

    private val addressLabels = listOf(
        "收货地址",
        "家庭住址",
        "居住地址",
        "联系地址",
        "住址",
        "地址"
    )
    private val regionMarkers = listOf("省", "市", "自治区", "自治州", "区", "县")
    private val streetMarkers = listOf("街", "路", "道", "巷", "镇", "乡", "村", "号", "单元", "室")

    private val educationalMarkers = listOf(
        "防范",
        "预防",
        "识别诈骗",
        "举报",
        "为什么违法",
        "法律后果",
        "危害",
        "危险",
        "新闻",
        "历史",
        "科普",
        "安全教育",
        "protect against",
        "prevent",
        "detect fraud",
        "report fraud",
        "legal consequences",
        "why is it illegal",
        "why is it dangerous"
    )

    private val actionMarkers = listOf(
        "如何",
        "怎么",
        "教我",
        "教程",
        "方法",
        "具体步骤",
        "列出材料",
        "步骤如下",
        "第一步",
        "准备材料",
        "制作",
        "制造",
        "合成",
        "编写",
        "生成",
        "实施",
        "how to",
        "teach me",
        "step by step",
        "instructions",
        "materials",
        "make a",
        "build a",
        "write a"
    )

    private val operationalRequestMarkers = listOf(
        "请写",
        "教我",
        "教程",
        "具体步骤",
        "列出材料",
        "步骤如下",
        "第一步",
        "准备材料",
        "生成一套",
        "实施步骤",
        "teach me",
        "step by step",
        "instructions",
        "write a"
    )

    private val reviewMarkers = listOf(
        "不会被发现",
        "不被发现",
        "不留下痕迹",
        "绕过审查",
        "规避审查",
        "逃避检查",
        "avoid detection",
        "leave no trace",
        "bypass review",
        "evade inspection"
    )

    fun classify(input: String): ContentSafetyAssessment {
        val text = normalize(input)
        if (text.isEmpty()) return ContentSafetyAssessment()

        val privacyTypes = buildSet {
            if (chineseIdPattern.containsMatchIn(text)) add(PrivacyDataType.CHINESE_ID_CARD)
            if (mobilePhonePattern.containsMatchIn(text)) add(PrivacyDataType.MOBILE_PHONE)
            if (containsPostalAddress(text)) add(PrivacyDataType.POSTAL_ADDRESS)
        }

        val isEducational = educationalMarkers.any(text::contains)
        val isOperationalRequest = operationalRequestMarkers.any(text::contains)
        if (isEducational && !isOperationalRequest) {
            return ContentSafetyAssessment(privacyTypes = privacyTypes)
        }

        val illegalCategory = detectIllegalCategory(text)
        val requiresReview = illegalCategory == null && reviewMarkers.any(text::contains)
        return ContentSafetyAssessment(
            privacyTypes = privacyTypes,
            illegalCategory = illegalCategory,
            requiresReview = requiresReview
        )
    }

    private fun detectIllegalCategory(text: String): IllegalContentCategory? {
        if (listOf("诈骗话术", "诈骗脚本", "骗取钱", "洗钱步骤", "phishing script", "scam script")
                .any(text::contains)
        ) {
            return IllegalContentCategory.FRAUD
        }
        if (listOf("窃取密码", "盗取密码", "入侵账户", "盗号", "steal password", "hack account")
                .any(text::contains)
        ) {
            return IllegalContentCategory.CREDENTIAL_THEFT
        }
        val hasCredentialTheftIntent = listOf(
            "窃取",
            "盗取",
            "偷取",
            "获取受害者",
            "steal",
            "hack"
        )
            .any(text::contains)
        val hasCredentialTarget = listOf("密码", "账号", "账户", "登录凭据", "password", "account", "credential")
            .any(text::contains)
        if (hasCredentialTheftIntent && hasCredentialTarget) {
            return IllegalContentCategory.CREDENTIAL_THEFT
        }
        if (listOf("伪造身份证", "伪造护照", "制作假证", "fake id", "forge passport")
                .any(text::contains)
        ) {
            return IllegalContentCategory.FORGED_DOCUMENTS
        }

        val hasAction = actionMarkers.any(text::contains)
        if (!hasAction) return null

        return when {
            listOf("炸弹", "爆炸物", "爆炸装置", "bomb", "explosive device")
                .any(text::contains) -> IllegalContentCategory.EXPLOSIVES
            listOf("制毒", "冰毒", "毒品合成", "methamphetamine", "illegal drugs")
                .any(text::contains) -> IllegalContentCategory.ILLEGAL_DRUGS
            listOf("诈骗", "洗钱", "phishing", "money laundering")
                .any(text::contains) -> IllegalContentCategory.FRAUD
            else -> null
        }
    }

    private fun containsPostalAddress(text: String): Boolean {
        val labeledAddress = addressLabels.any { label ->
            val labelIndex = text.indexOf(label)
            if (labelIndex < 0) return@any false
            val suffix = text.substring(labelIndex + label.length)
                .trimStart(' ', '\t', ':', '：')
                .take(120)
            suffix.length >= 5 && streetMarkers.any(suffix::contains)
        }
        if (labeledAddress) return true

        val regionCount = regionMarkers.count(text::contains)
        val streetCount = streetMarkers.count(text::contains)
        return regionCount >= 2 && streetCount >= 2
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.take(MAX_CLASSIFIER_CHARS), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
}

enum class ConfirmationDecision {
    CONFIRM,
    DECLINE,
    INVALID
}

object ExplicitConfirmationParser {
    private val affirmativeReplies = setOf(
        "是",
        "确认显示",
        "确认继续",
        "yes",
        "show it"
    )
    private val negativeReplies = setOf(
        "否",
        "取消",
        "不显示",
        "no"
    )

    fun parse(input: String): ConfirmationDecision {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
            .trimEnd('。', '！', '!', '？', '?')
            .trim()
        return when (normalized) {
            in affirmativeReplies -> ConfirmationDecision.CONFIRM
            in negativeReplies -> ConfirmationDecision.DECLINE
            else -> ConfirmationDecision.INVALID
        }
    }
}
