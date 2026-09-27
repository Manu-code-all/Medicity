import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { FamilyMember } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { FamilyPage } from "./FamilyPage";

const MEERA: FamilyMember = {
  patientId: "p1", fullName: "Meera Nair", relationship: null, self: true, age: 34, gender: "FEMALE", bloodGroup: "B+",
};
const LALITHA: FamilyMember = {
  patientId: "p2", fullName: "Lalitha Nair", relationship: "PARENT", self: false, age: 63, gender: "FEMALE", bloodGroup: null,
};

describe("FamilyPage", () => {
  it("adds a family member and lists them beside the account holder", async () => {
    let members = [MEERA];
    const calls = mockFetch(({ method }) => {
      if (method === "POST") {
        members = [MEERA, LALITHA];
        return json(201, LALITHA);
      }
      return json(200, members);
    });
    renderPage(<FamilyPage />);

    expect(await screen.findByText("You · 34 yrs · B+")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "+ Add a family member" }));
    await userEvent.type(screen.getByLabelText("Full name"), "Lalitha Nair");
    await userEvent.type(screen.getByLabelText("Date of birth"), "1962-04-10");
    await userEvent.selectOptions(screen.getByLabelText("Gender"), "FEMALE");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(await screen.findByText("Lalitha Nair")).toBeInTheDocument();
    expect(screen.getByText("Parent · 63 yrs")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({
      fullName: "Lalitha Nair", relationship: "PARENT", dateOfBirth: "1962-04-10", gender: "FEMALE",
    });
  });

  it("keeps the form open and shows why when the server refuses", async () => {
    mockFetch(({ method }) =>
      method === "POST"
        ? json(409, { code: "FAMILY_FULL", detail: "An account can manage up to 8 family members" })
        : json(200, [MEERA]),
    );
    renderPage(<FamilyPage />);
    await screen.findByText("Meera Nair");

    await userEvent.click(screen.getByRole("button", { name: "+ Add a family member" }));
    await userEvent.type(screen.getByLabelText("Full name"), "Aarav Nair");
    await userEvent.type(screen.getByLabelText("Date of birth"), "2019-06-01");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("up to 8 family members");
    expect(screen.getByLabelText("Full name")).toHaveValue("Aarav Nair");
  });
});
