import Foundation

/// How long one recording may run before the keyboard stops it by itself.
///
/// This is a cost guard, not a feature. OpenAI bills per committed second, so a
/// dictation the user forgot to stop — text delivered, message sent, keyboard
/// left recording — is an unbounded charge for nothing. When the ceiling
/// elapses the phrase is finished exactly as a tap on the stop square finishes
/// it: the buffer is committed, the transcript arrives and is inserted. It is a
/// completion, never a cancellation, so nothing the user said is lost.
///
/// 1:1 port of `android/.../config/RecordingLimit.kt`.
enum RecordingLimit {
    static let minMinutes = 1
    static let maxMinutes = 20

    /// Long enough for a paragraph of thinking out loud, short enough that an
    /// abandoned recording costs cents rather than dollars.
    static let defaultMinutes = 3

    /// Every value the dropdown offers, in the order it shows them.
    static let options: [Int] = Array(minMinutes...maxMinutes)

    /// A stored value outside the range falls back to [defaultMinutes].
    static func from(_ minutes: Int) -> Int {
        (minMinutes...maxMinutes).contains(minutes) ? minutes : defaultMinutes
    }

    static func defaultMinutesValue() -> Int { defaultMinutes }

    /// The ceiling as a duration, clamped through [from] first.
    static func millis(for minutes: Int) -> TimeInterval {
        TimeInterval(from(minutes)) * 60
    }
}
