package cz.pflanzer.foldduo.desk

/**
 * B47 "Stůl": the deck's calculator card evaluator. A small recursive-descent parser over
 * `+ - × ÷ %`, `×`/`÷`/`%` binding tighter than `+`/`-` (usual arithmetic precedence), unary
 * minus, and parentheses. `%` is modulo (`a % b`), the same operator every other language in this
 * codebase uses, not a "percent of" postfix — a calculator card with a plain text expression has
 * no unambiguous "percent of what" base to reach for otherwise. Pure, no Android types, so this
 * runs as plain JUnit (DeskCalculatorTest).
 */
object DeskCalculator {
    sealed class Result {
        data class Value(val value: Double) : Result()
        data class Error(val reason: CalcError) : Result()
    }

    enum class CalcError { DivisionByZero, ModuloByZero, Syntax }

    /** Accepts `*`/`x`/`X`/`×` for multiply and `/`/`÷` for divide, so a caller can wire either a
     * text field or dedicated operator buttons without its own translation table. */
    fun evaluate(expression: String): Result {
        val normalized = expression.replace('×', '*').replace('x', '*').replace('X', '*').replace('÷', '/')
        if (normalized.isBlank()) return Result.Error(CalcError.Syntax)
        return try {
            val parser = Parser(normalized)
            val value = parser.parseExpression()
            parser.skipSpaces()
            if (!parser.atEnd()) Result.Error(CalcError.Syntax) else Result.Value(value)
        } catch (e: CalcException) {
            Result.Error(e.reason)
        }
    }

    private class CalcException(val reason: CalcError) : RuntimeException()

    /** `expr := term (('+'|'-') term)*`, `term := factor (('*'|'/'|'%') factor)*`,
     * `factor := number | '-' factor | '(' expr ')'`. */
    private class Parser(private val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length
        fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? { skipSpaces(); return s.getOrNull(i) }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { i++; value += parseTerm() }
                    '-' -> { i++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { i++; value *= parseFactor() }
                    '/' -> {
                        i++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw CalcException(CalcError.DivisionByZero)
                        value /= divisor
                    }
                    '%' -> {
                        i++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw CalcException(CalcError.ModuloByZero)
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            val c = peek() ?: throw CalcException(CalcError.Syntax)
            if (c == '-') { i++; return -parseFactor() }
            if (c == '+') { i++; return parseFactor() }
            if (c == '(') {
                i++
                val value = parseExpression()
                if (peek() != ')') throw CalcException(CalcError.Syntax)
                i++
                return value
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i == start) throw CalcException(CalcError.Syntax)
            return s.substring(start, i).toDoubleOrNull() ?: throw CalcException(CalcError.Syntax)
        }
    }
}

/** One evaluated line for the card's history row. */
data class DeskCalcEntry(val expression: String, val result: Double)

object DeskCalculatorHistory {
    /** "History of 3" (task spec, B47). */
    const val MAX = 3

    /** Newest first, capped to [MAX]. */
    fun push(history: List<DeskCalcEntry>, entry: DeskCalcEntry): List<DeskCalcEntry> =
        (listOf(entry) + history).take(MAX)
}
