import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { BodyGuide } from "./BodyGuide";

function renderGuide() {
  render(
    <MemoryRouter>
      <BodyGuide />
    </MemoryRouter>,
  );
}

describe("BodyGuide", () => {
  it("from a keyboard: choose the chest, say it races, and get a cardiologist with a filtered link", async () => {
    renderGuide();

    screen.getByRole("button", { name: "Chest" }).focus();
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("heading", { name: "Chest" })).toBeInTheDocument();

    const next = screen.getByRole("button", { name: "See who to consult" });
    expect(next).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Heart racing or skipping beats" }));
    await userEvent.click(screen.getByRole("button", { name: "A few days" }));
    await userEvent.click(next);

    expect(screen.getByRole("heading", { name: "See a cardiologist" })).toBeInTheDocument();
    expect(screen.getByText("For your chest, a few days")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Find Cardiology doctors/ })).toHaveAttribute(
      "href",
      "/doctors?specialty=Cardiology&zone=chest",
    );
    expect(screen.getByRole("link", { name: "a chest physician" })).toHaveAttribute(
      "href",
      "/doctors?specialty=Pulmonology&zone=chest",
    );
  });

  it("a warning sign replaces the doctor with emergency care and numbers to call", async () => {
    renderGuide();

    await userEvent.click(screen.getByRole("button", { name: "Chest" }));
    await userEvent.click(screen.getByRole("button", { name: "Crushing chest pressure spreading to the arm or jaw" }));
    await userEvent.click(screen.getByRole("button", { name: "See who to consult" }));

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Get emergency care now");
    expect(screen.getByRole("link", { name: /Call 112/ })).toHaveAttribute("href", "tel:112");
    expect(screen.queryByRole("link", { name: /Find .* doctors/ })).not.toBeInTheDocument();
  });

  it("words alone are enough: typing a toothache on the head sends the visitor to a dentist, and keeps the words for booking", async () => {
    renderGuide();

    await userEvent.click(screen.getByRole("button", { name: "Head and face" }));
    const next = screen.getByRole("button", { name: "See who to consult" });
    expect(next).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Or say it in your own words"), "My back tooth hurts when I chew");
    expect(next).toBeEnabled();
    await userEvent.click(next);

    expect(screen.getByRole("heading", { name: "See a dentist" })).toBeInTheDocument();
    expect(screen.getByText("You mentioned \u201ctooth\u201d, which a dentist looks after.")).toBeInTheDocument();
    expect(sessionStorage.getItem("medicity.visitNote")).toBe("My back tooth hurts when I chew");
  });

  it("a warning sign typed in words gives the emergency answer, with the helpline when someone writes about ending their life", async () => {
    renderGuide();

    await userEvent.click(screen.getByRole("button", { name: "Chest" }));
    await userEvent.type(screen.getByLabelText("Or say it in your own words"), "I feel like I want to end my life");
    await userEvent.click(screen.getByRole("button", { name: "See who to consult" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Get emergency care now");
    expect(screen.getByRole("link", { name: "14416" })).toHaveAttribute("href", "tel:14416");
    expect(sessionStorage.getItem("medicity.visitNote")).toBeNull();
  });

  it("the back view has the spine and kidneys, and the list works without the drawing", async () => {
    renderGuide();

    await userEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(screen.getByRole("button", { name: "Sides of the back (kidneys)" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Chest" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Can't use the body map? Choose from a list" }));
    await userEvent.click(screen.getByRole("button", { name: "Mid and lower back" }));
    await userEvent.click(screen.getByRole("button", { name: "Pain when bending or sitting" }));
    await userEvent.click(screen.getByRole("button", { name: "See who to consult" }));

    expect(screen.getByRole("heading", { name: "See an orthopaedic doctor" })).toBeInTheDocument();
  });
});
