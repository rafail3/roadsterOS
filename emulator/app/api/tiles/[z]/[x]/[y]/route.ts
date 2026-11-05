// Vector tiles from the local map packs, as the unit reads them; a tile outside every pack is empty.
import { tile } from "../../../../../../server/mapPacks";

export const dynamic = "force-dynamic";

export async function GET(_: Request, { params }: { params: Promise<{ z: string; x: string; y: string }> }) {
  const { z, x, y } = await params;
  const data = tile(Number(z), Number(x), Number(y.replace(/\.pbf$/, "")));
  if (!data) return new Response(null, { status: 204 });
  return new Response(new Uint8Array(data), { headers: { "Content-Type": "application/x-protobuf", "Content-Encoding": "gzip", "Cache-Control": "max-age=3600" } });
}
