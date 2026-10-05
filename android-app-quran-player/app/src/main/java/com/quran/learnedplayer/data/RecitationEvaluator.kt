package com.quran.learnedplayer.data

enum class WordEvaluationStatus {
    PENDING,
    CORRECT,
    MISTAKE,
}

data class WordEvaluation(
    val wordIndex: Int,
    val originalWord: AyahWord,
    val status: WordEvaluationStatus = WordEvaluationStatus.PENDING,
    val spokenWord: String? = null,
    val feedback: String? = null,
)

data class RecitationResult(
    val evaluations: List<WordEvaluation>,
    val totalContentWords: Int,
    val correctCount: Int,
    val mistakeCount: Int,
    val accuracyPercentage: Float,
    val isComplete: Boolean,
    val firstMistake: WordEvaluation? = null,
)

object RecitationEvaluator {

    /**
     * Evaluates spoken Arabic transcript against reference Ayah content words.
     *
     * @param referenceWords The list of [AyahWord] for the target Ayah (including or excluding end glyphs).
     * @param spokenTranscript The recognized text string produced by the speech recognizer.
     */
    fun evaluate(
        referenceWords: List<AyahWord>,
        spokenTranscript: String,
        committed: Boolean = true,
    ): RecitationResult {
        // Filter out end-of-ayah number glyphs for word evaluation
        val contentWords = referenceWords.filter { !it.isEnd }
        if (contentWords.isEmpty()) {
            return RecitationResult(
                evaluations = emptyList(),
                totalContentWords = 0,
                correctCount = 0,
                mistakeCount = 0,
                accuracyPercentage = 100f,
                isComplete = false,
            )
        }

        val spokenTokens = ArabicTextNormalizer.tokenize(spokenTranscript)
        val evaluations = mutableListOf<WordEvaluation>()

        var spokenIndex = 0
        var correctCount = 0
        var mistakeCount = 0

        if (!committed) {
            // Partials: lock in a matching prefix only. Stop at the first unmatched token
            // so an in-progress last word never becomes a mistake.
            val prefixWordTexts = contentWords.map { it.text }
            var prefixBroken = false
            var i = 0
            while (i < contentWords.size) {
                val step = if (prefixBroken) {
                    null
                } else {
                    ArabicWordAligner.matchAt(prefixWordTexts, i, spokenTokens, spokenIndex)
                }
                if (step != null) {
                    val heard = spokenTokens
                        .subList(spokenIndex, spokenIndex + step.tokens)
                        .joinToString(" ")
                    for (offset in 0 until step.words) {
                        evaluations += WordEvaluation(
                            wordIndex = i + offset,
                            originalWord = contentWords[i + offset],
                            status = WordEvaluationStatus.CORRECT,
                            spokenWord = heard,
                        )
                        correctCount++
                    }
                    i += step.words
                    spokenIndex += step.tokens
                } else {
                    prefixBroken = true
                    evaluations += WordEvaluation(
                        wordIndex = i,
                        originalWord = contentWords[i],
                        status = WordEvaluationStatus.PENDING,
                    )
                    i++
                }
            }
            val totalWords = contentWords.size
            return RecitationResult(
                evaluations = evaluations,
                totalContentWords = totalWords,
                correctCount = correctCount,
                mistakeCount = 0,
                accuracyPercentage = if (totalWords > 0) {
                    (correctCount.toFloat() / totalWords * 100f).coerceIn(0f, 100f)
                } else 100f,
                isComplete = false,
            )
        }

        // A while loop, not `for (i in indices)`: one heard token can settle more than one written
        // word (and vice versa) — see [ArabicWordAligner] — so the step size is not always 1.
        val wordTexts = contentWords.map { it.text }
        var i = 0
        while (i < contentWords.size) {
            val targetWord = contentWords[i]

            if (spokenIndex < spokenTokens.size) {
                val spokenToken = spokenTokens[spokenIndex]
                val step = ArabicWordAligner.matchAt(wordTexts, i, spokenTokens, spokenIndex)

                if (step != null) {
                    val heard = spokenTokens
                        .subList(spokenIndex, spokenIndex + step.tokens)
                        .joinToString(" ")
                    for (offset in 0 until step.words) {
                        evaluations += WordEvaluation(
                            wordIndex = i + offset,
                            originalWord = contentWords[i + offset],
                            status = WordEvaluationStatus.CORRECT,
                            spokenWord = heard,
                        )
                        correctCount++
                    }
                    i += step.words
                    spokenIndex += step.tokens
                    continue
                }

                if (ArabicWordAligner.matchAt(wordTexts, i + 1, spokenTokens, spokenIndex) != null) {
                    evaluations += WordEvaluation(
                        wordIndex = i,
                        originalWord = targetWord,
                        status = WordEvaluationStatus.MISTAKE,
                        spokenWord = null,
                        feedback = "Skipped word '${targetWord.text}'",
                    )
                    mistakeCount++
                } else {
                    evaluations += WordEvaluation(
                        wordIndex = i,
                        originalWord = targetWord,
                        status = WordEvaluationStatus.MISTAKE,
                        spokenWord = spokenToken,
                        feedback = "Expected '${targetWord.text}', but heard '$spokenToken'",
                    )
                    mistakeCount++
                    spokenIndex++
                }
            } else {
                evaluations += WordEvaluation(
                    wordIndex = i,
                    originalWord = targetWord,
                    status = WordEvaluationStatus.PENDING,
                )
            }
            i++
        }

        val totalWords = contentWords.size
        val isComplete = evaluations.isNotEmpty() &&
            evaluations.all { it.status == WordEvaluationStatus.CORRECT }
        val accuracy = if (totalWords > 0) {
            (correctCount.toFloat() / totalWords * 100f).coerceIn(0f, 100f)
        } else 100f

        val firstMistake = evaluations.firstOrNull { it.status == WordEvaluationStatus.MISTAKE }

        return RecitationResult(
            evaluations = evaluations,
            totalContentWords = totalWords,
            correctCount = correctCount,
            mistakeCount = mistakeCount,
            accuracyPercentage = accuracy,
            isComplete = isComplete,
            firstMistake = firstMistake,
        )
    }

