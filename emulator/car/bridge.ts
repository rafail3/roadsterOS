// Typed access to core-car compiled to JavaScript. Every decision goes through here in JSON.

export type Source = "NONE" | "BT" | "RADIO";
export type CallState = "IDLE" | "RINGING" | "OUTGOING" | "ACTIVE";
export type ThemeMode = "AUTO" | "LIGHT" | "DARK";
export type Face = "DIGITAL" | "ANALOG";
export type FaceMode = "BOTH" | "DIGITAL" | "ANALOG";
export type TimeSource = "NETWORK" | "GPS";
export type DashKey = "HOUR" | "MINUTE" | "CLOCK";
export type AodFace = "HOME" | "DIGITAL" | "ANALOG";
export type AnimationSpeed = "NORMAL" | "FAST" | "OFF";
export type EqPreset = "USER" | "FLAT" | "POP" | "ROCK" | "JAZZ" | "CLASSIC";
export type ListeningSeat = "CUSTOM" | "FRONT_LEFT" | "FRONT_RIGHT" | "REAR_LEFT" | "REAR_RIGHT" | "ALL" | "FRONT" | "REAR";
export interface SpeakerFilter { highPass: boolean; highPassHz: number; lowPass: boolean; lowPassHz: number }
/** The sound processor's settings (core-car SoundSettings). */
export interface SoundSettings {
  preset: EqPreset; bands: number[]; balance: number; fader: number; seat: ListeningSeat; delays: number[];
  loudness: boolean; loudLowHz: number; loudLowGain: number; loudHighHz: number; loudHighGain: number;
  bass: boolean; bassGain: number; subLevel: number; subHz: number; front: SpeakerFilter; rear: SpeakerFilter; antennaPower: boolean;
  callVolume: number; navVolume: number; musicUnderNav: number;
}
export interface DspWrite { call: string; args: number[] }
export type Guidelines = "OFF" | "FIXED" | "STEERING";
export type ReverseDip = "NONE" | "WEAK" | "MEDIUM" | "STRONG";
/** The reverse camera's settings (core-car CameraSettings); linePos in pixels of the 1024x600 screen, negative higher. */
export interface Point { x: number; y: number }
/** The guidelines' points on the 1024x600 screen (core-car Guides). */
export interface Guides {
  leftFix: Point; leftControl: Point; leftFar: Point; leftMiddle: Point; leftNear: Point;
  rightFix: Point; rightControl: Point; rightFar: Point; rightMiddle: Point; rightNear: Point;
}
export interface CameraSettings { mirror: boolean; guidelines: Guidelines; linePos: number; dip: ReverseDip }
export type SteeringKey = "VOL_UP" | "VOL_DOWN" | "NEXT" | "PREV" | "MODE" | "PHONE" | "MUTE";
export type PanelKey = "POWER" | "HOME" | "BACK" | "VOL_UP" | "VOL_DOWN";
export type Band = "FM" | "AM";
export type App = "HOME" | "MUSIC" | "RADIO" | "MAPS" | "PHONE" | "SETTINGS" | "PHONE_SCREEN";
export type CastState = "OFF" | "VISIBLE" | "CONNECTING" | "LIVE";

