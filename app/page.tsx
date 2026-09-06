import { localDateForInstant } from "../db/capture-archive";
import { getDisplayTimeZone } from "../db/captures";
import { redirect } from "next/navigation";
import { archiveUrl } from "./archive-navigation";

export const dynamic = "force-dynamic";

export default function Home() {
  const timeZone = getDisplayTimeZone();
  const currentDate = localDateForInstant(new Date(), timeZone);
  redirect(archiveUrl(currentDate));
}
