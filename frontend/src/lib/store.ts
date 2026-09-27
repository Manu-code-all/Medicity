import type { StoreInput, StoreProfile } from "../api/types";

export const EMPTY_STORE: StoreInput = {
  name: "",
  licenceNumber: "",
  phone: "",
  addressLine: "",
  city: "",
  latitude: 0,
  longitude: 0,
  opensAt: "09:00",
  closesAt: "21:00",
  open24h: false,
  holdHours: 3,
};

export function storeInputFrom(profile: StoreProfile): StoreInput {
  return {
    name: profile.name,
    licenceNumber: profile.licenceNumber,
    phone: profile.phone,
    addressLine: profile.addressLine,
    city: profile.city,
    latitude: profile.latitude,
    longitude: profile.longitude,
    opensAt: profile.opensAt.slice(0, 5),
    closesAt: profile.closesAt.slice(0, 5),
    open24h: profile.open24h,
    holdHours: profile.holdHours,
  };
}
