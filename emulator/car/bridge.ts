// Typed access to core-car compiled to JavaScript. Every decision goes through here in JSON.

export type Source = "NONE" | "BT" | "RADIO";
export type CallState = "IDLE" | "RINGING" | "ACTIVE";
export type ThemeMode = "AUTO" | "LIGHT" | "DARK";
export type Face = "DIGITAL" | "ANALOG";
export type SteeringKey = "VOL_UP" | "VOL_DOWN" | "NEXT" | "PREV" | "MODE" | "PHONE" | "MUTE";
export type PanelKey = "POWER" | "HOME" | "BACK" | "VOL_UP" | "VOL_DOWN";
export type App = "HOME" | "MUSIC";

export interface CarState {
  source: Source;
  playing: boolean;
  muted: boolean;
  camera: boolean;
  call: CallState;
  btConnected: boolean;
  night: boolean;
  volume: number;
}

export interface ShellState {
  theme: ThemeMode;
  drawer: boolean;
  face: Face;
  font: string;
  brightness: number;
  headlights: boolean;
  screenOn: boolean;
  app: App;
  devices: boolean;
  running: App[];
  recents: boolean;
  lastHomeMs: number | null;
}

export type CarEvent =
  | { type: "ReverseGear"; on: boolean }
  | { type: "SteeringKey"; key: SteeringKey }
  | { type: "PanelKey"; key: PanelKey }
  | { type: "Illumination"; on: boolean }
  | { type: "Power"; acc: boolean }
  | { type: "BtConnection"; connected: boolean; device?: string }
  | { type: "Track"; title: string; artist?: string; album?: string; durationMs?: number }
  | { type: "Playback"; playing: boolean; positionMs?: number }
  | { type: "Call"; state: CallState; number?: string; name?: string }
  | { type: "Radio"; khz: number; band: "FM" | "AM"; rds?: string; signal?: number }
  | { type: "CarDisplay"; mode: string };

export type CarCommand =
  | { type: "Media.Play" | "Media.Pause" | "Media.Next" | "Media.Prev" }
  | { type: "Radio.Seek"; up: boolean }
  | { type: "Radio.Preset"; index: number }
  | { type: "Phone.Answer" | "Phone.HangUp" }
  | { type: "Phone.Mute"; on: boolean }
  | { type: "Audio.Mute"; on: boolean }
  | { type: "Audio.Volume"; delta: number }
  | { type: "Audio.Source"; source: Source }
  | { type: "Bt.Connect"; device: string }
  | { type: "Bt.Disconnect" | "Bt.Scan" }
  | { type: "Bt.Pair" | "Bt.Forget"; device: string }
  | { type: "Bt.ConfirmPairing"; accept: boolean };

export type ShellAction =
  | { type: "CycleTheme" | "OpenDrawer" | "CloseDrawer" | "ToggleFace" | "ToggleScreen" | "Wake" | "Home" | "Back" | "OpenDevices" | "CloseDevices" | "CloseAllApps" | "CloseRecents" }
  | { type: "HomePressed"; atMs: number }
  | { type: "CloseApp"; app: App }
  | { type: "SetTheme"; mode: ThemeMode }
  | { type: "DrawerReleased"; fraction: number }
  | { type: "SetFace"; face: Face }
  | { type: "SetFont"; name: string }
  | { type: "SetBrightness"; value: number }
  | { type: "Headlights"; on: boolean }
  | { type: "OpenApp"; app: App };

export interface RuleAction {
  type: string;
  command?: CarCommand;
  on?: boolean;
}

interface Kotlin {
  Bridge: Record<"initial" | "shellInitial", () => string> &
    Record<"apply" | "command" | "shell", (state: string, input: string) => string> & {
      echo(command: string): string;
      maxVolume(): number;
      setVolume(state: string, level: number): string;
      coverColors(pixels: string, width: number): string;
      effectiveTheme(state: string, hour: number): "LIGHT" | "DARK";
      minBrightness(): number;
      maxBrightness(): number;
      veil(state: string): string;
      coverFileName(title: string, artist: string): string;
      coverFind(names: string, title: string, artist: string): string;
      stockCover(title: string, artist: string, count: number): number;
      sharpPx(): number;
    };
}

function load(): Kotlin | null {
  const bundle = (window as unknown as Record<string, { ro?: { rdstr?: { car?: Kotlin } } } | undefined>)["core-car"];
  return bundle?.ro?.rdstr?.car ?? null;
}

const K = load();
export const bridgeLoaded = K !== null;

export const bridge = {
  initial: (): CarState => JSON.parse(K!.Bridge.initial()),
  shellInitial: (): ShellState => JSON.parse(K!.Bridge.shellInitial()),
  apply: (state: CarState, event: CarEvent): { state: CarState; actions: RuleAction[] } =>
    JSON.parse(K!.Bridge.apply(JSON.stringify(state), JSON.stringify(event))),
  command: (state: CarState, command: CarCommand): CarState =>
    JSON.parse(K!.Bridge.command(JSON.stringify(state), JSON.stringify(command))),
  echo: (command: CarCommand): CarEvent | null => JSON.parse(K!.Bridge.echo(JSON.stringify(command))),
  shell: (state: ShellState, action: ShellAction): ShellState =>
    JSON.parse(K!.Bridge.shell(JSON.stringify(state), JSON.stringify(action))),
  maxVolume: () => K!.Bridge.maxVolume(),
  coverColors: (pixels: number[], width: number): { top: string; bottom: string } => JSON.parse(K!.Bridge.coverColors(JSON.stringify(pixels), width)),
  setVolume: (state: CarState, level: number): CarCommand[] => JSON.parse(K!.Bridge.setVolume(JSON.stringify(state), level)),
  effectiveTheme: (state: ShellState, hour: number) => K!.Bridge.effectiveTheme(JSON.stringify(state), hour),
  minBrightness: () => K!.Bridge.minBrightness(),
  maxBrightness: () => K!.Bridge.maxBrightness(),
  veil: (state: ShellState): { dark: number; brightness: number; saturation: number } => JSON.parse(K!.Bridge.veil(JSON.stringify(state))),
  coverFileName: (title: string, artist: string) => K!.Bridge.coverFileName(title, artist),
  coverFind: (names: string[], title: string, artist: string): string | null => K!.Bridge.coverFind(JSON.stringify(names), title, artist) || null,
  stockCover: (title: string, artist: string, count: number) => K!.Bridge.stockCover(title, artist, count),
  sharpPx: () => K!.Bridge.sharpPx(),
};
