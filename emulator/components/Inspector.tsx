import { useState } from "react";
import { clearLog, useEmulator, type LogEntry } from "../car/store";
import { Segmented } from "./controls";

const TABS = [{ value: "log", label: "Log" }, { value: "rules", label: "Rules" }, { value: "shell", label: "Shell" }] as const;

const clock = (t: Date) => t.toLocaleTimeString("en-GB");

function Log({ entries }: { entries: LogEntry[] }) {
  if (!entries.length) return <p className="note">No events yet. Press a control or run a scenario.</p>;
  return (
    <ol className="log" reversed>
      {[...entries].reverse().map((entry) => (
        <li key={entry.id} className={`log-${entry.direction}`}>
          <time>{clock(entry.time)}</time>
          <span>{entry.direction === "in" ? "car" : "ui"}</span>
          <code>{entry.text}</code>
        </li>
      ))}
    </ol>
  );
}

export function Inspector() {
  const { log, car, shell } = useEmulator();
  const [tab, setTab] = useState<(typeof TABS)[number]["value"]>("log");
  return (
    <section className="inspector" aria-label="Inspector">
      <div className="inspector-head">
        <Segmented label="Inspector view" options={TABS} value={tab} onChange={setTab} />
        {tab === "log" && <button className="button" disabled={!log.length} onClick={clearLog}>Clear</button>}
      </div>
      <div className="inspector-body" role="region" tabIndex={0} aria-label={`${tab} output`}>
        {tab === "log" ? <Log entries={log} /> : <pre>{JSON.stringify(tab === "rules" ? car : shell, null, 2)}</pre>}
      </div>
    </section>
  );
}
