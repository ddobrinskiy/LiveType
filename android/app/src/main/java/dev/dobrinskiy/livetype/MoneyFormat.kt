package dev.dobrinskiy.livetype

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Renders money the worker has already priced.
 *
 * This is formatting, not accounting: nothing here converts, discounts or
 * totals anything. It takes the integer micro-USD the worker sends and decides
 * how many digits tell the truth about it.
 */
object MoneyFormat {
    /** OpenAI bills in dollars, so the amount is USD wherever the phone is. */
    private const val CURRENCY = "USD"

    /** Micro-USD: six decimal places, which is also the finest the worker holds. */
    private const val MICRO_SCALE = 6

    private const val MIN_FRACTION_DIGITS = 2
    private const val MAX_FRACTION_DIGITS = MICRO_SCALE

    /**
     * Locale-aware, but always in USD: `getCurrencyInstance(locale)` alone
     * would print roubles on a Russian phone for a dollar amount.
     */
    fun usd(usdMicros: Long, locale: Locale = Locale.getDefault()): String {
        val amount = BigDecimal.valueOf(usdMicros, MICRO_SCALE)
        val digits = fractionDigits(amount)
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(CURRENCY)
            minimumFractionDigits = digits
            maximumFractionDigits = digits
            roundingMode = RoundingMode.HALF_UP
        }
        return format.format(amount)
    }

    /**
     * Cents by default, more only when cents would swallow the number: a day of
     * light dictation costs a few tenths of a cent, and showing that as `$0.00`
     * would be a lie the user is reading precisely to avoid.
     *
     * Two significant digits, floored at cents and capped at micro-dollars:
     * `$1.23`, `$0.117`, `$0.043`, `$0.000017`. A true zero stays `$0.00`.
     */
    private fun fractionDigits(amount: BigDecimal): Int {
        if (amount.signum() == 0) return MIN_FRACTION_DIGITS
        val magnitude = amount.abs()
        var digits = MIN_FRACTION_DIGITS
        while (
            digits < MAX_FRACTION_DIGITS &&
            magnitude < BigDecimal.ONE.movePointLeft(digits - 1)
        ) {
            digits += 1
        }
        return digits
    }
}
