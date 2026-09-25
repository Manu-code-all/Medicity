export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  path: string;
  fieldErrors?: Record<string, string>;
  incidentId?: string;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  userId: string;
  role: Role;
  fullName: string;
}

export type Role = "PATIENT" | "DOCTOR" | "ADMIN";

export type AppointmentStatus = "BOOKED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";

export interface Doctor {
  id: string;
  fullName: string;
  specialization: string;
  consultationFee: number;
  yearsExperience: number;
  bio: string | null;
}

export interface Slot {
  id: string;
  startsAt: string;
  endsAt: string;
}

export interface Appointment {
  id: string;
  slotId: string;
  patientId: string;
  status: AppointmentStatus;
  scheduledAt: string;
  reason: string | null;
  cancelledAt: string | null;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}