    /**
     * Streaming path: matching prefix is CORRECT; an in-progress last token that is still a
     * prefix of the expected word stays PENDING. A [lastTokenStable] token that is not a
     * prefix and not a match is a MISTAKE. Silence / unfinished words never correct the user.
     *
     * **Nothing calls this any more** — the live coach goes through [evaluateContinuing], which is
     * the only path with sticky-prefix and lookback behaviour. It also still walks one word to one
     * token, so it would score يَـٰٓأَيُّهَا heard as "يا ايها" as a mistake; wire it back in and it
     * needs [ArabicWordAligner] the way the other two paths have it.
     */
    fun evaluateStreaming(
        referenceWords: List<AyahWord>,
        spokenTranscript: String,
        lastTokenStable: Boolean,
    ): RecitationResult {
        val contentWords = referenceWords.filter { !it.isEnd }
        if (contentWords.isEmpty()) {
            return RecitationResult(
                evaluations = emptyList(),
                totalContentWords = 0,
                correctCount = 0,
                mistakeCount = 0,
                accuracyPercentage = 100f,
                isComplete = false,
            )
        }

        val spokenTokens = ArabicTextNormalizer.tokenize(spokenTranscript)
            .filter { it != "unk" && it != "[unk]" }
        val evaluations = mutableListOf<WordEvaluation>()
        var spokenIndex = 0
        var correctCount = 0
        var mistakeCount = 0
        var stopped = false

        for (i in contentWords.indices) {
            val targetWord = contentWords[i]
            if (stopped || spokenIndex >= spokenTokens.size) {
                evaluations += WordEvaluation(
                    wordIndex = i,
                    originalWord = targetWord,
                    status = WordEvaluationStatus.PENDING,
                )
                continue
            }
            val spokenToken = spokenTokens[spokenIndex]
            val isLastSpoken = spokenIndex == spokenTokens.lastIndex
            if (ArabicTextNormalizer.isWordMatch(targetWord.text, spokenToken)) {
                evaluations += WordEvaluation(
                    wordIndex = i,
                    originalWord = targetWord,
                    status = WordEvaluationStatus.CORRECT,
                    spokenWord = spokenToken,
                )
                correctCount++
                spokenIndex++
                continue
            }
            if (isLastSpoken && ArabicTextNormalizer.isIncompletePrefix(spokenToken, targetWord.text)) {
                evaluations += WordEvaluation(
                    wordIndex = i,
                    originalWord = targetWord,
                    status = WordEvaluationStatus.PENDING,
                )
                stopped = true
                continue
            }
            val nextTarget = contentWords.getOrNull(i + 1)
            if (nextTarget != null && ArabicTextNormalizer.isWordMatch(nextTarget.text, spokenToken)) {
                evaluations += WordEvaluation(
                    wordIndex = i,
                    originalWord = targetWord,
                    status = WordEvaluationStatus.MISTAKE,
                    spokenWord = null,
                    feedback = "Skipped word '${targetWord.text}'",
                )
                mistakeCount++
                stopped = true
                continue
            }
            if (isLastSpoken && !lastTokenStable) {
                evaluations += WordEvaluation(
                    wordIndex = i,
                    originalWord = targetWord,
                    status = WordEvaluationStatus.PENDING,
                )
                stopped = true
                continue
            }
            evaluations += WordEvaluation(
                wordIndex = i,
                originalWord = targetWord,
                status = WordEvaluationStatus.MISTAKE,
                spokenWord = spokenToken,
                feedback = "Expected '${targetWord.text}', but heard '$spokenToken'",
            )
            mistakeCount++
            spokenIndex++
            stopped = true
        }

        val totalWords = contentWords.size
        val isComplete = evaluations.isNotEmpty() &&
            evaluations.all { it.status == WordEvaluationStatus.CORRECT }
        val accuracy = if (totalWords > 0) {
            (correctCount.toFloat() / totalWords * 100f).coerceIn(0f, 100f)
        } else 100f
        val firstMistake = evaluations.firstOrNull { it.status == WordEvaluationStatus.MISTAKE }
        return RecitationResult(
            evaluations = evaluations,
            totalContentWords = totalWords,
            correctCount = correctCount,
            mistakeCount = mistakeCount,
            accuracyPercentage = accuracy,
            isComplete = isComplete,
            firstMistake = firstMistake,
        )
    }

