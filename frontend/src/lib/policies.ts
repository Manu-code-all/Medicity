/**
 * Facts the legal pages and the sign-up forms share. Every value marked
 * placeholder is something a person has to fill in before real patients rely
 * on these pages; they are in one place so none is forgotten.
 */

/**
 * The versions of the texts people agree to. They must match the API's
 * `medicity.privacy.*-version` settings: the API decides who is asked to agree
 * again, and these are what the pages print as "Version". Change both together.
 */
export const POLICY_VERSIONS = {
  terms: "2026-10-draft1",
  privacy: "2026-10-draft1",
  healthData: "2026-10-draft1",
} as const;

/** PLACEHOLDER: the legal entity that runs Medicity (the "data fiduciary" under the DPDP Act). */
export const OPERATOR_NAME = "[Operator name and registered address: to be filled in]";

/** PLACEHOLDER: the person the DPDP Act and Google Play expect people to be able to reach. */
export const GRIEVANCE_OFFICER = {
  name: "[Grievance Officer: name to be appointed]",
  email: "grievance@medicity.example",
  address: "[Postal address: to be filled in]",
  /** How long the officer commits to take to answer. A placeholder: the law sets its own limits. */
  replyWithin: "[30] days",
} as const;

export const LAST_UPDATED = "11 October 2026";
