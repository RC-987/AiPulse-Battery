// Shared time helpers for the battery UI / sync pipeline.
//
// IST (Asia/Kolkata) has a fixed UTC offset of +05:30 year-round — India
// does not observe daylight saving, so a plain numeric offset is safe and
// does not need the Intl / timezone database machinery.

// Offset of IST from UTC in milliseconds.
export const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000

/**
 * UTC epoch millisecond for the most recent midnight in IST (i.e. the
 * start of "today" as an IST calendar day). All App Usage totals reset at
 * this boundary so the list mirrors what a user perceives as "today".
 */
export function istMidnightMs(nowMs = Date.now()) {
  const istNow = nowMs + IST_OFFSET_MS
  const istMidnight = Math.floor(istNow / 86_400_000) * 86_400_000
  return istMidnight - IST_OFFSET_MS
}

/**
 * UTC epoch millisecond for the NEXT IST midnight after [nowMs]. Useful
 * for scheduling the daily-reset auto-refresh on the App Usage tab.
 */
export function nextIstMidnightMs(nowMs = Date.now()) {
  return istMidnightMs(nowMs) + 86_400_000
}
