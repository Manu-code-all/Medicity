import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { GRIEVANCE_OFFICER } from "../../lib/policies";
import { DeleteAccountPage, DisclaimerPage, PrivacyPage, TermsPage } from "./LegalPages";

function show(page: React.ReactNode, url = "/") {
  render(<MemoryRouter initialEntries={[url]}>{page}</MemoryRouter>);
}

describe("legal pages", () => {
  it.each([
    ["privacy notice", <PrivacyPage key="p" />],
    ["terms of service", <TermsPage key="t" />],
    ["medical disclaimer", <DisclaimerPage key="d" />],
    ["account deletion", <DeleteAccountPage key="a" />],
  ])("the %s is plainly marked as a draft for a lawyer", (_name, page) => {
    show(page);
    expect(screen.getByRole("note")).toHaveTextContent("DRAFT, NOT YET REVIEWED BY A LAWYER");
  });

  it("the privacy notice names the grievance officer placeholder, the rights and the retention of the place of a question", () => {
    show(<PrivacyPage />);

    expect(screen.getByRole("heading", { name: "Grievance Officer" })).toBeInTheDocument();
    expect(screen.getByText(GRIEVANCE_OFFICER.name)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: GRIEVANCE_OFFICER.email })).toHaveAttribute(
      "href",
      `mailto:${GRIEVANCE_OFFICER.email}`,
    );
    expect(screen.getByText(/Download my data/)).toBeInTheDocument();
    expect(screen.getByText(/Cleared 30 days after the question is closed/)).toBeInTheDocument();
  });

  it("the disclaimer says it is not medical advice and points to emergency numbers", () => {
    show(<DisclaimerPage />);
    expect(screen.getByText(/not a doctor and does not give medical advice/)).toBeInTheDocument();
    expect(screen.getByText(/Call 112/)).toBeInTheDocument();
  });

  it("the deletion page, public, gives the steps in the app, the email route, and what is kept", () => {
    show(<DeleteAccountPage />);

    const steps = screen.getAllByRole("listitem").map((li) => li.textContent ?? "");
    expect(steps.some((s) => s.includes("Delete my account"))).toBe(true);
    expect(screen.getByRole("heading", { name: "What we keep, and why" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /doctor or chemist/ })).toBeInTheDocument();
    expect(screen.queryByText(/has been deleted/)).not.toBeInTheDocument();
  });

  it("after deleting, the same page confirms it", () => {
    show(<DeleteAccountPage />, "/delete-account?done=1");
    expect(screen.getByRole("status")).toHaveTextContent("Your account has been deleted");
  });

  it("every page links to the others at its foot", () => {
    show(<TermsPage />);
    const foot = within(document.querySelector(".legal__links") as HTMLElement);
    expect(foot.getByRole("link", { name: "Privacy notice" })).toHaveAttribute("href", "/privacy");
    expect(foot.getByRole("link", { name: "Medical disclaimer" })).toHaveAttribute("href", "/disclaimer");
    expect(foot.getByRole("link", { name: "Delete your account" })).toHaveAttribute("href", "/delete-account");
  });
});
