package com.example.minicpm_v_demo.rag.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagQueryRouterTest {
    private val router: RagQueryRouter = DefaultRagQueryRouter()

    @Test
    fun routesEverySyntheticRegressionCase() {
        val cases = loadCases()
        assertTrue("Route corpus must contain at least 120 cases", cases.size >= 120)

        val mismatches = cases.mapNotNull { case ->
            val actual = router.route(
                    RagRouteInput(
                        ragEnabled = true,
                        query = case.query,
                        knownDocumentNames = case.documentNames,
                    )
            )
            if (actual == case.expected) null
            else "${case.query}: expected=${case.expected}, actual=$actual"
        }
        assertTrue(
            "Unexpected routes:\n${mismatches.joinToString("\n")}",
            mismatches.isEmpty(),
        )
    }

    @Test
    fun disabledRagAlwaysPassesThroughWithoutInspectingAnchors() {
        assertEquals(
            RagQueryRoute.NO_RETRIEVAL,
            router.route(
                RagRouteInput(
                    ragEnabled = false,
                    query = "比较合同甲和合同乙的违约条款",
                    knownDocumentNames = listOf("合同甲", "合同乙"),
                )
            ),
        )
    }

    @Test
    fun socialPrefixCannotHideAKnowledgeBaseAnchor() {
        assertEquals(
            RagQueryRoute.SINGLE_RETRIEVAL,
            router.route(
                RagRouteInput(
                    ragEnabled = true,
                    query = "你好，请根据合同回答付款日期",
                    knownDocumentNames = emptyList(),
                )
            ),
        )
    }

    @Test
    fun normalizesFullWidthCharactersAndCollapsedWhitespace() {
        assertEquals(
            RagQueryRoute.SINGLE_RETRIEVAL,
            router.route(
                RagRouteInput(
                    ragEnabled = true,
                    query = "  请根据　知识库　回答第１条  ",
                    knownDocumentNames = emptyList(),
                )
            ),
        )
    }

    @Test
    fun socialAndSelfContainedPerturbationsStayOnTheZeroRetrievalPath() {
        val seeds = loadCases()
            .filter { it.expected == RagQueryRoute.NO_RETRIEVAL }
            .take(25)
            .map(RouteCase::query)
        val perturbations = seeds.flatMap { seed ->
            listOf(
                "  $seed  ",
                "$seed！",
                seed.uppercase(),
                seed.toFullWidthAscii(),
            )
        }
        assertEquals(100, perturbations.size)

        val falseRetrievals = perturbations.count { query ->
            router.route(RagRouteInput(true, query, emptyList())) != RagQueryRoute.NO_RETRIEVAL
        }
        assertTrue(
            "False retrieval rate exceeded 1%: $falseRetrievals / ${perturbations.size}",
            falseRetrievals <= 1,
        )
    }

    private fun loadCases(): List<RouteCase> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/rag/route_cases.tsv"))
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1)
                .filter(String::isNotBlank)
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size in 2..3) { "Invalid route case: $line" }
                    RouteCase(
                        expected = RagQueryRoute.valueOf(columns[0]),
                        query = columns[1],
                        documentNames = columns.getOrNull(2)
                            ?.split('|')
                            ?.filter(String::isNotBlank)
                            .orEmpty(),
                    )
                }
                .toList()
        }
    }

    private data class RouteCase(
        val expected: RagQueryRoute,
        val query: String,
        val documentNames: List<String>,
    )

    private fun String.toFullWidthAscii(): String = buildString(length) {
        this@toFullWidthAscii.forEach { character ->
            append(
                when (character.code) {
                    0x20 -> '\u3000'
                    in 0x21..0x7e -> (character.code + 0xfee0).toChar()
                    else -> character
                }
            )
        }
    }
}
