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

// --- Patient portal ------------------------------------------------------

export interface PatientProfile {
  fullName: string;
  email: string;
  phone: string | null;
  dateOfBirth: string;
  gender: "MALE" | "FEMALE" | "OTHER" | "UNDISCLOSED";
  bloodGroup: string | null;
  addressLine: string | null;
  city: string | null;
  emergencyContact: string | null;
  memberSince: string;
}

export interface Visit {
  id: string;
  status: AppointmentStatus;
  scheduledAt: string;
  endsAt: string;
  reason: string | null;
  doctorId: string;
  doctorName: string;
  specialization: string;
  cancelledAt: string | null;
  cancelReason: string | null;
}

export interface PortalSummary {
  upcoming: number;
  completed: number;
  cancelled: number;
  missed: number;
  prescriptions: number;
  nextVisit: Visit | null;
}

export interface PrescriptionItem {
  medicine: string;
  strength: string | null;
  form: string;
  dosage: string;
  frequency: string;
  durationDays: number;
  quantity: number;
}

export interface Prescription {
  id: string;
  appointmentId: string;
  issuedAt: string;
  doctorName: string;
  specialization: string;
  diagnosis: string;
  notes: string | null;
  /** True when this prescription corrects an earlier one, which it replaces. */
  revised: boolean;
  /** When the pharmacy filled it; null until then. */
  dispensedAt: string | null;
  items: PrescriptionItem[];
}

export type VisitScope = "upcoming" | "past";

// --- Doctor workspace ----------------------------------------------------

export interface PatientBrief {
  id: string;
  fullName: string;
  age: number;
  gender: PatientProfile["gender"];
  bloodGroup: string | null;
}

export interface DoctorVisitSummary {
  id: string;
  status: AppointmentStatus;
  scheduledAt: string;
  endsAt: string;
  reason: string | null;
  patient: PatientBrief;
  prescriptionId: string | null;
  dispensedAt: string | null;
}

export interface DoctorVisitDetail {
  id: string;
  status: AppointmentStatus;
  scheduledAt: string;
  endsAt: string;
  reason: string | null;
  cancelReason: string | null;
  /** Whether the start time has passed, so the visit can be closed. */
  started: boolean;
  patient: PatientBrief;
  prescription: Prescription | null;
}

export interface PatientHistory {
  patient: PatientBrief;
  visits: Visit[];
  prescriptions: Prescription[];
}

export interface PrescriptionDraftItem {
  medicineId: string;
  dosage: string;
  frequency: string;
  durationDays: number;
  quantity: number;
}

export interface PrescriptionDraft {
  diagnosis: string;
  notes: string;
  items: PrescriptionDraftItem[];
}

export interface Medicine {
  id: string;
  name: string;
  genericName: string;
  form: string;
  strength: string | null;
  quantityOnHand: number;
  lowStock: boolean;
}

export interface AppNotification {
  id: string;
  kind: string;
  title: string;
  body: string;
  link: string | null;
  /** The moment the notification is about, such as a visit's start; formatted in the viewer's time zone. */
  occursAt: string | null;
  createdAt: string;
  read: boolean;
}

export interface NotificationList {
  unread: number;
  items: AppNotification[];
}
