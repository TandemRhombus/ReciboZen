// Layers 3, 4 and 8 of docs/PLAN_IA.md as pure functions: no Durable Object, no
// storage, no clock of their own. Every decision is (state, request) -> (state,
// verdict), which is what makes it testable without a Workers runtime.

export const OPERATIONS = ["identify-appliance", "suggest-savings", "explain-bill"];

// From docs/PLAN_IA.md §3. Generous on purpose: a normal household does not
// reach 20 identifications a month, and whoever reaches 150 is the best
// customer, not the problem.
export const MONTHLY_QUOTAS = {
  PLUS: { "identify-appliance": 150, "suggest-savings": 30, "explain-bill": 80 },
  FAMILY: { "identify-appliance": 400, "suggest-savings": 80, "explain-bill": 200 },
};

export function monthKey(epochSeconds) {
  const date = new Date(epochSeconds * 1000);
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
}

export function nextMonthStart(epochSeconds) {
  const date = new Date(epochSeconds * 1000);
  return Math.floor(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1) / 1000);
}

export function emptySubjectState(month) {
  return { month, counts: {}, tokensIn: 0, tokensOut: 0, costUsd: 0, recent: [] };
}

// A month boundary resets counters rather than deleting them, so a subject that
// goes quiet for a year and comes back is treated as new without any sweeper
// job having to exist.
function rolledOver(state, month) {
  return !state || state.month !== month ? emptySubjectState(month) : state;
}

// Layer 6. A monthly quota does nothing against a loop: 150 calls spread over a
// month and 150 calls fired in ninety seconds look identical to it. These two
// windows are what stand between a runaway client and the global ceiling.
//
// Sized for a person, not a script. Adding a whole household is twenty or
// thirty appliances; at six a minute that is a five-minute sitting, which is
// slower than anyone types and far faster than anyone needs.
export const RATE_LIMITS = [
  { windowSeconds: 60, maxCalls: 6, name: "burst" },
  { windowSeconds: 3600, maxCalls: 40, name: "sustained" },
];

/**
 * Exact sliding window rather than a token bucket, because the timestamps it
 * keeps are the same ones needed to tell the caller precisely when to come
 * back. At forty entries the list is smaller than the state around it.
 */
export function checkRateLimit(recent, { nowEpochSeconds, limits = RATE_LIMITS }) {
  const longest = Math.max(...limits.map((limit) => limit.windowSeconds));
  // Anything older than the widest window can never matter again. Pruning here
  // is what stops the list from growing without bound.
  const kept = (recent ?? []).filter((at) => nowEpochSeconds - at < longest);

  for (const { windowSeconds, maxCalls, name } of limits) {
    const inWindow = kept.filter((at) => nowEpochSeconds - at < windowSeconds);
    if (inWindow.length >= maxCalls) {
      const oldest = Math.min(...inWindow);
      // No clamp needed: anything still in the window satisfies
      // now - at < windowSeconds, so at + windowSeconds - now is at least 1.
      // A floor here would be unreachable code pretending to be a safeguard.
      return {
        allowed: false,
        reason: "rate_limited",
        window: name,
        retryAfterSeconds: oldest + windowSeconds - nowEpochSeconds,
        recent: kept,
      };
    }
  }
  return { allowed: true, recent: [...kept, nowEpochSeconds] };
}

export function checkSubjectQuota(state, { tier, operation, nowEpochSeconds }) {
  const limits = MONTHLY_QUOTAS[tier];
  if (!limits) return { allowed: false, reason: "unknown_tier", state };
  const limit = limits[operation];
  if (limit === undefined) return { allowed: false, reason: "unknown_operation", state };

  const month = monthKey(nowEpochSeconds);
  const current = rolledOver(state, month);
  const used = current.counts[operation] ?? 0;
  const resetsAtEpochSeconds = nextMonthStart(nowEpochSeconds);

  if (used >= limit) {
    return { allowed: false, reason: "quota_exhausted", state: current, used, limit, resetsAtEpochSeconds };
  }
  // The counter moves BEFORE the model is called. A crash mid-call costs the
  // user one unit of quota; the alternative costs money on every failed call
  // and leaves a window where concurrent requests all see the old count.
  const next = { ...current, counts: { ...current.counts, [operation]: used + 1 } };
  return { allowed: true, state: next, used: used + 1, limit, resetsAtEpochSeconds };
}

export function recordSubjectUsage(state, { tokensIn, tokensOut, costUsd, nowEpochSeconds }) {
  const current = rolledOver(state, monthKey(nowEpochSeconds));
  return {
    ...current,
    tokensIn: current.tokensIn + tokensIn,
    tokensOut: current.tokensOut + tokensOut,
    costUsd: round6(current.costUsd + costUsd),
  };
}

// Layer 4. Per-subject quotas do nothing against 5,000 fake accounts, so a
// single global counter decides when the AI switches off for everyone.
export function emptyGlobalState(month) {
  return { month, costUsd: 0, calls: 0 };
}

export function checkGlobalCeiling(state, { ceilingUsd, nowEpochSeconds }) {
  const month = monthKey(nowEpochSeconds);
  const current = !state || state.month !== month ? emptyGlobalState(month) : state;
  const resetsAtEpochSeconds = nextMonthStart(nowEpochSeconds);
  if (!(ceilingUsd > 0)) {
    return { allowed: false, reason: "ceiling_not_configured", state: current, spentUsd: current.costUsd };
  }
  if (current.costUsd >= ceilingUsd) {
    return { allowed: false, reason: "global_ceiling_reached", state: current, spentUsd: current.costUsd, ceilingUsd, resetsAtEpochSeconds };
  }
  return { allowed: true, state: current, spentUsd: current.costUsd, ceilingUsd, resetsAtEpochSeconds };
}

export function recordGlobalSpend(state, { costUsd, nowEpochSeconds }) {
  const month = monthKey(nowEpochSeconds);
  const current = !state || state.month !== month ? emptyGlobalState(month) : state;
  return { ...current, costUsd: round6(current.costUsd + costUsd), calls: current.calls + 1 };
}

// Spend accumulates in fractions of a cent across thousands of calls. Rounding
// each addition keeps the float from drifting into a number that reads wrong on
// a dashboard, without ever rounding a real cost away.
function round6(value) {
  return Math.round(value * 1e6) / 1e6;
}
