import Foundation

/// Renders money the worker has already priced.
///
/// This is formatting, not accounting: nothing here converts, discounts or
/// totals anything. It takes the integer micro-USD the worker sends and decides
/// how many digits tell the truth about it.
///
/// 1:1 port of `android/.../MoneyFormat.kt`. `NumberFormatter` with a fixed
/// `currencyCode = "USD"` replaces `getCurrencyInstance(locale)` + `setCurrency`
/// so roubles never appear on a Russian phone for a dollar amount.
enum MoneyFormat {
    /// Micro-USD: six decimal places, which is also the finest the worker holds.
    private static let microScale = 1_000_000
    private static let minFractionDigits = 2

    /// Three decimals is the user's preference: tenths of a cent are the finest
    /// granularity worth reading, and four or more turned out to be noise.
    private static let maxFractionDigits = 3

    /// Locale-aware, but always in USD.
    static func usd(usdMicros: Int64, locale: Locale = .current) -> String {
        let amount = NSDecimalNumber(value: usdMicros).decimalValue / Decimal(microScale)
        let digits = fractionDigits(amount)

        // Capping at three decimals means a real but tiny amount — a single
        // second of dictation is $0.000283 — would render as "$0.000" and read
        // as nothing spent. Say "less than the smallest amount we show"
        // instead: still three decimals, but not a claim of zero.
        if usdMicros != 0 && rounded(amount, digits) == 0 {
            let smallest = Decimal(1) / pow(10, maxFractionDigits)
            return "<" + format(smallest, digits: maxFractionDigits, locale: locale)
        }

        return format(amount, digits: digits, locale: locale)
    }

    private static func format(_ amount: Decimal, digits: Int, locale: Locale) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "USD"
        formatter.locale = locale
        formatter.minimumFractionDigits = digits
        formatter.maximumFractionDigits = digits
        formatter.roundingMode = .halfUp
        return formatter.string(from: NSDecimalNumber(decimal: amount)) ?? "$0.00"
    }

    private static func rounded(_ amount: Decimal, _ digits: Int) -> Decimal {
        let value = NSDecimalNumber(decimal: amount)
        let handler = NSDecimalNumberHandler(
            roundingMode: .plain, scale: Int16(digits), raiseOnExactness: false,
            raiseOnOverflow: false, raiseOnUnderflow: false, raiseOnDivideByZero: false
        )
        return value.rounding(accordingToBehavior: handler).decimalValue
    }

    /// Cents by default, more only when cents would swallow the number. Two
    /// significant digits, floored at cents and capped at three decimals.
    private static func fractionDigits(_ amount: Decimal) -> Int {
        if amount == 0 { return minFractionDigits }
        let magnitude = abs(amount)
        var digits = minFractionDigits
        while digits < maxFractionDigits && magnitude < (Decimal(1) / pow(10, digits - 1)) {
            digits += 1
        }
        return digits
    }

    private static func pow(_ base: Int, _ exponent: Int) -> Decimal {
        var result: Decimal = 1
        for _ in 0..<exponent { result *= Decimal(base) }
        return result
    }
}
