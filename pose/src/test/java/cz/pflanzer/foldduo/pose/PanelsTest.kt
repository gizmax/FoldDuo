package cz.pflanzer.foldduo.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelsTest {
    @Test fun fold8ExactSizes() {
        assertEquals(Panel.Cover, Panels.classify(1248, 1972))
        assertEquals(Panel.Cover, Panels.classify(1972, 1248))
        assertEquals(Panel.Inner, Panels.classify(2448, 1848))
        assertEquals(Panel.Inner, Panels.classify(1848, 2448))
    }
    @Test fun aspectFallback() {
        assertEquals(Panel.Inner, Panels.classify(2000, 1800))
        assertEquals(Panel.Cover, Panels.classify(1080, 2400))
        assertEquals(Panel.Unknown, Panels.classify(0, 10))
    }
}
