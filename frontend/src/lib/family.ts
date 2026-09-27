import type { Relationship } from "../api/types";

export const FAMILY_KEY = ["portal", "family"];

const LABELS: Record<Relationship, string> = {
  PARENT: "Parent",
  CHILD: "Child",
  SPOUSE: "Spouse",
  SIBLING: "Sibling",
  OTHER: "Family",
};

export function relationshipLabel(relationship: Relationship | null): string {
  return relationship ? LABELS[relationship] : "You";
}
