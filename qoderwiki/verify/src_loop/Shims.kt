// JVM shims for InterruptedTailDetectorTest. ONLY these two types are stubbed;
// InterruptedTailDetector.kt / AgentContentPart.kt / LLMMessage.kt /
// InterruptedTailDetectorTest.kt are byte-for-byte copies of the repo sources.
package com.openminis.app.data.model

// Real LLMUsage additionally carries @Serializable (kotlinx.serialization is not
// on the JVM bench classpath). Fields are identical to the repo declaration.
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    val latestContextTokens: Int = 0,
)