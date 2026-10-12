import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { AuthContext, type AuthContextValue, type Session } from "../auth/context";
import { ChangePasswordGate } from "./ChangePasswordGate";

function renderGate(session: Session | null, changePassword = vi.fn(async () => ({}) as Session)) {
  const value = { session, changePassword, logout: vi.fn() } as unknown as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <ChangePasswordGate />
    </AuthContext.Provider>,
  );
  return value;
}

const TEMP: Session = { userId: "u1", fullName: "Anu Desk", role: "RECEPTIONIST", mustChangePassword: true };

describe("ChangePasswordGate", () => {
  it("shows nothing to an ordinary session or a visitor", () => {
    renderGate({ ...TEMP, mustChangePassword: false });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("blocks a session with a temporary password until a new one is chosen", async () => {
    const value = renderGate(TEMP);
    expect(screen.getByRole("dialog", { name: "Choose your own password" })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Temporary password"), "ABCD-EFGH-JKLM");
    await userEvent.type(screen.getByLabelText("New password"), "my-own-passphrase");
    await userEvent.type(screen.getByLabelText("New password again"), "my-own-passphrasX");
    expect(screen.getByText("The two passwords differ.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save password" })).toBeDisabled();

    await userEvent.clear(screen.getByLabelText("New password again"));
    await userEvent.type(screen.getByLabelText("New password again"), "my-own-passphrase");
    await userEvent.click(screen.getByRole("button", { name: "Save password" }));

    expect(value.changePassword).toHaveBeenCalledWith("ABCD-EFGH-JKLM", "my-own-passphrase");
  });

  it("shows why the change was refused, and signing out stays possible", async () => {
    const refuse = vi.fn(async () => {
      throw new ApiError(422, { code: "INCORRECT_PASSWORD", detail: "That password is not correct" });
    });
    const value = renderGate(TEMP, refuse);

    await userEvent.type(screen.getByLabelText("Temporary password"), "wrong");
    await userEvent.type(screen.getByLabelText("New password"), "my-own-passphrase");
    await userEvent.type(screen.getByLabelText("New password again"), "my-own-passphrase");
    await userEvent.click(screen.getByRole("button", { name: "Save password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("That password is not correct");
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(value.logout).toHaveBeenCalled();
  });
});
