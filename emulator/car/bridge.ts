// Typed access to core-car compiled to JavaScript. Every decision goes through here in JSON.

export type Source = "NONE" | "BT" | "RADIO";
export type CallState = "IDLE" | "RINGING" | "ACTIVE";
export type ThemeMode = "AUTO" | "LIGHT" | "DARK";
export type Face = "DIGITAL" | "ANALOG";
export type SteeringKey = "VOL_UP" | "VOL_DOWN" | "NEXT" | "PREV" | "MODE" | "PHONE" | "MUTE";
export type PanelKey = "POWER" | "HOME" | "BACK" | "VOL_UP" | "VOL_DOWN";
export type Band = "FM" | "AM";
export type App = "HOME" | "MUSIC" | "RADIO" | "MAPS";

export interface CarState {
  source: Source;
  playing: boolean;
  muted: boolean;
  camera: boolean;
  call: CallState;
  btConnected: boolean;
  night: boolean;
  volume: number;
  /** the radio receives its station; false while paused */
  radioOn: boolean;
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
  networks: boolean;
  running: App[];
  recents: boolean;
  lastHomeMs: number | null;
  card: CardPage | null;
}

export type CardPage = "BLUETOOTH" | "NO_PHONE" | "BT_OFF" | "RADIO" | "NAVIGATION";
export type CardPress = "PREV" | "PLAY" | "NEXT";

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
  | { type: "Radio"; khz: number; band: Band; rds?: string; signal?: number; pty?: number; stereo?: boolean }
  | { type: "CarDisplay"; mode: string };

export type CarCommand =
  | { type: "Media.Play" | "Media.Pause" | "Media.Next" | "Media.Prev" }
  | { type: "Radio.Play" | "Radio.Pause" }
  | { type: "Radio.Seek"; up: boolean }
  | { type: "Radio.Tune"; khz: number }
  | { type: "Radio.Band"; band: Band }
  | { type: "Radio.Scan" }
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
  | { type: "CycleTheme" | "OpenDrawer" | "CloseDrawer" | "ToggleFace" | "ToggleScreen" | "Wake" | "Home" | "Back" | "OpenDevices" | "CloseDevices" | "OpenNetworks" | "CloseNetworks" | "CloseAllApps" | "OpenRecents" | "CloseRecents" | "Sleep" }
  | { type: "HomePressed"; atMs: number }
  | { type: "CloseApp"; app: App }
  | { type: "SetTheme"; mode: ThemeMode }
  | { type: "DrawerReleased"; fraction: number }
  | { type: "SetFace"; face: Face }
  | { type: "SetFont"; name: string }
  | { type: "SetBrightness"; value: number }
  | { type: "Headlights"; on: boolean }
  | { type: "OpenApp"; app: App }
  | { type: "ShowCard"; page: CardPage };

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
      ptyName(code: number): string;
      loadBorders(geojson: string): void;
      countryAt(lon: number, lat: number): string;
      mapOffer(country: string, installed: string, wifi: boolean, declined: string, downloading: string): string;
      packMb(code: string): number;
      homeCard(state: string, btOn: boolean, chosen: string): string;
      homeCardPress(state: string, page: string, press: string): string;
      speed(lon: number, lat: number, timeMs: number, chipKmh: number): string;
      speedWarning(roadLimit: number, timeMs: number): string;
      countryName(code: string): string;
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
  ptyName: (code: number): string => K!.Bridge.ptyName(code),
  loadBorders: (geojson: string) => K!.Bridge.loadBorders(geojson),
  countryAt: (lon: number, lat: number): string => K!.Bridge.countryAt(lon, lat),
  mapOffer: (country: string, installed: string[], wifi: boolean, declined: string[], downloading: string | null) =>
    K!.Bridge.mapOffer(country, JSON.stringify(installed), wifi, JSON.stringify(declined), downloading ?? "") as "NONE" | "DOWNLOAD" | "NEEDS_WIFI",
  packMb: (code: string): number => K!.Bridge.packMb(code),
  /** The card on home: its pages, the one shown, the sound strip under the map, and the page a source brings forward. */
  homeCard: (state: CarState, btOn: boolean, chosen: CardPage | null): { pages: CardPage[]; shown: CardPage; strip: "NONE" | "BT" | "RADIO"; forSource: CardPage | "" } =>
    JSON.parse(K!.Bridge.homeCard(JSON.stringify(state), btOn, chosen ?? "")),
  /** What a transport button on a card page, a sound app or the map's strip sends, by the unit's rule. */
  homeCardPress: (state: CarState, page: "BLUETOOTH" | "RADIO", press: CardPress): CarCommand[] =>
    JSON.parse(K!.Bridge.homeCardPress(JSON.stringify(state), page, press)),
  /** The limit sign and over-limit warning for the last speed fix; [roadLimit] null when the car's road has none. */
  speedWarning: (roadLimit: number | null, timeMs: number): { limit: number | null; over: boolean; chime: boolean; lit: number; tone: "WHITE" | "ORANGE" | "RED" } =>
    JSON.parse(K!.Bridge.speedWarning(roadLimit ?? -1, timeMs)),
  /** A GPS fix for the map's speed, through the unit's own calculation; [chipKmh] null when the fix has none. */
  speed: (lon: number, lat: number, timeMs: number, chipKmh: number | null): { kmh: number; stopped: boolean } =>
    JSON.parse(K!.Bridge.speed(lon, lat, timeMs, chipKmh ?? -1)),
  countryName: (code: string): string => K!.Bridge.countryName(code),
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