export interface CarState {
  source: Source;
  playing: boolean;
  muted: boolean;
  camera: boolean;
  call: CallState;
  btConnected: boolean;
  /** a phone is connected for calls (hands-free profile) */
  hfpConnected: boolean;
  night: boolean;
  volume: number;
  /** the radio receives its station; false while paused */
  radioOn: boolean;
  /** the phone's screen on ours (Miracast) */
  cast: CastState;
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
  sound: boolean;
  networks: boolean;
  running: App[];
  recents: boolean;
  lastHomeMs: number | null;
  card: CardPage | null;
  clock24h: boolean;
  speedChime: boolean;
  /** the highest volume level the car starts with; 0 keeps the last one */
  startVolume: number;
  /** how the call shows: none, over the whole screen, or as the bar at the top */
  callView: "NONE" | "FULL" | "BAR";
  /** writing a message is blocked while the car moves (a setting, off by default) */
  lockTypingWhileMoving: boolean;
  /** the app icons on home, in order: our apps by name, other apps by package */
  homeApps: string[];
  recordEvents: boolean;
  /** the road's speed limit sign on the map's speed */
  speedLimitSign: boolean;
  /** both clock faces switched with a tap, or one fixed face */
  faceMode: FaceMode;
  autoTime: boolean;
  timeSource: TimeSource;
  dashMatched: boolean;
  aodEnabled: boolean;
  aodAfterMin: number;
  aodFace: AodFace;
  aod: boolean;
  developer: boolean;
  adbEnabled: boolean;
  showTouches: boolean;
  showFrameRate: boolean;
  animationSpeed: AnimationSpeed;
  setupDone: boolean;
  dsp: SoundSettings;
  camera: CameraSettings;
  /** the camera opened from its icon, without reverse gear */
  cameraOpen: boolean;
  /** phones find the unit in Smart View while the car is on */
  castVisible: boolean;
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
  | { type: "Call"; state: CallState; number?: string; name?: string; held?: boolean; conference?: boolean; other?: OtherCall | null }
  | { type: "VoiceAssistant"; active: boolean }
  | { type: "MessageSupport"; available: boolean }
  | ({ type: "Message" } & Message)
  | { type: "HfpConnection"; connected: boolean; device?: string }
  | { type: "CallAudio"; inCar: boolean }
  | { type: "PhoneStatus"; battery?: number; signal?: number; operator?: string; roaming?: boolean }
  | { type: "Radio"; khz: number; band: Band; rds?: string; signal?: number; pty?: number; stereo?: boolean }
  | { type: "CarDisplay"; mode: string }
  | { type: "PhoneCast"; state: CastState; device?: string };

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
  | { type: "Phone.Dial"; number: string }
  | { type: "Dash.Key"; key: DashKey }
  | { type: "Dsp"; write: DspWrite }
  | { type: "Camera.Write"; write: DspWrite }
  | { type: "Phone.Dtmf"; digit: string }
  | { type: "Phone.AudioInCar"; on: boolean }
  | { type: "Phone.Redial" | "Phone.Hold" | "Phone.Merge" | "Phone.DeclineWaiting" }
  | { type: "Phone.AnswerWaiting"; keepCurrent: boolean }
  | { type: "Phone.VoiceAssistant"; on: boolean }
  | { type: "Phone.SendMessage"; number: string; text: string }
  | { type: "Audio.Mute"; on: boolean }
  | { type: "Audio.Volume"; delta: number }
  | { type: "Audio.Source"; source: Source }
  | { type: "Bt.Connect"; device: string }
  | { type: "Bt.Disconnect" | "Bt.Scan" }
  | { type: "Bt.Pair" | "Bt.Forget"; device: string }
  | { type: "Bt.ConfirmPairing"; accept: boolean };

export type ShellAction =
  | { type: "CycleTheme" | "OpenDrawer" | "CloseDrawer" | "ToggleFace" | "ToggleScreen" | "Wake" | "Home" | "Back" | "OpenDevices" | "CloseDevices" | "OpenNetworks" | "CloseNetworks" | "CloseAllApps" | "OpenRecents" | "CloseRecents" | "Sleep" | "ResetSettings" }
  | { type: "SetClock24h" | "SetSpeedChime"; on: boolean }
  | { type: "SetStartVolume"; level: number }
  | { type: "HomePressed"; atMs: number }
  | { type: "CloseApp"; app: App }
  | { type: "SetTheme"; mode: ThemeMode }
  | { type: "DrawerReleased"; fraction: number }
  | { type: "SetFace"; face: Face }
  | { type: "SetFont"; name: string }
  | { type: "SetBrightness"; value: number }
  | { type: "Headlights"; on: boolean }
  | { type: "OpenApp"; app: App }
  | { type: "ShowCard"; page: CardPage }
  | { type: "CallChanged"; state: CallState }
  | { type: "ExpandCall" }
  | { type: "SetTypingLock"; on: boolean }
  | { type: "ToggleHomeApp"; id: string }
  | { type: "MoveHomeApp"; id: string; by: number }
  | { type: "AppsInstalled"; ids: string[] }
  | { type: "SetRecording"; on: boolean }
  | { type: "SetSpeedLimitSign"; on: boolean }
  | { type: "SetFaceMode"; mode: FaceMode }
  | { type: "SetTimeSync"; auto: boolean; source: TimeSource }
  | { type: "SetDashMatched"; on: boolean }
  | { type: "SetAod"; on: boolean }
  | { type: "SetAodAfter"; minutes: number }
  | { type: "SetAodFace"; face: AodFace }
  | { type: "EnterAod" | "ExitAod" }
  | { type: "OpenSound" | "CloseSound" }
  | { type: "SetDeveloper" | "SetAdb" | "SetShowTouches" | "SetShowFrameRate"; on: boolean }
  | { type: "SetAnimationSpeed"; speed: AnimationSpeed }
  | { type: "FinishSetup" }
  | { type: "SetSound"; settings: SoundSettings }
  | { type: "SetCamera"; settings: CameraSettings }
  | { type: "SetCastVisible"; on: boolean }
  | { type: "CastChanged"; state: CastState }
  | { type: "OpenCamera" | "CloseCamera" };

export interface PhoneNumber { number: string; label: string }
export interface Contact { name: string; numbers: PhoneNumber[] }
export type CallType = "INCOMING" | "OUTGOING" | "MISSED";
export interface CallRecord { number: string; name: string; type: CallType; atMs: number }
export interface RecentRow { number: string; name: string; label: string; type: CallType; atMs: number; count: number }
export interface PhoneMatch { name: string; number: string; label: string }
export type PhoneScreen = "BT_OFF" | "CONNECT" | "READY";
/** A second call beside the one on screen: ringing (waiting) or on hold. */
export interface OtherCall { number: string; name?: string; waiting: boolean }
export interface Message { id: string; number: string; text: string; name?: string; atMs?: number; sent?: boolean }
export interface Conversation { number: string; name: string; last: string; atMs: number; unread: number; messages: Message[] }
/** One call as the phone lists it over HFP; states are the Hfp constants of core-car. */
export interface HfpCall { id: number; state: number; number: string; multiparty?: boolean }
export const HFP = { ACTIVE: 0, HELD: 1, DIALING: 2, ALERTING: 3, INCOMING: 4, WAITING: 5 } as const;

/** An app the unit can start: ours by name, another by its package. */
export interface LaunchableApp { id: string; label: string }

export interface RuleAction {
  type: string;
  app?: App;
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
      encodePreferences(state: string): string;
      decodePreferences(text: string): string;
      factoryPreferences(): string;
      aodDue(state: string, idleMs: number, busy: boolean): boolean;
      aodFace(state: string): "DIGITAL" | "ANALOG";
      wakesAod(event: string): boolean;
      aodMinutes(): string;
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
      freqFormat(band: string, khz: number): string;
      freqGlide(shown: number, target: number): number;
      freqCounter(band: string, khz: number, target: number): string;
      homeCardPress(state: string, page: string, press: string): string;
      speed(lon: number, lat: number, timeMs: number, chipKmh: number): string;
      speedWarning(roadLimit: number, timeMs: number): string;
      countryName(code: string): string;
      phoneScreen(btOn: boolean, hfpConnected: boolean): string;
      callCapable(majorClass: number): boolean;
      phoneSections(book: string): string;
      phoneRecents(calls: string, book: string): string;
      phoneSearch(book: string, query: string): string;
      phoneSuggest(book: string, typed: string): string;
      phoneFind(book: string, number: string): string;
      initials(name: string): string;
      hfpCall(calls: string): string;
      conversations(messages: string, read: string, book: string): string;
      quickReplies(): string;
      homeApps(chosen: string, installed: string): string;
      homeAppsMax(): number;
      arrangeHoldMs(): number;
      arrangeCancelsHold(dx: number): boolean;
      arrangeDropIndex(from: number, dx: number, slot: number, count: number): number;
      arrangeOffset(i: number, from: number, dx: number, slot: number, count: number): number;
      arrangeSettle(from: number, dx: number, slot: number, count: number): number;
      arrangeKeeps(from: number, to: number): boolean;
      arrangeOpens(arranging: boolean, pickedUp: boolean): boolean;
      arrangeTimes(): string;
      arrangeGlides(before: string, after: string): string;
      ownApps(): string;
      timeZones(ids: string): string;
      timeZoneSearch(ids: string, query: string): string;
      timeZoneCity(id: string): string;
      timeZoneRegion(id: string): string;
      timeZoneOffset(minutes: number): string;
      dashFollow(shownBefore: number, jump: number): string;
      soundWrites(a: string, b: string): string;
      cameraWrites(a: string, b: string): string;
      cameraGuides(guidelines: string, eps: number): string;
      startupPhones(saved: string, last: string | null): string;
      startupConnectWaitMs(): number;
      startupConnectTryMs(): number;
      castName(): string;
      soundPreset(s: string, preset: string): string;
      soundBand(s: string, band: number, db: number): string;
      soundResetLoudness(s: string): string;
      soundResetBass(s: string): string;
      soundBands(): string;
      soundHpf(): string;
      soundLpf(): string;
      callDuration(ms: number): string;
      clockText(hour: number, minute: number, h24: boolean): string;
      meridiem(hour: number): string;
      startVolume(state: string, cap: number): string;
      size(bytes: number): string;
    };
}

