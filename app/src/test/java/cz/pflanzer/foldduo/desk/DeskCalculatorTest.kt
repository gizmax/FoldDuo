package cz.pflanzer.foldduo.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskCalculatorTest {
    private fun value(expr: String): Double = (DeskCalculator.evaluate(expr) as DeskCalculator.Result.Value).value

    @Test fun basicArithmetic() {
        assertEquals(5.0, value("2+3"), 0.0)
        assertEquals(-1.0, value("2-3"), 0.0)
        assertEquals(6.0, value("2*3"), 0.0)
        assertEquals(2.0, value("6/3"), 0.0)
    }

    @Test fun multiplyAndDivideBindTighterThanAddAndSubtract() {
        assertEquals(14.0, value("2+3*4"), 0.0)
        assertEquals(11.0, value("20-3*3"), 0.0)
        assertEquals(4.0, value("2+4/2"), 0.0)
    }

    @Test fun moduloBindsAtTheSamePrecedenceAsMultiplyDivide() {
        assertEquals(4.0, value("1+8%5"), 0.0) // 8%5=3, then 1+3
        assertEquals(3.0, value("8%5"), 0.0)
    }

    @Test fun parenthesesOverridePrecedence() {
        assertEquals(20.0, value("(2+3)*4"), 0.0)
    }

    @Test fun unaryMinus() {
        assertEquals(-4.0, value("-2*2"), 0.0)
        assertEquals(-2.0, value("-2"), 0.0)
        assertEquals(2.0, value("--2"), 0.0)
    }

    @Test fun acceptsAlternateOperatorGlyphs() {
        assertEquals(6.0, value("2×3"), 0.0)
        assertEquals(2.0, value("6÷3"), 0.0)
    }

    @Test fun divisionByZeroIsAnError() {
        val result = DeskCalculator.evaluate("5/0")
        assertTrue(result is DeskCalculator.Result.Error)
        assertEquals(DeskCalculator.CalcError.DivisionByZero, (result as DeskCalculator.Result.Error).reason)
    }

    @Test fun moduloByZeroIsAnError() {
        val result = DeskCalculator.evaluate("5%0")
        assertEquals(DeskCalculator.CalcError.ModuloByZero, (result as DeskCalculator.Result.Error).reason)
    }

    @Test fun blankOrGarbageIsASyntaxError() {
        assertTrue(DeskCalculator.evaluate("") is DeskCalculator.Result.Error)
        assertTrue(DeskCalculator.evaluate("2+") is DeskCalculator.Result.Error)
        assertTrue(DeskCalculator.evaluate("2+*3") is DeskCalculator.Result.Error)
        assertTrue(DeskCalculator.evaluate("(2+3") is DeskCalculator.Result.Error)
    }

    @Test fun decimalNumbers() = assertEquals(1.5, value("1.0+0.5"), 1e-9)

    // --- history ---

    @Test fun historyKeepsNewestFirstCappedAtThree() {
        var history = emptyList<DeskCalcEntry>()
        history = DeskCalculatorHistory.push(history, DeskCalcEntry("1+1", 2.0))
        history = DeskCalculatorHistory.push(history, DeskCalcEntry("2+2", 4.0))
        history = DeskCalculatorHistory.push(history, DeskCalcEntry("3+3", 6.0))
        history = DeskCalculatorHistory.push(history, DeskCalcEntry("4+4", 8.0))
        assertEquals(3, history.size)
        assertEquals(listOf("4+4", "3+3", "2+2"), history.map { it.expression })
    }
}
