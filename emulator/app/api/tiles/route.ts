// Which map packs are on this laptop, so the emulator draws from them instead of the network.
import { packs } from "../../../server/mapPacks";

export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ packs: packs() });
}