    /**
     * Sticky-prefix path: words `[0, lockedCorrectCount)` stay CORRECT. New spoken tokens
     * may align at any index in `[0, lockedCorrectCount]` (lookback) or at a later unfinished
     * content word when the stream matches mid-ayah after a mic gap.
     *
     * @param allowSkipMistakes when false (live partials), a token that matches the *next*
     * word stays PENDING instead of flagging a skip — mic gaps must not interrupt the user.
     */
    fun evaluateContinuing(
        referenceWords: List<AyahWord>,
        spokenTranscript: String,
        lastTokenStable: Boolean,
        lockedCorrectCount: Int,
        allowSkipMistakes: Boolean = true,
    ): RecitationResult {
        val contentWords = referenceWords.filter { !it.isEnd }
        if (contentWords.isEmpty()) {
            return RecitationResult(
                evaluations = emptyList(),
                totalContentWords = 0,
                correctCount = 0,
                mistakeCount = 0,
                accuracyPercentage = 100f,
                isComplete = false,
            )
        }

        val locked = lockedCorrectCount.coerceIn(0, contentWords.size)
        // The only place intros are stripped. Callers pass the raw transcript: stripping here
        // is what lets the ayah's own opening veto the strip (Al-Fatihah 1:1 *is* the basmala).
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript(
            transcript = spokenTranscript,
            ayahOpening = RecitationIntroFilter.openingTokensOf(referenceWords),
        )
        val allTokens = ArabicTextNormalizer.tokenize(stripped)
            .filter { it != "unk" && it != "[unk]" }
        val newTokens = newAttemptTokens(contentWords, allTokens, locked)

        val sticky = Array(contentWords.size) { i ->
            WordEvaluation(
                wordIndex = i,
                originalWord = contentWords[i],
                status = if (i < locked) {
                    WordEvaluationStatus.CORRECT
                } else {
                    WordEvaluationStatus.PENDING
                },
            )
        }

        val wordTexts = contentWords.map { it.text }
        if (newTokens.isNotEmpty()) {
            var best: AlignWalk? = null
            // Lookback into sticky prefix, continue from lock, or sync to a later phrase
            // only when the spoken stream actually matches that later word.
            val alignStarts = (0..locked).toMutableList()
            val firstSpoken = newTokens.first()
            for (i in (locked + 1) until contentWords.size) {
                val later = contentWords[i].text
                if (ArabicWordAligner.matchAt(wordTexts, i, newTokens, 0) != null ||
                    ArabicTextNormalizer.isIncompletePrefix(firstSpoken, later)
                ) {
                    alignStarts += i
                }
            }
            for (alignStart in alignStarts) {
                val walk = walkAlign(
                    contentWords, wordTexts, newTokens, alignStart, locked,
                    lastTokenStable, allowSkipMistakes,
                ) ?: continue
                if (best == null || walk.betterThan(best)) best = walk
            }
            if (best != null) {
                for ((index, ev) in best.overlay) {
                    if (index >= locked) sticky[index] = ev
                }
            } else if (lastTokenStable && locked < contentWords.size) {
                val spoken = newTokens.first()
                val target = contentWords[locked]
                sticky[locked] = WordEvaluation(
                    wordIndex = locked,
                    originalWord = target,
                    status = WordEvaluationStatus.MISTAKE,
                    spokenWord = spoken,
                    feedback = "Expected '${target.text}', but heard '$spoken'",
                )
            }
        }

        return buildResult(sticky.toList())
    }

