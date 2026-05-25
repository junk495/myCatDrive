package com.maisonsmd.catdrive.lib

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.text.getSpans
import timber.log.Timber

data class Span(
    val begin: Int,
    val end: Int,
    val text: String,
    val style: Int = Typeface.NORMAL, // style from Typeface
)

object ParserHelper {
    /**
     * NOTE: Currently only support one span type: StyleSpan
     */
    private fun findSpans(input: Spanned): ArrayList<Span> {
        val results = ArrayList<Span>()
        var spanBegin = 0
        var spanEnd = 0
        val len = input.length
        while (spanEnd < len) {
            spanEnd = input.nextSpanTransition(spanBegin, len, StyleSpan::class.java)
            val s = input.substring(spanBegin, spanEnd).trim()
            val spans = input.getSpans<StyleSpan>(spanBegin, spanEnd)
            if (spans.isNotEmpty())
                results.add(Span(spanBegin, spanEnd, s, spans.first().style))
            else
                results.add(Span(spanBegin, spanEnd, s))
            spanBegin = spanEnd
        }
        return results
    }

    data class SpanSplitResult(
        val text: String,
        val isKeySpan: Boolean
    )

    public fun splitByStyleSpan(input: Spanned, keyStyle: Int, minSpanLength: Int = 0): ArrayList<SpanSplitResult> {
        val result = ArrayList<SpanSplitResult>()
        val spans = findSpans(input)

        var begin: Int = 0
        var end: Int
        var previousSegmentMatched = false
        for (span in spans) {
            var segmentMatched = false
            val segment = input.substring(span.begin, span.end)
            if (span.style == keyStyle && (segment.trim().length >= minSpanLength)) {
                segmentMatched = true
            }

            if (segmentMatched != previousSegmentMatched) {
                end = span.begin
                val prevSegment = input.substring(begin, end).trim()
                if (prevSegment.isNotEmpty())
                    result.add(SpanSplitResult(prevSegment, previousSegmentMatched))
                begin = end
            }

            if (span == spans.last()) {
                end = span.end
                val prevSegment = input.substring(begin, end).trim()
                if (prevSegment.isNotEmpty())
                    result.add(SpanSplitResult(prevSegment, segmentMatched))
            }
            previousSegmentMatched = segmentMatched
        }

        // Timber.w(result.toString())
        return result
    }
}
