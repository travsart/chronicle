package local.oss.chronicle.features.currentlyplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SliderSafetyHelper logic.
 * These tests verify that slider safety computations prevent IllegalStateException
 * when Material Slider's validateValues() is called.
 */
class CurrentlyPlayingBindingAdaptersTest {
    @Test
    fun `computeSafeValueTo returns minimum safe value when valueTo is zero`() {
        // Given: valueTo is 0 (invalid for slider)
        val valueTo = 0
        val valueFrom = 0f

        // When: computing safe valueTo
        val result = SliderSafetyHelper.computeSafeValueTo(valueTo, valueFrom)

        // Then: should return minimum safe value (100f) to prevent IllegalStateException
        assertEquals(100f, result, 0.001f)
    }

    @Test
    fun `computeSafeValueTo returns minimum safe value when valueTo is negative`() {
        // Given: valueTo is negative (invalid for slider)
        val valueTo = -10
        val valueFrom = 0f

        // When: computing safe valueTo
        val result = SliderSafetyHelper.computeSafeValueTo(valueTo, valueFrom)

        // Then: should return minimum safe value (100f) to prevent crash
        assertEquals(100f, result, 0.001f)
    }

    @Test
    fun `computeSafeValueTo returns minimum safe value when valueTo equals valueFrom`() {
        // Given: valueTo equals valueFrom (valueTo must be > valueFrom)
        val valueTo = 1
        val valueFrom = 1f

        // When: computing safe valueTo
        val result = SliderSafetyHelper.computeSafeValueTo(valueTo, valueFrom)

        // Then: should return minimum safe value to prevent IllegalStateException
        assertEquals(100f, result, 0.001f)
    }

    @Test
    fun `computeSafeValueTo returns given value when valid`() {
        // Given: valid valueTo > valueFrom
        val valueTo = 5000
        val valueFrom = 0f

        // When: computing safe valueTo
        val result = SliderSafetyHelper.computeSafeValueTo(valueTo, valueFrom)

        // Then: should return the original value (passthrough)
        assertEquals(5000f, result, 0.001f)
    }

    @Test
    fun `computeSafeValue clamps value to valueTo when value exceeds max`() {
        // Given: value exceeds valueTo (would cause IllegalStateException)
        val value = 1000
        val valueFrom = 0f
        val valueTo = 500f

        // When: computing safe value
        val result = SliderSafetyHelper.computeSafeValue(value, valueFrom, valueTo)

        // Then: should clamp to valueTo
        assertEquals(500f, result, 0.001f)
    }

    @Test
    fun `computeSafeValue clamps value to valueFrom when value is below min`() {
        // Given: value is below valueFrom (would cause IllegalStateException)
        val value = -10
        val valueFrom = 0f
        val valueTo = 1000f

        // When: computing safe value
        val result = SliderSafetyHelper.computeSafeValue(value, valueFrom, valueTo)

        // Then: should clamp to valueFrom
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `computeSafeValue returns value when within valid range`() {
        // Given: value is within valid range [valueFrom, valueTo]
        val value = 250
        val valueFrom = 0f
        val valueTo = 1000f

        // When: computing safe value
        val result = SliderSafetyHelper.computeSafeValue(value, valueFrom, valueTo)

        // Then: should return original value (passthrough)
        assertEquals(250f, result, 0.001f)
    }

    @Test
    fun `computeSafeValue handles edge case when value equals valueTo`() {
        // Given: value equals valueTo (edge case, should be valid)
        val value = 1000
        val valueFrom = 0f
        val valueTo = 1000f

        // When: computing safe value
        val result = SliderSafetyHelper.computeSafeValue(value, valueFrom, valueTo)

        // Then: should return value (no clamping needed)
        assertEquals(1000f, result, 0.001f)
    }

    @Test
    fun `computeSafeValue handles edge case when value equals valueFrom`() {
        // Given: value equals valueFrom (edge case, should be valid)
        val value = 0
        val valueFrom = 0f
        val valueTo = 1000f

        // When: computing safe value
        val result = SliderSafetyHelper.computeSafeValue(value, valueFrom, valueTo)

        // Then: should return value (no clamping needed)
        assertEquals(0f, result, 0.001f)
    }
}
