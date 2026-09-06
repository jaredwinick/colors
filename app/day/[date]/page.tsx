import { renderArchivePage } from "../../archive-page";

export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ date: string }>;
};

export default async function ArchiveDay({ params }: Props) {
  const { date } = await params;
  return renderArchivePage(date);
}