    private data class AlignWalk(
        val overlay: Map<Int, WordEvaluation>,
        val extendExclusive: Int,
        val hasMistake: Boolean,
        val alignStart: Int,
    ) {
        /** Words this walk actually settled. See [betterThan] for why it is not [extendExclusive]. */
        val correctCount: Int = overlay.count { it.value.status == WordEvaluationStatus.CORRECT }

        fun betterThan(other: AlignWalk): Boolean {
            // Prefer a clean sync over a longer mistaken walk (false mid-ayah starts).
            if (hasMistake != other.hasMistake) return !hasMistake
            // How many words it got right, before how far into the ayah it reached.
            // [extendExclusive] is an absolute word index, so a walk that starts late and matches
            // *nothing* still "reaches" further than one that starts at 0 and gets a dozen words
            // right: 12:87 opens يَـٰبَنِىَّ and the recognizer's "يا" is an incomplete prefix of
            // يَا۟يْـَٔسُ thirteen words later, so that empty walk was winning and the ayah scored
            // 0/20 on a near-perfect recitation.
            if (correctCount != other.correctCount) return correctCount > other.correctCount
            if (extendExclusive != other.extendExclusive) return extendExclusive > other.extendExclusive
            // Prefer continuing from the lock over restarting earlier when equal.
            return alignStart > other.alignStart
        }
    }

    private fun newAttemptTokens(
        contentWords: List<AyahWord>,
        allTokens: List<String>,
        locked: Int,
    ): List<String> {
        if (locked == 0) return allTokens
        // How many *tokens* the locked words took, which is not `locked` once a written word was
        // heard as two (يَـٰٓأَيُّهَا -> "يا ايها"). Dropping the wrong number here re-feeds the
        // already-scored opening as if it were a new attempt.
        val consumed = ArabicWordAligner.consumedByPrefix(contentWords.map { it.text }, locked, allTokens)
        if (consumed >= 0) return allTokens.drop(consumed)
        return allTokens
    }

