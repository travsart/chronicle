package local.oss.chronicle.features.currentlyplaying

/**
 * Pure utility functions for safely computing Slider values.
 * This object contains no Android dependencies and can be unit tested.
 *
 * Material Slider's validateValues() throws IllegalStateException if:
 * - valueFrom >= valueTo
 * - value < valueFrom
 * - value > valueTo
 *
 * These functions ensure all constraints are met.
 */
object SliderSafetyHelper {

    /**
     * Minimum safe value for valueTo to prevent IllegalStateException.
     * Must be greater than the default valueFrom of 0.
     */
    private const val MIN_SAFE_VALUE_TO = 100f

    /**
     * Computes a safe valueTo for a Slider.
     * Ensures valueTo is always greater than valueFrom to prevent IllegalStateException.
     *
     * @param valueTo The desired maximum value for the slider
     * @param valueFrom The minimum value for the slider (defaults to 0f)
     * @return A safe valueTo that is guaranteed to be > valueFrom
     */
    fun computeSafeValueTo(valueTo: Int, valueFrom: Float = 0f): Float {
        val valueToFloat = valueTo.toFloat()
        return if (valueToFloat <= valueFrom) {
            MIN_SAFE_VALUE_TO
        } else {
            valueToFloat
        }
    }

    /**
     * Computes a safe value for a Slider, clamping it within valid range.
     * Ensures the value is between [valueFrom, valueTo] to prevent IllegalStateException.
     *
     * @param value The desired slider value
     * @param valueFrom The minimum value for the slider
     * @param valueTo The maximum value for the slider
     * @return A safe value clamped within [valueFrom, valueTo]
     */
    fun computeSafeValue(value: Int, valueFrom: Float, valueTo: Float): Float {
        val valueFloat = value.toFloat()
        return when {
            valueFloat < valueFrom -> valueFrom
            valueFloat > valueTo -> valueTo
            else -> valueFloat
        }
    }
}