function load(): Kotlin | null {
  const bundle = (window as unknown as Record<string, { ro?: { rdstr?: { car?: Kotlin } } } | undefined>)["core-car"];
  return bundle?.ro?.rdstr?.car ?? null;
}

const K = load();
export const bridgeLoaded = K !== null;

// In development the core-car bundle is loaded once, as a script, and a hot reload of the TypeScript keeps the old one:
// when a rebuild replaces public/rdstr.js the page reloads whole, so the two never disagree.
if (process.env.NODE_ENV === "development" && typeof window !== "undefined") {
  let seen: string | null = null;
  const stamp = (r: Response) => r.headers.get("etag") ?? r.headers.get("last-modified") ?? r.headers.get("content-length");
  setInterval(() => {
    fetch("/rdstr.js", { method: "HEAD", cache: "no-store" }).then((r) => {
      const now = stamp(r);
      if (seen !== null && now !== null && now !== seen) window.location.reload();
      seen = now ?? seen;
    }).catch(() => {});
  }, 2000);
}

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
  freqFormat: (band: Band, khz: number): string => K!.Bridge.freqFormat(band, khz),
  freqGlide: (shown: number, target: number): number => K!.Bridge.freqGlide(shown, target),
  freqCounter: (band: Band, khz: number, target: number): { exact: string | null; label: string; decimalAt: number; columns: [number, number][] } =>
    JSON.parse(K!.Bridge.freqCounter(band, khz, target)),
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
  /** The preferences kept across starts (SharedPreferences on the unit, the browser's storage here). */
  encodePreferences: (state: ShellState): string => K!.Bridge.encodePreferences(JSON.stringify(state)),
  decodePreferences: (text: string): ShellState => JSON.parse(K!.Bridge.decodePreferences(text)),
  factoryPreferences: (): string => K!.Bridge.factoryPreferences(),
  /** The always-on display: due after the wait, its face, the car events that end it (core-car Shell). */
  aodDue: (state: ShellState, idleMs: number, busy: boolean) => K!.Bridge.aodDue(JSON.stringify(state), idleMs, busy),
  aodFace: (state: ShellState) => K!.Bridge.aodFace(JSON.stringify(state)),
  wakesAod: (event: CarEvent) => K!.Bridge.wakesAod(JSON.stringify(event)),
  aodMinutes: (): number[] => JSON.parse(K!.Bridge.aodMinutes()),
  maxBrightness: () => K!.Bridge.maxBrightness(),
  veil: (state: ShellState): { dark: number; brightness: number; saturation: number } => JSON.parse(K!.Bridge.veil(JSON.stringify(state))),
  coverFileName: (title: string, artist: string) => K!.Bridge.coverFileName(title, artist),
  coverFind: (names: string[], title: string, artist: string): string | null => K!.Bridge.coverFind(JSON.stringify(names), title, artist) || null,
  stockCover: (title: string, artist: string, count: number) => K!.Bridge.stockCover(title, artist, count),
  sharpPx: () => K!.Bridge.sharpPx(),
  /** What the phone app shows: Bluetooth off, a phone to connect, or the app itself. */
  phoneScreen: (btOn: boolean, hfpConnected: boolean) => K!.Bridge.phoneScreen(btOn, hfpConnected) as PhoneScreen,
  /** Bluetooth's major device class says whether a device can take calls (phones only). */
  callCapable: (majorClass: number) => K!.Bridge.callCapable(majorClass),
  phoneSections: (book: Contact[]): { letter: string; contacts: Contact[] }[] => JSON.parse(K!.Bridge.phoneSections(JSON.stringify(book))),
  /** Contacts by any word start of the name, accents folded, or by digits of a number (core-car PhoneBook.search). */
  phoneSearch: (book: Contact[], query: string): Contact[] => JSON.parse(K!.Bridge.phoneSearch(JSON.stringify(book), query)),
  phoneRecents: (calls: CallRecord[], book: Contact[]): RecentRow[] => JSON.parse(K!.Bridge.phoneRecents(JSON.stringify(calls), JSON.stringify(book))),
  /** The contact under digits typed on the keypad, from three digits on. */
  phoneSuggest: (book: Contact[], typed: string): PhoneMatch | null => JSON.parse(K!.Bridge.phoneSuggest(JSON.stringify(book), typed)),
  /** The contact a caller's number belongs to; the phone sends only the number. */
  phoneFind: (book: Contact[], number: string): PhoneMatch | null => JSON.parse(K!.Bridge.phoneFind(JSON.stringify(book), number)),
  initials: (name: string) => K!.Bridge.initials(name),
  /** The phone's call list folded into the one call the screen shows, as on the unit (core-car Hfp.call). */
  hfpCall: (calls: HfpCall[]): CarEvent => JSON.parse(K!.Bridge.hfpCall(JSON.stringify(calls))),
  conversations: (messages: Message[], read: string[], book: Contact[]): Conversation[] =>
    JSON.parse(K!.Bridge.conversations(JSON.stringify(messages), JSON.stringify(read), JSON.stringify(book))),
  quickReplies: (): string[] => JSON.parse(K!.Bridge.quickReplies()),
  /** The icons home shows: the chosen ones still installed, in order (core-car HomeApps). */
  homeApps: (chosen: string[], installed: LaunchableApp[]): LaunchableApp[] => JSON.parse(K!.Bridge.homeApps(JSON.stringify(chosen), JSON.stringify(installed))),
  homeAppsMax: () => K!.Bridge.homeAppsMax(),
  /** Arranging the icons on home (core-car HomeArrange). */
  arrange: {
    holdMs: () => K!.Bridge.arrangeHoldMs(),
    cancelsHold: (dx: number) => K!.Bridge.arrangeCancelsHold(dx),
    dropIndex: (from: number, dx: number, slot: number, count: number) => K!.Bridge.arrangeDropIndex(from, dx, slot, count),
    offset: (i: number, from: number, dx: number, slot: number, count: number) => K!.Bridge.arrangeOffset(i, from, dx, slot, count),
    settle: (from: number, dx: number, slot: number, count: number) => K!.Bridge.arrangeSettle(from, dx, slot, count),
    keeps: (from: number, to: number) => K!.Bridge.arrangeKeeps(from, to),
    opens: (arranging: boolean, pickedUp: boolean) => K!.Bridge.arrangeOpens(arranging, pickedUp),
    /** Remove, glide and grow durations in ms. */
    times: (): [number, number, number] => JSON.parse(K!.Bridge.arrangeTimes()),
    glides: (before: Record<string, number>, after: Record<string, number>): { id: string; fromDx?: number; added?: boolean }[] =>
      JSON.parse(K!.Bridge.arrangeGlides(JSON.stringify(before), JSON.stringify(after))),
  },
  ownApps: (): LaunchableApp[] => JSON.parse(K!.Bridge.ownApps()),
  /** The zones named after a place, by city, and a search over them (core-car TimeZones). */
  timeZones: (ids: string[]): string[] => JSON.parse(K!.Bridge.timeZones(JSON.stringify(ids))),
  timeZoneSearch: (ids: string[], query: string): string[] => JSON.parse(K!.Bridge.timeZoneSearch(JSON.stringify(ids), query)),
  timeZoneCity: (id: string) => K!.Bridge.timeZoneCity(id),
  timeZoneRegion: (id: string) => K!.Bridge.timeZoneRegion(id),
  timeZoneOffset: (minutes: number) => K!.Bridge.timeZoneOffset(minutes),
  /** The dash display's keys for a jump of the unit's local time (core-car DashClock). */
  dashFollow: (shownBefore: number, jump: number): DashKey[] => JSON.parse(K!.Bridge.dashFollow(shownBefore, jump)),
  /** The sound window (core-car Sound). */
  soundWrites: (a: SoundSettings, b: SoundSettings): DspWrite[] => JSON.parse(K!.Bridge.soundWrites(JSON.stringify(a), JSON.stringify(b))),
  /** The vendor calls a change of the camera settings makes (core-car Camera.writes). */
  /** The guidelines as the vendor draws them, bent by the CAN steering angle [eps] (core-car Camera.guides). */
  cameraGuides: (guidelines: Guidelines, eps: number): Guides => JSON.parse(K!.Bridge.cameraGuides(guidelines, eps)),
  /** The saved phones to ask in turn after a start, the last one connected first (core-car Startup). */
  startupPhones: (saved: string[], last: string | null): string[] => JSON.parse(K!.Bridge.startupPhones(JSON.stringify(saved), last)),
  startupConnectWaitMs: () => K!.Bridge.startupConnectWaitMs(),
  startupConnectTryMs: () => K!.Bridge.startupConnectTryMs(),
  /** The name phones list the unit under in Smart View. */
  castName: () => K!.Bridge.castName(),
  cameraWrites: (a: CameraSettings, b: CameraSettings): DspWrite[] => JSON.parse(K!.Bridge.cameraWrites(JSON.stringify(a), JSON.stringify(b))),
  soundPreset: (s: SoundSettings, p: EqPreset): SoundSettings => JSON.parse(K!.Bridge.soundPreset(JSON.stringify(s), p)),
  soundBand: (s: SoundSettings, band: number, db: number): SoundSettings => JSON.parse(K!.Bridge.soundBand(JSON.stringify(s), band, db)),
  soundResetLoudness: (s: SoundSettings): SoundSettings => JSON.parse(K!.Bridge.soundResetLoudness(JSON.stringify(s))),
  soundResetBass: (s: SoundSettings): SoundSettings => JSON.parse(K!.Bridge.soundResetBass(JSON.stringify(s))),
  soundBands: (): string[] => JSON.parse(K!.Bridge.soundBands()),
  soundHpf: (): number[] => JSON.parse(K!.Bridge.soundHpf()),
  soundLpf: (): number[] => JSON.parse(K!.Bridge.soundLpf()),
  callDuration: (ms: number) => K!.Bridge.callDuration(ms),
  /** The big clock's text on the 24- or 12-hour clock, and the half of the day beside a 12-hour one. */
  clockText: (hour: number, minute: number, h24: boolean) => K!.Bridge.clockText(hour, minute, h24),
  meridiem: (hour: number) => K!.Bridge.meridiem(hour),
  /** The commands a car start sends to bring a loud last level down to the settings' start volume. */
  startVolume: (state: CarState, cap: number): CarCommand[] => JSON.parse(K!.Bridge.startVolume(JSON.stringify(state), cap)),
  size: (bytes: number) => K!.Bridge.size(bytes),
};
