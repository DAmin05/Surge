import { BuyerFlow } from "@/components/BuyerFlow";

export default async function EventPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <BuyerFlow eventId={Number(id)} />;
}
