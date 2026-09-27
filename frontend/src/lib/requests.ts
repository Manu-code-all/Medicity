import type { RequestStatus } from "../api/types";

const RUPEES = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });

/** "₹68.80". Amounts are rupees with paise, as the API sends them. */
export function formatRupees(amount: number): string {
  return RUPEES.format(amount);
}

export function requestStatusLabel(status: RequestStatus): string {
  switch (status) {
    case "OPEN":
      return "Open";
    case "RESERVED":
      return "Reserved";
    case "CLOSED":
      return "Closed";
    case "EXPIRED":
      return "Expired";
  }
}
