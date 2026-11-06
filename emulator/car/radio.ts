// The simulated air around the unit: synthetic stations with the RDS data real ones send.

import { bridge, type Band } from "./bridge";

/** A station on air: `ps` is its RDS name (8 characters at most), `pty` its programme type code. */
export interface Station { khz: number; band: Band; ps: string; pty: number; signal: 1 | 2 | 3 }

/** The tuner's ranges and steps, European region of the vendor tuner table: FM in 50 kHz steps, AM in 9 kHz (core-car Tuner). */
export const BANDS = bridge.bands();

export const STATIONS: Station[] = [
  { khz: 88600, band: "FM", ps: "CITYWAVE", pty: 10, signal: 3 },
  { khz: 89200, band: "FM", ps: "POP HITS", pty: 10, signal: 3 },
  { khz: 91400, band: "FM", ps: "NEWS 24", pty: 1, signal: 2 },
  { khz: 93700, band: "FM", ps: "ROCK 93", pty: 11, signal: 2 },
  { khz: 95350, band: "FM", ps: "JAZZ BAR", pty: 24, signal: 2 },
  { khz: 97800, band: "FM", ps: "CLASSIC", pty: 14, signal: 3 },
  { khz: 100400, band: "FM", ps: "RETRO", pty: 27, signal: 2 },
  { khz: 101900, band: "FM", ps: "NORD FM", pty: 9, signal: 3 },
  { khz: 104500, band: "FM", ps: "SPORT FM", pty: 4, signal: 2 },
  { khz: 106800, band: "FM", ps: "FOLK", pty: 28, signal: 1 },
  // on air without RDS: only the frequency shows
  { khz: 107500, band: "FM", ps: "", pty: 0, signal: 1 },
  { khz: 558, band: "AM", ps: "", pty: 0, signal: 2 },
  { khz: 1404, band: "AM", ps: "", pty: 0, signal: 3 },
  { khz: 1593, band: "AM", ps: "", pty: 0, signal: 1 },
];

export const stationAt = (band: Band, khz: number) => STATIONS.find((s) => s.band === band && s.khz === khz);

/** A frequency as the screen writes it: kHz on AM; MHz on FM, with a second decimal only off the 100 kHz grid. */
export const formatFreq = (band: Band, khz: number) => bridge.freqFormat(band, khz);
