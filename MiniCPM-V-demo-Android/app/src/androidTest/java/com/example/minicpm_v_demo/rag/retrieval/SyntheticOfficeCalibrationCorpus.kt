package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.chunk.CjkBigramEncoder
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import java.util.Locale

enum class CalibrationCategory {
    RELEVANT,
    SIMILAR_BUT_WRONG,
    UNRELATED,
    GREETING,
    IDENTIFIER,
    DATE,
    AMOUNT,
    CROSS_DOCUMENT,
}

data class SyntheticCalibrationCase(
    val caseId: String,
    val category: CalibrationCategory,
    val question: String,
    val relevantChunkIds: Set<Long>,
)

data class SyntheticCalibrationDocument(
    val chunkId: Long,
    val documentId: String,
    val displayName: String,
    val department: String,
    val topic: String,
    val identifier: String,
    val effectiveDate: String,
    val amount: Int,
    val rule: String,
    val text: String,
) {
    val chunk: ChunkEntity
        get() = ChunkEntity(
            id = chunkId,
            documentId = documentId,
            knowledgeBaseId = SyntheticOfficeCalibrationCorpus.KNOWLEDGE_BASE_ID,
            ordinal = 0,
            text = text,
            searchText = CjkBigramEncoder.encode(text),
            displayName = displayName,
            locatorType = "line",
            locatorValue = "1",
            tokenCount = text.codePointCount(0, text.length).coerceAtLeast(1),
            contentSha256 = chunkId.toString(16).padStart(64, '0'),
            embeddingState = ChunkEntity.EMBEDDING_READY,
        )
}

data class SyntheticCalibrationCorpus(
    val documents: List<SyntheticCalibrationDocument>,
    val cases: List<SyntheticCalibrationCase>,
)

object SyntheticOfficeCalibrationCorpus {
    const val KNOWLEDGE_BASE_ID = "kb-calibration-v1"

    fun build(): SyntheticCalibrationCorpus {
        require(DEPARTMENTS.size == DOCUMENT_COUNT)
        require(TOPICS.size == DOCUMENT_COUNT)
        require(RULES.size == DOCUMENT_COUNT)
        require(GREETINGS.size == DOCUMENT_COUNT)
        val documents = List(DOCUMENT_COUNT, ::document)
        val cases = documents.flatMapIndexed { index, current ->
            val next = documents[(index + 1) % documents.size]
            casesFor(index, current, next)
        }
        require(documents.map { it.chunkId }.distinct().size == DOCUMENT_COUNT)
        require(cases.size == DOCUMENT_COUNT * CalibrationCategory.entries.size)
        require(cases.map { it.caseId }.distinct().size == cases.size)
        require(cases.all { it.question.isNotBlank() && it.question.codePointCount(0, it.question.length) <= 512 })
        require(cases.all { it.relevantChunkIds.all(documents.map { document -> document.chunkId }.toSet()::contains) })
        return SyntheticCalibrationCorpus(documents, cases)
    }

    private fun document(index: Int): SyntheticCalibrationDocument {
        val ordinal = index + 1
        val department = DEPARTMENTS[index]
        val topic = TOPICS[index]
        val identifier = "SYN-%02d-%04d".format(Locale.ROOT, ordinal, 2026 + index)
        val month = index / 28 + 1
        val day = index % 28 + 1
        val effectiveDate = "2026-%02d-%02d".format(Locale.ROOT, month, day)
        val amount = 137 + index * 41
        val rule = RULES[index]
        val text = if (index < CHINESE_DOCUMENTS) {
            "$department 的《$topic》是纯合成测试规章。合成编号为 $identifier，生效日期为 $effectiveDate，" +
                "单笔上限为 $amount 元。核心要求：$rule。"
        } else {
            "This is a synthetic $topic rule for $department. Its synthetic identifier is $identifier, " +
                "effective date is $effectiveDate, and per-item limit is $amount yuan. Core requirement: $rule."
        }
        return SyntheticCalibrationDocument(
            chunkId = 10_001L + index,
            documentId = "synthetic-doc-%02d".format(Locale.ROOT, ordinal),
            displayName = "synthetic-policy-%02d.txt".format(Locale.ROOT, ordinal),
            department = department,
            topic = topic,
            identifier = identifier,
            effectiveDate = effectiveDate,
            amount = amount,
            rule = rule,
            text = text,
        )
    }

