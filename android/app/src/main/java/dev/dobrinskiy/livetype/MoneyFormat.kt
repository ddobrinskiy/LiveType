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

    /**
     * Three decimals is the user's preference: tenths of a cent are the finest
     * granularity worth reading, and four or more turned out to be noise.
     * Amounts below a tenth of a cent therefore round to `$0.000` rather than
     * growing more digits — see [fractionDigits].
     */
    private const val MAX_FRACTION_DIGITS = 3

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

        // Capping at three decimals means a real but tiny amount — a single
        // second of dictation is $0.000283 — would render as "$0.000" and read
        // as nothing spent. Say "less than the smallest amount we show"
        // instead: still three decimals, but not a claim of zero.
        if (usdMicros != 0L && amount.setScale(digits, RoundingMode.HALF_UP).signum() == 0) {
            val smallest = BigDecimal.ONE.movePointLeft(MAX_FRACTION_DIGITS)
            return "<" + format.format(smallest)
        }

        return format.format(amount)
    }

    /**
     * Cents by default, more only when cents would swallow the number: a day of
     * light dictation costs a few tenths of a cent, and showing that as `$0.00`
     * would be a lie the user is reading precisely to avoid.
     *
     * Two significant digits, floored at cents and capped at three decimals:
     * `$1.23`, `$0.117`, `$0.043`, `$0.003`. A true zero stays `$0.00`.
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
