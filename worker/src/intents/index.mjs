import * as identifyAppliance from "./identify-appliance.mjs";
import * as suggestSavings from "./suggest-savings.mjs";

// The complete list of things this Worker will ask a model to do. A route that
// is not in here does not exist, which is what makes "the client never sends a
// prompt" enforceable rather than aspirational.
export const INTENTS = {
  "identify-appliance": identifyAppliance,
  "suggest-savings": suggestSavings,
};