    private fun casesFor(
        index: Int,
        current: SyntheticCalibrationDocument,
        next: SyntheticCalibrationDocument,
    ): List<SyntheticCalibrationCase> {
        val ordinal = index + 1
        val prefix = "cal-v1-%02d".format(Locale.ROOT, ordinal)
        val Chinese = index < CHINESE_DOCUMENTS
        fun case(
            category: CalibrationCategory,
            question: String,
            relevant: Set<Long>,
        ) = SyntheticCalibrationCase(
            caseId = "$prefix-${category.name.lowercase(Locale.ROOT)}",
            category = category,
            question = question,
            relevantChunkIds = relevant,
        )

        return listOf(
            case(
                CalibrationCategory.RELEVANT,
                if (Chinese) {
                    "根据知识库，${current.department}的${current.topic}核心要求是什么？"
                } else {
                    "What is the core requirement of ${current.department}'s ${current.topic} rule?"
                },
                setOf(current.chunkId),
            ),
            case(
                CalibrationCategory.SIMILAR_BUT_WRONG,
                if (Chinese) {
                    "${current.department}的${current.topic}在火星办事处的例外审批人是谁？"
                } else {
                    "Who approves the Mars-office exception under ${current.department}'s ${current.topic} rule?"
                },
                emptySet(),
            ),
            case(
                CalibrationCategory.UNRELATED,
                if (Chinese) {
                    "知识库是否说明量子卫星轨道姿态校准的实验步骤？"
                } else {
                    "Does the knowledge base explain quantum-satellite orbital attitude calibration?"
                },
                emptySet(),
            ),
            case(CalibrationCategory.GREETING, GREETINGS[index], emptySet()),
            case(
                CalibrationCategory.IDENTIFIER,
                if (Chinese) {
                    "合成编号${current.identifier}对应的核心要求是什么？"
                } else {
                    "What core requirement belongs to synthetic identifier ${current.identifier}?"
                },
                setOf(current.chunkId),
            ),
            case(
                CalibrationCategory.DATE,
                if (Chinese) {
                    "哪项合成规定在${current.effectiveDate}生效，它的要求是什么？"
                } else {
                    "Which synthetic rule takes effect on ${current.effectiveDate}, and what does it require?"
                },
                setOf(current.chunkId),
            ),
            case(
                CalibrationCategory.AMOUNT,
                if (Chinese) {
                    "单笔上限${current.amount}元对应什么合成政策？"
                } else {
                    "Which synthetic policy has a per-item limit of ${current.amount} yuan?"
                },
                setOf(current.chunkId),
            ),
            case(
                CalibrationCategory.CROSS_DOCUMENT,
                if (Chinese) {
                    "比较${current.department}的${current.topic}与${next.department}的${next.topic}核心要求。"
                } else {
                    "Compare the core requirements of ${current.department}'s ${current.topic} and " +
                        "${next.department}'s ${next.topic}."
                },
                setOf(current.chunkId, next.chunkId),
            ),
        )
    }

    private const val DOCUMENT_COUNT = 40
    private const val CHINESE_DOCUMENTS = 20

    private val DEPARTMENTS = listOf(
        "行政支持部", "财务共享部", "采购运营部", "信息安全部", "人力资源部",
        "法务合规部", "客户成功部", "设施管理部", "研发质量部", "市场活动部",
        "供应链计划部", "数据治理部", "产品设计部", "售后服务部", "内部审计部",
        "项目交付部", "培训发展部", "品牌传播部", "商务拓展部", "风险控制部",
        "Operations Office", "Finance Operations", "Procurement Desk", "Security Office", "People Services",
        "Legal Operations", "Customer Care", "Facilities Team", "Quality Engineering", "Events Team",
        "Supply Planning", "Data Stewardship", "Product Studio", "Field Support", "Internal Controls",
        "Delivery Office", "Learning Team", "Communications Desk", "Business Programs", "Risk Office",
    )

