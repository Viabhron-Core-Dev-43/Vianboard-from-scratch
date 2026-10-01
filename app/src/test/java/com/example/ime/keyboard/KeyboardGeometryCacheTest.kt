package com.example.ime.keyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KeyboardGeometryCacheTest {

    private lateinit var layout: KeyboardLayout
    private lateinit var theme: KeyboardTheme
    private val density = 2.0f
    private val width = 1080f
    private val height = 700f

    @Before
    fun setUp() {
        layout = KeyboardLayout()
        theme = KeyboardTheme(
            toolbarHeightDp = 40f,
            keyHeightDp = 48f,
            keyCornerRadiusDp = 10f,
            horizontalGapDp = 3f,
            verticalGapDp = 5f
        )
    }

    @Test
    fun testInitialLayoutCalculatesAndCachesGeometry() {
        assertNull(layout.cachedGeometry)

        val recalculated = layout.ensureLayout(width, height, theme, density)
        assertTrue("First layout call must calculate geometry", recalculated)
        assertNotNull(layout.cachedGeometry)

        val firstGeo = layout.cachedGeometry!!
        assertEquals(width, firstGeo.width, 0.001f)
        assertEquals(height, firstGeo.height, 0.001f)
        assertEquals(40f * density, firstGeo.toolbarBounds.bottom - firstGeo.toolbarBounds.top, 0.001f)
        assertEquals(36f * density, firstGeo.anchorKeyBounds.right - firstGeo.anchorKeyBounds.left, 0.001f)
        assertEquals(36f * density, firstGeo.anchorKeyBounds.bottom - firstGeo.anchorKeyBounds.top, 0.001f)
    }

    @Test
    fun testSubsequentLayoutWithSameDimensionsReusesCachedGeometry() {
        layout.ensureLayout(width, height, theme, density)
        val initialGeo = layout.cachedGeometry
        assertNotNull(initialGeo)

        val recalculated = layout.ensureLayout(width, height, theme, density)
        assertFalse("Subsequent layout with same dimensions must NOT recalculate geometry", recalculated)
        assertSame("Cached geometry reference must be identical", initialGeo, layout.cachedGeometry)
    }

    @Test
    fun testDimensionChangeTriggersGeometryRecalculation() {
        layout.ensureLayout(width, height, theme, density)
        val initialGeo = layout.cachedGeometry
        assertNotNull(initialGeo)

        val recalculated = layout.ensureLayout(width + 50f, height, theme, density)
        assertTrue("Width change must trigger geometry recalculation", recalculated)
        assertNotSame("New geometry object must be created on dimension change", initialGeo, layout.cachedGeometry)
    }

    @Test
    fun testModeChangeTriggersGeometryRecalculation() {
        layout.ensureLayout(width, height, theme, density)
        val initialGeo = layout.cachedGeometry
        assertNotNull(initialGeo)

        layout.mode = KeyboardMode.NUMPAD
        val recalculated = layout.ensureLayout(width, height, theme, density)
        assertTrue("Mode change must trigger geometry recalculation", recalculated)
        assertNotSame(initialGeo, layout.cachedGeometry)
    }

    @Test
    fun testShiftStateChangeDoesNotRecalculateGeometry() {
        layout.ensureLayout(width, height, theme, density)
        val initialGeo = layout.cachedGeometry
        assertNotNull(initialGeo)

        layout.shiftState = ShiftState.ON
        layout.updateShiftLabels()

        val recalculated = layout.ensureLayout(width, height, theme, density)
        assertFalse("Shift label update must NOT trigger geometry recalculation", recalculated)
        assertSame(initialGeo, layout.cachedGeometry)
    }

    @Test
    fun testSuggestionUpdateDoesNotRecalculateGeometry() {
        layout.ensureLayout(width, height, theme, density)
        val initialGeo = layout.cachedGeometry
        assertNotNull(initialGeo)

        layout.suggestions = listOf("hello", "world", "test")
        layout.syncSuggestionKeys()

        val recalculated = layout.ensureLayout(width, height, theme, density)
        assertFalse("Suggestion update must NOT trigger geometry recalculation", recalculated)
        assertSame(initialGeo, layout.cachedGeometry)

        val suggestionKeys = layout.toolbarKeys.filter { it.type == KeyType.SUGGESTION }
        assertEquals(3, suggestionKeys.size)
        assertEquals("hello", suggestionKeys[0].label)
        assertEquals("world", suggestionKeys[1].label)
        assertEquals("test", suggestionKeys[2].label)
    }

    @Test
    fun testHeliBoardGeometryReferenceValues() {
        layout.ensureLayout(width, height, theme, density)
        val geo = layout.cachedGeometry!!

        // 1. 40dp strip height anchored at top (y = 0)
        assertEquals(0f, geo.toolbarBounds.top, 0.001f)
        assertEquals(40f * density, geo.toolbarBounds.bottom, 0.001f)

        // 2. 36dp square anchor control vertically centered
        val expectedAnchorTop = (40f * density - 36f * density) / 2f
        assertEquals(expectedAnchorTop, geo.anchorKeyBounds.top, 0.001f)
        assertEquals(36f * density, geo.anchorKeyBounds.right - geo.anchorKeyBounds.left, 0.001f)
        assertEquals(36f * density, geo.anchorKeyBounds.bottom - geo.anchorKeyBounds.top, 0.001f)

        // 3. 3-Slot Suggestion Bar with center slot matching 36%
        assertEquals(3, geo.suggestionSlotBounds.size)
        val middleAreaWidth = geo.suggestionSlotBounds[2].right - geo.suggestionSlotBounds[0].left
        val centerSlotWidth = geo.suggestionSlotBounds[1].right - geo.suggestionSlotBounds[1].left
        val centerRatio = centerSlotWidth / middleAreaWidth
        assertEquals(0.36f, centerRatio, 0.01f)
    }

    @Test
    fun testDynamicModeRowCountsAndHeights() {
        assertEquals("QWERTY mode must have 5 rows", 5, layout.getRowCountForMode(KeyboardMode.CHARACTERS))
        assertEquals("Symbols 1 mode must have 5 rows", 5, layout.getRowCountForMode(KeyboardMode.SYMBOLS_1))
        assertEquals("Symbols 2 mode must have 5 rows", 5, layout.getRowCountForMode(KeyboardMode.SYMBOLS_2))
        assertEquals("Numpad mode must have 4 rows", 4, layout.getRowCountForMode(KeyboardMode.NUMPAD))
    }
}
