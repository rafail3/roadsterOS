import { useState, type ReactNode } from "react";
import { type SetupStep } from "../car/bridge";
import { shellAction } from "../car/store";
import { CubeMark, pixelFont } from "./Boot";
import { Keyboard } from "./Keyboard";

// The setup's screens after the start screen: the home screen's dark glass and type, with the boot screen's tube
// (scanlines), its amber and its pixel captions. One accent across the setup: the boot amber.

/** The screens the progress bar counts, in order. */
const STEPS: SetupStep[] = ["CAR_NAME", "PREPARE", "WIFI", "UPDATE", "LOCATION", "LOOK", "FILES", "WELCOME"];

/** Text in the boot screen's 5x7 pixel font; [cell] in canvas pixels (2.5 draws 2 of the unit's pixels per cell). */
export function PixelText({ text, cell = 2.5, className }: { text: string; cell?: number; className?: string }) {
  const font = pixelFont();
  const rects: ReactNode[] = [];
  [...text.toUpperCase()].forEach((ch, k) => {
    const g = font[ch] ?? font[" "];
    for (let i = 0; i < 35; i++) if (g[i] === "#") rects.push(<rect key={`${k}-${i}`} x={(k * 6 + (i % 5)) * cell} y={Math.floor(i / 5) * cell} width={cell * 0.8} height={cell * 0.8} />);
  });
  return (
    <svg className={className} width={text.length * 6 * cell} height={7 * cell} role="img" aria-label={text}>
      <g fill="currentColor">{rects}</g>
    </svg>
  );
}

/** Where the setup is: one block of dots per screen, full when done, dithered on the current one, faint ahead. */
function Progress({ step }: { step: SetupStep }) {
  const at = STEPS.indexOf(step);
  return (
    <div className="sf-progress" role="progressbar" aria-valuemin={1} aria-valuemax={STEPS.length} aria-valuenow={at + 1} aria-label="Setup progress">
      {STEPS.map((s, i) => (
        <span key={s} className={`sf-block${i < at ? " is-done" : i === at ? " is-now" : ""}`}>
          {Array.from({ length: 18 }, (_, d) => <i key={d} />)}
        </span>
      ))}
    </div>
  );
}

function Frame({ step, title, text, children, next, nextLabel = "Continue", canNext = true }: {
  step: SetupStep; title: string; text: string; children?: ReactNode; next: () => void; nextLabel?: string; canNext?: boolean;
}) {
  const at = STEPS.indexOf(step);
  return (
    <section className="sf" aria-label="Setup">
      <header className="sf-bar">
        <PixelText text={`Setup ${at + 1} of ${STEPS.length}`} className="sf-caption" />
        <Progress step={step} />
      </header>
      <div className="sf-head">
        <CubeMark size={104} />
        <div className="sf-heading">
          <h1 className="sf-title">{title}</h1>
          <p className="sf-text">{text}</p>
        </div>
        <div className="sf-actions">
          {at > 0 && <button className="sf-button" onClick={() => shellAction({ type: "SetupBack" })}>Back</button>}
          <button className="sf-button is-primary" disabled={!canNext} onClick={next}>{nextLabel}</button>
        </div>
      </div>
      {children}
    </section>
  );
}

/** The car's name, typed on our keyboard; it is also the name phones see when they share their screen. */
function CarName({ saved, next }: { saved: string; next: () => void }) {
  const [name, setName] = useState(saved);
  const trimmed = name.trim();
  const save = () => {
    if (!trimmed) return;
    shellAction({ type: "SetCarName", name });
    next();
  };
  return (
    <Frame step="CAR_NAME" title="Name your car" text="Phones see this name when they share their screen with the car." next={save} canNext={trimmed !== ""}>
      <div className="sf-field" role="textbox" aria-label="Car name">
        <span className="sf-value">{name}</span>
        <span className="sf-cursor" aria-hidden="true" />
      </div>
      <Keyboard open onKey={(c) => setName((n) => (n + c).slice(0, 24))} onBackspace={() => setName((n) => n.slice(0, -1))}
        onSubmit={save} submitLabel="Done" submitEnabled={trimmed !== ""} />
    </Frame>
  );
}

const TITLES: Partial<Record<SetupStep, [string, string]>> = {
  PREPARE: ["Preparing the system", "Packages, permissions, the home screen and the keys."],
  WIFI: ["Connect to Wi-Fi", "For updates and maps. You can skip it and connect later."],
  UPDATE: ["A new version is out", "Install it now, or later from Settings > About."],
  LOCATION: ["Where you drive", "The map, the time zone and how phone numbers are written."],
  LOOK: ["Choose the look", "Solid or Glassy, light or dark."],
  FILES: ["Installing files", "The map of Romania and the fonts."],
  WELCOME: ["Welcome", "The car is ready."],
};

/** The setup screen for [step]; the start screen and the boot screen are drawn by Boot. */
export function SetupScreen({ step, carName, next }: { step: SetupStep; carName: string; next: () => void }) {
  if (step === "CAR_NAME") return <CarName saved={carName} next={next} />;
  const [title, text] = TITLES[step] ?? [step, ""];
  return <Frame step={step} title={title} text={text} next={next} nextLabel={step === "WELCOME" ? "Start driving" : "Continue"} />;
}