    private fun walkAlign(
        contentWords: List<AyahWord>,
        wordTexts: List<String>,
        newTokens: List<String>,
        alignStart: Int,
        locked: Int,
        lastTokenStable: Boolean,
        allowSkipMistakes: Boolean,
    ): AlignWalk? {
        val overlay = mutableMapOf<Int, WordEvaluation>()
        var spokenIndex = 0
        var i = alignStart
        while (i < contentWords.size && spokenIndex < newTokens.size) {
            val token = newTokens[spokenIndex]
            val target = contentWords[i]
            val isLast = spokenIndex == newTokens.lastIndex
            val step = ArabicWordAligner.matchAt(wordTexts, i, newTokens, spokenIndex)
            if (step != null) {
                val heard = newTokens
                    .subList(spokenIndex, spokenIndex + step.tokens)
                    .joinToString(" ")
                for (offset in 0 until step.words) {
                    overlay[i + offset] = WordEvaluation(
                        wordIndex = i + offset,
                        originalWord = contentWords[i + offset],
                        status = WordEvaluationStatus.CORRECT,
                        spokenWord = heard,
                    )
                }
                spokenIndex += step.tokens
                i += step.words
                continue
            }
            if (isLast && ArabicTextNormalizer.isIncompletePrefix(token, target.text)) {
                if (i < locked) return AlignWalk(overlay, i, hasMistake = false, alignStart)
                overlay[i] = WordEvaluation(
                    wordIndex = i,
                    originalWord = target,
                    status = WordEvaluationStatus.PENDING,
                )
                return AlignWalk(overlay, i, hasMistake = false, alignStart)
            }
            if (ArabicWordAligner.matchAt(wordTexts, i + 1, newTokens, spokenIndex) != null) {
                if (i < locked) return null
                if (!allowSkipMistakes) {
                    // Live path: treat as possible mic gap / mid-ayah sync, not a skip mistake.
                    overlay[i] = WordEvaluation(
                        wordIndex = i,
                        originalWord = target,
                        status = WordEvaluationStatus.PENDING,
                    )
                    return AlignWalk(overlay, i, hasMistake = false, alignStart)
                }
                overlay[i] = WordEvaluation(
                    wordIndex = i,
                    originalWord = target,
                    status = WordEvaluationStatus.MISTAKE,
                    spokenWord = null,
                    feedback = "Skipped word '${target.text}'",
                )
                return AlignWalk(overlay, i, hasMistake = true, alignStart)
            }
            if (isLast && !lastTokenStable) {
                if (i < locked) return null
                overlay[i] = WordEvaluation(
                    wordIndex = i,
                    originalWord = target,
                    status = WordEvaluationStatus.PENDING,
                )
                return AlignWalk(overlay, i, hasMistake = false, alignStart)
            }
            if (i < locked) return null
            overlay[i] = WordEvaluation(
                wordIndex = i,
                originalWord = target,
                status = WordEvaluationStatus.MISTAKE,
                spokenWord = token,
                feedback = "Expected '${target.text}', but heard '$token'",
            )
            return AlignWalk(overlay, i, hasMistake = true, alignStart)
        }
        return AlignWalk(overlay, i, hasMistake = false, alignStart)
    }

    private fun buildResult(evaluations: List<WordEvaluation>): RecitationResult {
        val totalWords = evaluations.size
        val correctCount = evaluations.count { it.status == WordEvaluationStatus.CORRECT }
        val mistakeCount = evaluations.count { it.status == WordEvaluationStatus.MISTAKE }
        val isComplete = evaluations.isNotEmpty() &&
            evaluations.all { it.status == WordEvaluationStatus.CORRECT }
        val accuracy = if (totalWords > 0) {
            (correctCount.toFloat() / totalWords * 100f).coerceIn(0f, 100f)
        } else 100f
        return RecitationResult(
            evaluations = evaluations,
            totalContentWords = totalWords,
            correctCount = correctCount,
            mistakeCount = mistakeCount,
            accuracyPercentage = accuracy,
            isComplete = isComplete,
            firstMistake = evaluations.firstOrNull { it.status == WordEvaluationStatus.MISTAKE },
        )
    }
}
