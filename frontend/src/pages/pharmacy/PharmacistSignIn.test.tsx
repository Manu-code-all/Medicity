import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderPage } from "../../test/renderPage";
import { LoginPage } from "../LoginPage";

describe("Pharmacist sign-in", () => {
  it("has its own page, offers the demo pharmacist, and is not one of the public role tabs", () => {
    renderPage(<LoginPage role="pharmacist" />);

    expect(screen.getByRole("heading", { name: "Sign in to the hospital pharmacy" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Try as the Hospital Pharmacist/ })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Sign in as" })).toBeNull();
    expect(screen.getByRole("link", { name: "Patient sign-in" })).toHaveAttribute("href", "/login");
  });

  it("is reachable from the other sign-in pages", () => {
    renderPage(<LoginPage />);
    expect(screen.getByRole("link", { name: "Sign in here" })).toHaveAttribute("href", "/login/pharmacist");
  });
});
