// Shared input rules for every typed intent. Kept in one place so a second
// intent cannot quietly be laxer than the first — the whole point of layer 5 is
// that the client's reach is identical no matter which route it knocks on.

export const CATEGORIES = [
  "CLIMATE", "KITCHEN", "LAUNDRY", "ENTERTAINMENT",
  "COMPUTING", "LIGHTING", "WATER", "PERSONAL_CARE", "OTHER",
];

export const MAX_TEXT = 60;

// Letters, digits and the punctuation real product names carry. No control
// characters and no line breaks: a newline is how you start writing a second
// instruction inside a field that is supposed to hold a name.
const SAFE_TEXT = /^[\p{L}\p{N} .,\-+/&()']{1,60}$/u;

export function badRequest(field, reason) {
  return { ok: false, error: "invalid_input", field, reason };
}

/** Returns the trimmed string, or a rejection describing what was wrong. */
export function safeText(value, field) {
  if (typeof value !== "string") return badRequest(field, "expected_string");
  const trimmed = value.trim();
  if (trimmed.length === 0) return badRequest(field, "empty");
  if (trimmed.length > MAX_TEXT) return badRequest(field, "too_long");
  if (!SAFE_TEXT.test(trimmed)) return badRequest(field, "unsupported_characters");
  return { ok: true, value: trimmed };
}

export function positiveNumber(value, field, { max = Number.MAX_SAFE_INTEGER } = {}) {
  if (typeof value !== "number" || !Number.isFinite(value)) return badRequest(field, "expected_number");
  if (value <= 0) return badRequest(field, "not_positive");
  if (value > max) return badRequest(field, "out_of_range");
  return { ok: true, value };
}
