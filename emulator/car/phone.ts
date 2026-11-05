// The phone the emulator simulates: a synthetic phone book and call history, as the Bluetooth sync would deliver them.
import type { CallRecord, Contact } from "./bridge";

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

/** Bluetooth major device classes: phones take calls, a tablet or a laptop does not. */
export const MAJOR_PHONE = 0x0200;
export const MAJOR_COMPUTER = 0x0100;

/** The phone the emulator starts connected to, by the name it gives itself over Bluetooth. */
export const SAMPLE_PHONE = "Pixel 8";

// fictional names and numbers
export const SAMPLE_CONTACTS: Contact[] = [
  { name: "Ana Petrescu", numbers: [{ number: "+40 721 000 101", label: "mobile" }] },
  { name: "Andrei Popa", numbers: [{ number: "0722 000 202", label: "mobile" }] },
  { name: "Victor Stan", numbers: [{ number: "0744 000 303", label: "mobile" }, { number: "021 000 3030", label: "work" }] },
  { name: "Bogdan Ionescu", numbers: [{ number: "0723 000 404", label: "mobile" }] },
  { name: "Cristina Dima", numbers: [{ number: "0755 000 505", label: "mobile" }] },
  { name: "Daniel Marin", numbers: [{ number: "0766 000 606", label: "mobile" }] },
  { name: "Elena Radu", numbers: [{ number: "0745 000 707", label: "mobile" }, { number: "0264 000 770", label: "home" }] },
  { name: "Florin Tudor", numbers: [{ number: "0731 000 808", label: "mobile" }] },
  { name: "Ioana Vlad", numbers: [{ number: "0727 000 909", label: "mobile" }] },
  { name: "Mama", numbers: [{ number: "0721 000 111", label: "mobile" }] },
  { name: "Mihai Georgescu", numbers: [{ number: "0740 000 121", label: "mobile" }, { number: "021 000 1212", label: "work" }] },
  { name: "Radu Constantin", numbers: [{ number: "0757 000 131", label: "mobile" }] },
  { name: "Service auto", numbers: [{ number: "021 000 1414", label: "work" }] },
  { name: "Ștefan Matei", numbers: [{ number: "0733 000 151", label: "mobile" }] },
  { name: "Teodora Iliescu", numbers: [{ number: "0768 000 161", label: "mobile" }] },
];

/** The phone's history, newest first, at times before [now]. */
export function sampleCalls(now: number): CallRecord[] {
  return [
    { number: "0744 000 303", name: "", type: "MISSED", atMs: now - 25 * MIN },
    { number: "+40744000303", name: "", type: "MISSED", atMs: now - 31 * MIN },
    { number: "0721 000 111", name: "", type: "OUTGOING", atMs: now - 2 * HOUR },
    { number: "021 000 1414", name: "", type: "INCOMING", atMs: now - DAY - 3 * HOUR },
    { number: "0722 000 202", name: "", type: "OUTGOING", atMs: now - 2 * DAY },
    { number: "0799 000 444", name: "", type: "MISSED", atMs: now - 4 * DAY },
    { number: "0745 000 707", name: "", type: "INCOMING", atMs: now - 9 * DAY },
  ];
}

/** Callers the control panel can ring the unit with: a contact, and a number the phone book does not know. */
export const SAMPLE_CALLERS = [
  { label: "Ana Petrescu", number: "+40721000101" },
  { label: "Unknown number", number: "+40799000444" },
];