    private val TOPICS = listOf(
        "差旅餐费管理", "临时备用金管理", "供应商样品登记", "访客设备接入", "远程办公设备借用",
        "合同印章申请", "客户回访记录", "会议室节能", "缺陷复盘归档", "展会物料运输",
        "紧急备件调拨", "数据字典变更", "原型机外借", "现场工单升级", "审计证据留存",
        "项目里程碑验收", "外部课程报销", "新闻稿校对", "合作伙伴准入", "异常交易复核",
        "Meal Reimbursement", "Petty Cash", "Supplier Sample Logging", "Guest Device Access", "Remote Equipment Loan",
        "Contract Seal Request", "Customer Follow-up", "Meeting Room Energy", "Defect Review Archive", "Event Material Shipping",
        "Emergency Spare Transfer", "Data Dictionary Change", "Prototype Checkout", "Field Ticket Escalation", "Audit Evidence Retention",
        "Milestone Acceptance", "External Course Expense", "Press Release Review", "Partner Onboarding", "Transaction Exception Review",
    )

    private val RULES = listOf(
        "报销申请必须附行程日期和逐项票据", "领用人须在五个工作日内核销余额", "样品入库前必须记录批次和保管人",
        "访客终端只能连接隔离网络且当日失效", "借用设备归还时必须完成数据清除确认", "用印前必须完成合同编号与授权人复核",
        "回访记录应在二十四小时内写入客户档案", "最后离开会议室的人负责关闭非必要电源", "复盘必须关联缺陷编号和验证结论",
        "运输清单须由活动负责人和仓库共同确认", "调拨前必须核对目标仓库和备件序列号", "字段变更必须附影响范围和回滚说明",
        "外借前必须拍摄设备状态并登记归还日期", "连续两次未解决的工单应升级到值班经理", "证据副本必须标注来源日期和保存责任人",
        "验收记录必须包含交付物清单和双方签字", "报销前必须提交课程完成证明和付款凭证", "发布前必须完成事实核验和法务复核",
        "准入前必须完成制裁筛查和受益所有人确认", "复核人员不得与原交易审批人为同一人",
        "Attach travel dates and itemized receipts to every claim", "Reconcile the remaining balance within five business days",
        "Record the batch and custodian before accepting a sample", "Connect guest devices only to the isolated network for one day",
        "Confirm data erasure when returning borrowed equipment", "Verify the contract ID and authorizer before applying the seal",
        "Write each follow-up into the customer record within twenty-four hours", "The last person leaving must switch off nonessential power",
        "Link every review to a defect ID and verification conclusion", "Have the event owner and warehouse confirm the shipping list",
        "Verify the destination warehouse and spare serial number before transfer", "Include impact scope and rollback notes with field changes",
        "Photograph device condition and record a return date before checkout", "Escalate a ticket after two unsuccessful resolution attempts",
        "Label each evidence copy with source date and accountable custodian", "Include a deliverable list and both parties' signatures",
        "Submit course completion proof and payment evidence", "Complete fact checking and legal review before publication",
        "Complete sanctions screening and beneficial-owner verification", "Assign a reviewer different from the original approver",
    )

    private val GREETINGS = listOf(
        "你好，很高兴见到你", "早上好，今天怎么样", "下午好，可以聊聊天吗", "晚上好，辛苦了", "嗨，你在吗",
        "谢谢你的帮助", "周末愉快", "祝你今天顺利", "你好呀，先打个招呼", "最近怎么样",
        "很高兴再次见面", "早安，希望你状态不错", "午安，来问候一下", "晚安，明天见", "嗨，今天心情好吗",
        "感谢你一直在线", "你好，我们随便聊聊", "祝你有美好的一天", "见到你真好", "先说一声你好",
        "Hello, nice to meet you", "Good morning, how are you", "Good afternoon, can we chat", "Good evening, hope all is well",
        "Hi there, are you around", "Thanks for your help", "Have a pleasant weekend", "Hope your day goes smoothly",
        "Hello again, just saying hi", "How have you been lately", "It is good to see you again", "Morning, hope you are doing well",
        "Good afternoon, just checking in", "Good night and see you tomorrow", "Hi, how is your day going",
        "Thank you for being here", "Hello, let us have a casual chat", "Wishing you a wonderful day",
        "Great to see you", "Just wanted to say hello",
    )
}
